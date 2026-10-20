package com.finvault.module2_wallet_ledger.service;

import com.finvault.common.BusinessException;
import com.finvault.module1_security_rbac.entity.Role;
import com.finvault.module1_security_rbac.entity.User;
import com.finvault.module1_security_rbac.service.UserService;
import com.finvault.module2_wallet_ledger.dto.*;
import com.finvault.module2_wallet_ledger.entity.*;
import com.finvault.module2_wallet_ledger.event.TransactionCreatedEvent;
import com.finvault.module2_wallet_ledger.event.TransactionDeletedEvent;
import com.finvault.module2_wallet_ledger.repository.CategoryRepository;
import com.finvault.module2_wallet_ledger.repository.TransactionRepository;
import com.finvault.module2_wallet_ledger.repository.WalletMemberRepository;
import com.finvault.module2_wallet_ledger.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ==============================================================================
 * [Owner: Member 2 - Core Ledger & Multi-Wallet Module]
 * TransactionServiceImpl: Triển khai Lõi Giao dịch Sổ cái (Ledger Engine)
 *
 * Tính năng kỹ thuật chuyên sâu:
 * 1. Chống Race Condition: Sử dụng Pessimistic Locking (@Lock PESSIMISTIC_WRITE)
 *    khi đọc và cập nhật số dư ví, đảm bảo nguyên tử (Atomicity) theo chuẩn ACID.
 * 2. Chống Deadlock liên ví: Khi chuyển tiền, khóa ví theo thứ tự ID tăng dần (Resource Ordering).
 * 3. Workflow Phê duyệt Quỹ gia đình: Khoản chi >= 5,000,000 VND tự động chuyển sang PENDING,
 *    chờ Gia trưởng (ROLE_FAMILY_HEAD) hoặc Super Admin phê duyệt mới được trừ tiền.
 * 4. Event-Driven Architecture: Tự động phát TransactionCreatedEvent để Module 3 và Module 5
 *    xử lý ngân sách và kiểm toán bất đồng bộ.
 * ==============================================================================
 */
@Service
public class TransactionServiceImpl implements TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionServiceImpl.class);

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final WalletMemberRepository walletMemberRepository;
    private final CategoryRepository categoryRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${finvault.family.approval-threshold-vnd:5000000}")
    private BigDecimal approvalThreshold;

    public TransactionServiceImpl(
            TransactionRepository transactionRepository,
            WalletRepository walletRepository,
            WalletMemberRepository walletMemberRepository,
            CategoryRepository categoryRepository,
            UserService userService,
            ApplicationEventPublisher eventPublisher) {
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.walletMemberRepository = walletMemberRepository;
        this.categoryRepository = categoryRepository;
        this.userService = userService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public TransactionResponseDto createTransaction(String username, TransactionRequest request) {
        User user = userService.getUserEntityByUsername(username);

        // Khóa bi quan hàng Ví để cập nhật số dư chống Race Condition
        Wallet wallet = walletRepository.findByIdForUpdate(request.getWalletId())
                .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví có ID: " + request.getWalletId(), HttpStatus.NOT_FOUND));

        // Kiểm tra ví có đang hoạt động hay không (chống giao dịch trên ví bị đóng băng)
        if (!Boolean.TRUE.equals(wallet.getIsActive())) {
            throw new BusinessException("WALLET_INACTIVE", "Ví đã bị đóng băng hoặc ngưng hoạt động", HttpStatus.BAD_REQUEST);
        }

        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findByIdAndIsDeletedFalse(request.getCategoryId())
                    .orElseThrow(() -> new BusinessException("CATEGORY_NOT_FOUND", "Không tìm thấy danh mục có ID: " + request.getCategoryId(), HttpStatus.NOT_FOUND));

            // Kiểm tra tương thích giữa Loại Danh mục và Loại Giao dịch
            if (request.getTransactionType() == TransactionType.INCOME && category.getCategoryType() != CategoryType.INCOME) {
                throw new BusinessException("CATEGORY_TYPE_MISMATCH", "Danh mục đã chọn không phải là danh mục Thu nhập");
            }
            if (request.getTransactionType() == TransactionType.EXPENSE && category.getCategoryType() != CategoryType.EXPENSE) {
                throw new BusinessException("CATEGORY_TYPE_MISMATCH", "Danh mục đã chọn không phải là danh mục Chi tiêu");
            }
        }

        Transaction transaction = new Transaction();
        transaction.setWallet(wallet);
        transaction.setUser(user);
        transaction.setCategory(category);
        transaction.setTransactionType(request.getTransactionType());
        transaction.setAmount(request.getAmount());
        transaction.setTransactionDate(request.getTransactionDate() != null ? request.getTransactionDate() : LocalDateTime.now());
        transaction.setNote(request.getNote() != null ? request.getNote().trim() : null);
        validateReceiptUrl(request.getReceiptUrl());
        transaction.setReceiptUrl(request.getReceiptUrl());
        transaction.setIsRecurring(Boolean.TRUE.equals(request.getIsRecurring()));
        transaction.setRecurringInterval(request.getRecurringInterval());

        // Kiểm tra quyền hạn và phân luồng nghiệp vụ
        if (Boolean.TRUE.equals(wallet.getIsFamilyWallet())) {
            handleFamilyFundTransaction(transaction, wallet, user, request.getAmount(), request.getTransactionType());
        } else {
            handleIndividualWalletTransaction(transaction, wallet, user, request.getAmount(), request.getTransactionType());
        }

        Transaction saved = transactionRepository.save(transaction);

        // Nếu giao dịch đã được phê duyệt ngay -> Phát sự kiện Event-Driven
        if (saved.getApprovalStatus() == ApprovalStatus.APPROVED) {
            eventPublisher.publishEvent(new TransactionCreatedEvent(this, saved));
            log.info("Giao dịch [{}] đã hoàn tất thành công. Số dư mới ví [{}]: {}",
                    saved.getId(), wallet.getWalletName(), wallet.getBalance());
        } else {
            log.warn("Giao dịch chi tiêu gia đình [{}] vượt ngưỡng {} VND -> Chuyển trạng thái PENDING chờ Gia trưởng duyệt.",
                    saved.getId(), approvalThreshold);
        }

        return TransactionResponseDto.fromEntity(saved);
    }

    private void handleIndividualWalletTransaction(Transaction transaction, Wallet wallet, User user, BigDecimal amount, TransactionType type) {
        boolean isOwner = wallet.getUser().getId().equals(user.getId());
        boolean isSuperAdmin = user.hasRole(Role.ROLE_SUPER_ADMIN);
        if (!isOwner && !isSuperAdmin) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền tạo giao dịch trên ví này", HttpStatus.FORBIDDEN);
        }

        if (type == TransactionType.INCOME) {
            wallet.deposit(amount);
        } else if (type == TransactionType.EXPENSE) {
            wallet.withdraw(amount);
        } else {
            throw new BusinessException("INVALID_TYPE", "Vui lòng sử dụng tính năng chuyển tiền cho giao dịch TRANSFER");
        }

        transaction.setApprovalStatus(ApprovalStatus.APPROVED);
        walletRepository.save(wallet);
    }

    private void handleFamilyFundTransaction(Transaction transaction, Wallet wallet, User user, BigDecimal amount, TransactionType type) {
        boolean isOwner = wallet.getUser().getId().equals(user.getId());
        boolean isSuperAdmin = user.hasRole(Role.ROLE_SUPER_ADMIN);
        boolean isFamilyHead = user.hasRole(Role.ROLE_FAMILY_HEAD);

        Optional<WalletMember> memberOpt = walletMemberRepository.findByWalletIdAndUserId(wallet.getId(), user.getId());
        if (!isOwner && !isSuperAdmin && memberOpt.isEmpty()) {
            throw new BusinessException("FORBIDDEN", "Bạn không phải là thành viên của Quỹ gia đình này", HttpStatus.FORBIDDEN);
        }

        if (memberOpt.isPresent()) {
            WalletMember member = memberOpt.get();
            if (member.getMemberRole() == WalletMemberRole.VIEWER) {
                throw new BusinessException("FORBIDDEN", "Thành viên với quyền VIEWER không được tạo giao dịch");
            }
            // Kiểm tra hạn mức chi cá nhân
            if (type == TransactionType.EXPENSE && member.getSpendingLimit().compareTo(BigDecimal.ZERO) > 0) {
                if (amount.compareTo(member.getSpendingLimit()) > 0) {
                    throw new BusinessException("SPENDING_LIMIT_EXCEEDED",
                            String.format("Khoản chi (%s VND) vượt quá hạn mức cá nhân được cấp (%s VND)",
                                    amount.toPlainString(), member.getSpendingLimit().toPlainString()));
                }
            }
        }

        if (type == TransactionType.INCOME) {
            // Nạp tiền vào quỹ gia đình luôn được duyệt ngay
            wallet.deposit(amount);
            transaction.setApprovalStatus(ApprovalStatus.APPROVED);
            walletRepository.save(wallet);
            return;
        }

        if (type == TransactionType.TRANSFER) {
            throw new BusinessException("INVALID_TYPE", "Vui lòng sử dụng tính năng chuyển tiền riêng biệt cho giao dịch TRANSFER");
        }

        // Với giao dịch chi tiêu (EXPENSE): Kiểm tra ngưỡng duyệt chi lớn
        boolean requiresApproval = (amount.compareTo(approvalThreshold) >= 0) && !isOwner && !isSuperAdmin && !isFamilyHead;

        if (requiresApproval) {
            // Tạm thời KHÔNG trừ tiền ví, chuyển sang trạng thái chờ duyệt
            transaction.setApprovalStatus(ApprovalStatus.PENDING);
        } else {
            // Chi tiêu được duyệt ngay -> Trừ tiền ví
            wallet.withdraw(amount);
            transaction.setApprovalStatus(ApprovalStatus.APPROVED);
            walletRepository.save(wallet);
        }
    }

    @Override
    @Transactional
    public TransactionResponseDto transferFunds(String username, TransferRequest request) {
        User user = userService.getUserEntityByUsername(username);

        if (request.getSourceWalletId().equals(request.getTargetWalletId())) {
            throw new BusinessException("SAME_WALLET_TRANSFER", "Ví nguồn và ví đích không được trùng nhau");
        }

        // Chống Deadlock: Luôn khóa ví có ID nhỏ hơn trước, sau đó khóa ví có ID lớn hơn
        Long firstLockId = Math.min(request.getSourceWalletId(), request.getTargetWalletId());
        Long secondLockId = Math.max(request.getSourceWalletId(), request.getTargetWalletId());

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví ID: " + firstLockId, HttpStatus.NOT_FOUND));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví ID: " + secondLockId, HttpStatus.NOT_FOUND));

        Wallet sourceWallet = firstLockId.equals(request.getSourceWalletId()) ? firstWallet : secondWallet;
        Wallet targetWallet = firstLockId.equals(request.getTargetWalletId()) ? firstWallet : secondWallet;

        // Kiểm tra ví có đang hoạt động hay không (chống chuyển tiền từ/đến ví bị đóng băng)
        if (!Boolean.TRUE.equals(sourceWallet.getIsActive())) {
            throw new BusinessException("WALLET_INACTIVE", "Ví nguồn đã bị đóng băng hoặc ngưng hoạt động", HttpStatus.BAD_REQUEST);
        }
        if (!Boolean.TRUE.equals(targetWallet.getIsActive())) {
            throw new BusinessException("WALLET_INACTIVE", "Ví đích đã bị đóng băng hoặc ngưng hoạt động", HttpStatus.BAD_REQUEST);
        }

        // Kiểm tra quyền thao tác trên ví nguồn
        boolean isSourceOwner = sourceWallet.getUser().getId().equals(user.getId());
        boolean isSuperAdmin = user.hasRole(Role.ROLE_SUPER_ADMIN);
        if (!isSourceOwner && !isSuperAdmin) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền chuyển tiền từ ví nguồn này", HttpStatus.FORBIDDEN);
        }

        // Kiểm tra tính tương thích loại tiền tệ giữa ví nguồn và ví đích
        if (!sourceWallet.getCurrency().equalsIgnoreCase(targetWallet.getCurrency())) {
            throw new BusinessException("CURRENCY_MISMATCH",
                    String.format("Không thể chuyển tiền giữa 2 ví khác loại tiền tệ (%s và %s) khi chưa có tỷ giá quy đổi",
                            sourceWallet.getCurrency(), targetWallet.getCurrency()));
        }

        // Kiểm tra hạn mức chi tiêu hàng tháng trên ví nguồn nếu có cấu hình (chống vượt hạn mức - [L-M3])
        if (sourceWallet.getWalletType() != WalletType.CREDIT_CARD
                && sourceWallet.getMonthlySpendingLimit() != null
                && sourceWallet.getMonthlySpendingLimit().compareTo(BigDecimal.ZERO) > 0) {
            LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
            LocalDateTime endOfMonth = LocalDate.now().with(TemporalAdjusters.lastDayOfMonth()).atTime(23, 59, 59);
            BigDecimal currentMonthSpent = transactionRepository.sumExpenseAndTransferByWalletAndDateRange(
                    sourceWallet.getId(), startOfMonth, endOfMonth);
            if (currentMonthSpent == null) {
                currentMonthSpent = BigDecimal.ZERO;
            }
            BigDecimal projectedTotal = currentMonthSpent.add(request.getAmount());
            if (projectedTotal.compareTo(sourceWallet.getMonthlySpendingLimit()) > 0) {
                throw new BusinessException("SPENDING_LIMIT_EXCEEDED",
                        String.format("Tổng chi tiêu và chuyển tiền trong tháng (%s VND) sẽ vượt quá hạn mức tối đa của ví nguồn (%s VND)",
                                projectedTotal.toPlainString(), sourceWallet.getMonthlySpendingLimit().toPlainString()));
            }
        }

        // Thực hiện giao dịch nguyên tử (ACID)
        sourceWallet.withdraw(request.getAmount());
        targetWallet.deposit(request.getAmount());

        walletRepository.save(sourceWallet);
        walletRepository.save(targetWallet);

        LocalDateTime now = LocalDateTime.now();
        String baseNote = (request.getNote() != null && !request.getNote().isBlank()) ? request.getNote().trim() : "Chuyển tiền liên ví";

        // Bản ghi 1: Ví nguồn (Debit / Ghi nợ / OUTGOING)
        Transaction debitTransfer = new Transaction();
        debitTransfer.setWallet(sourceWallet);
        debitTransfer.setTargetWallet(targetWallet);
        debitTransfer.setUser(user);
        debitTransfer.setTransactionType(TransactionType.TRANSFER);
        debitTransfer.setTransferDirection(TransferDirection.OUTGOING);
        debitTransfer.setAmount(request.getAmount());
        debitTransfer.setTransactionDate(now);
        debitTransfer.setNote("Chuyển tới " + targetWallet.getWalletName() + ": " + baseNote);
        debitTransfer.setApprovalStatus(ApprovalStatus.APPROVED);

        Transaction savedDebit = transactionRepository.save(debitTransfer);

        // Bản ghi 2: Ví đích (Credit / Ghi có / INCOMING) - Đảm bảo lịch sử giao dịch ví nhận không bị thiếu vết kiểm toán [L-C2]
        Transaction creditTransfer = new Transaction();
        creditTransfer.setWallet(targetWallet);
        creditTransfer.setTargetWallet(sourceWallet);
        creditTransfer.setUser(user);
        creditTransfer.setTransactionType(TransactionType.TRANSFER);
        creditTransfer.setTransferDirection(TransferDirection.INCOMING);
        creditTransfer.setLinkedTransactionId(savedDebit.getId());
        creditTransfer.setAmount(request.getAmount());
        creditTransfer.setTransactionDate(now);
        creditTransfer.setNote("Nhận từ " + sourceWallet.getWalletName() + ": " + baseNote);
        creditTransfer.setApprovalStatus(ApprovalStatus.APPROVED);

        Transaction savedCredit = transactionRepository.save(creditTransfer);

        // Cập nhật linkedTransactionId cho bản ghi ghi nợ để liên kết 2 chiều
        savedDebit.setLinkedTransactionId(savedCredit.getId());
        transactionRepository.save(savedDebit);

        eventPublisher.publishEvent(new TransactionCreatedEvent(this, savedDebit));
        log.info("Chuyển tiền thành công {} VND từ ví [{}] sang ví [{}] bởi [{}]",
                request.getAmount(), sourceWallet.getWalletName(), targetWallet.getWalletName(), username);

        return TransactionResponseDto.fromEntity(savedDebit);
    }

    @Override
    @Transactional
    public TransactionResponseDto approveOrRejectTransaction(Long transactionId, String approverUsername, ApprovalDecisionDto decision) {
        User approver = userService.getUserEntityByUsername(approverUsername);
        Transaction transaction = getTransactionEntityById(transactionId);

        if (transaction.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException("NOT_PENDING", "Giao dịch này không ở trạng thái chờ duyệt");
        }

        Wallet wallet = transaction.getWallet();
        boolean isOwner = wallet.getUser().getId().equals(approver.getId());
        boolean isSuperAdmin = approver.hasRole(Role.ROLE_SUPER_ADMIN);
        boolean isWalletAdminOrOwner = walletMemberRepository.findByWalletIdAndUserId(wallet.getId(), approver.getId())
                .map(m -> (m.getMemberRole() == WalletMemberRole.ADMIN || m.getMemberRole() == WalletMemberRole.OWNER) && Boolean.TRUE.equals(m.getIsActive()))
                .orElse(false);

        if (!isOwner && !isSuperAdmin && !isWalletAdminOrOwner) {
            throw new BusinessException("FORBIDDEN", "Chỉ Chủ sở hữu hoặc Quản trị viên của Quỹ gia đình này mới có quyền duyệt giao dịch", HttpStatus.FORBIDDEN);
        }

        if (Boolean.TRUE.equals(decision.getApproved())) {
            // Khi duyệt -> Khóa ví và kiểm tra số dư trước khi trừ tiền
            Wallet lockedWallet = walletRepository.findByIdForUpdate(wallet.getId())
                    .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví", HttpStatus.NOT_FOUND));

            if (lockedWallet.getWalletType() != WalletType.CREDIT_CARD && lockedWallet.getBalance().compareTo(transaction.getAmount()) < 0) {
                throw new BusinessException("INSUFFICIENT_FUNDS_FOR_APPROVAL",
                        String.format("Không thể phê duyệt: Số dư ví hiện tại (%s %s) không đủ để chi khoản %s %s",
                                lockedWallet.getBalance().toPlainString(), lockedWallet.getCurrency(),
                                transaction.getAmount().toPlainString(), lockedWallet.getCurrency()));
            }

            lockedWallet.withdraw(transaction.getAmount());
            walletRepository.save(lockedWallet);

            transaction.setApprovalStatus(ApprovalStatus.APPROVED);
            transaction.setApprovedBy(approverUsername);
            transaction.setApprovedAt(LocalDateTime.now());
            log.info("Gia trưởng [{}] đã DUYỆT khoản chi lớn {} VND cho giao dịch [{}]", approverUsername, transaction.getAmount(), transactionId);

            eventPublisher.publishEvent(new TransactionCreatedEvent(this, transaction));
        } else {
            // Khi từ chối -> Không trừ tiền, ghi nhận lý do
            transaction.setApprovalStatus(ApprovalStatus.REJECTED);
            transaction.setRejectionReason(decision.getRejectionReason() != null ? decision.getRejectionReason().trim() : "Gia trưởng từ chối");
            log.info("Gia trưởng [{}] đã TỪ CHỐI khoản chi lớn cho giao dịch [{}]. Lý do: {}",
                    approverUsername, transactionId, transaction.getRejectionReason());
        }

        Transaction updated = transactionRepository.save(transaction);
        return TransactionResponseDto.fromEntity(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponseDto> getTransactionsByWallet(Long walletId, String username, Pageable pageable) {
        User user = userService.getUserEntityByUsername(username);
        Wallet wallet = walletRepository.findByIdAndIsDeletedFalse(walletId)
                .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví", HttpStatus.NOT_FOUND));

        boolean isSuperAdmin = user.hasRole(Role.ROLE_SUPER_ADMIN);
        boolean isOwner = wallet.getUser().getId().equals(user.getId());
        boolean isMember = wallet.getMembers().stream().anyMatch(m -> m.getUser().getId().equals(user.getId()) && Boolean.TRUE.equals(m.getIsActive()));

        if (!isSuperAdmin && !isOwner && !isMember) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền xem giao dịch của ví này", HttpStatus.FORBIDDEN);
        }

        return transactionRepository.findAllByWalletIdAndIsDeletedFalseOrderByTransactionDateDesc(walletId, pageable)
                .map(TransactionResponseDto::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponseDto> getUserTransactions(String username, Pageable pageable) {
        User user = userService.getUserEntityByUsername(username);
        return transactionRepository.findAllByUserIdAndIsDeletedFalseOrderByTransactionDateDesc(user.getId(), pageable)
                .map(TransactionResponseDto::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionResponseDto> getPendingFamilyTransactions(String approverUsername) {
        User approver = userService.getUserEntityByUsername(approverUsername);
        return transactionRepository.findPendingFamilyTransactions(approver.getId()).stream()
                .map(TransactionResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponseDto getTransactionById(Long transactionId, String username) {
        User user = userService.getUserEntityByUsername(username);
        Transaction transaction = getTransactionEntityById(transactionId);

        boolean isSuperAdmin = user.hasRole(Role.ROLE_SUPER_ADMIN);
        boolean isCreator = transaction.getUser().getId().equals(user.getId());
        boolean isWalletOwner = transaction.getWallet().getUser().getId().equals(user.getId());
        boolean isMember = transaction.getWallet().getMembers().stream().anyMatch(m -> m.getUser().getId().equals(user.getId()) && Boolean.TRUE.equals(m.getIsActive()));

        if (!isSuperAdmin && !isCreator && !isWalletOwner && !isMember) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền xem chi tiết giao dịch này", HttpStatus.FORBIDDEN);
        }

        return TransactionResponseDto.fromEntity(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public Transaction getTransactionEntityById(Long transactionId) {
        return transactionRepository.findByIdAndIsDeletedFalse(transactionId)
                .orElseThrow(() -> new BusinessException("TRANSACTION_NOT_FOUND", "Không tìm thấy giao dịch ID: " + transactionId, HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteTransaction(Long transactionId, String username) {
        User user = userService.getUserEntityByUsername(username);
        Transaction transaction = getTransactionEntityById(transactionId);

        boolean isSuperAdmin = user.hasRole(Role.ROLE_SUPER_ADMIN);
        boolean isCreator = transaction.getUser().getId().equals(user.getId());
        boolean isWalletOwner = transaction.getWallet().getUser().getId().equals(user.getId());

        if (!isSuperAdmin && !isCreator && !isWalletOwner) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền xóa giao dịch này", HttpStatus.FORBIDDEN);
        }

        // Nếu giao dịch đã APPROVED, hoàn trả lại số dư tương ứng cho ví [L-C1]
        if (transaction.getApprovalStatus() == ApprovalStatus.APPROVED) {
            if (transaction.getTransactionType() == TransactionType.INCOME) {
                Wallet wallet = walletRepository.findByIdForUpdate(transaction.getWallet().getId())
                        .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví liên kết", HttpStatus.NOT_FOUND));
                // Issue 7: Kiểm tra số dư ví trước khi hoàn tác thu nhập (tránh overdraft crash)
                if (wallet.getBalance().compareTo(transaction.getAmount()) < 0) {
                    throw new BusinessException("CANNOT_REVERSE_INCOME",
                            "Không thể xóa khoản thu nhập này vì số dư hiện tại không đủ. Vui lòng xóa các khoản chi tiêu liên quan trước.",
                            HttpStatus.BAD_REQUEST);
                }
                // Thu nhập bị xóa -> trừ tiền lại khỏi ví
                wallet.withdraw(transaction.getAmount());
                walletRepository.save(wallet);
            } else if (transaction.getTransactionType() == TransactionType.EXPENSE) {
                Wallet wallet = walletRepository.findByIdForUpdate(transaction.getWallet().getId())
                        .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví liên kết", HttpStatus.NOT_FOUND));
                // Chi tiêu bị xóa -> hoàn trả tiền vào ví
                wallet.deposit(transaction.getAmount());
                walletRepository.save(wallet);
            } else if (transaction.getTransactionType() == TransactionType.TRANSFER) {
                // Chuyển tiền bị xóa -> đảo ngược nghiệp vụ an toàn, chống deadlock bằng khóa thứ tự ID tăng dần
                handleTransferDeletion(transaction);
            }
        }

        transaction.setIsDeleted(true);
        transactionRepository.save(transaction);
        log.info("Người dùng [{}] đã soft-delete giao dịch [{}]. Số dư ví đã được hoàn trả chính xác.", username, transactionId);

        // Phát sự kiện xóa giao dịch để Module 3 tự động giảm spentAmount của Ngân sách
        eventPublisher.publishEvent(new TransactionDeletedEvent(this, transaction));
    }

    private void handleTransferDeletion(Transaction transaction) {
        boolean isIncoming = isIncomingTransfer(transaction);
        Wallet sourceWallet = isIncoming ? transaction.getTargetWallet() : transaction.getWallet();
        Wallet destWallet = isIncoming ? transaction.getWallet() : transaction.getTargetWallet();

        Long id1 = (transaction.getWallet() != null) ? transaction.getWallet().getId() : null;
        Long id2 = (transaction.getTargetWallet() != null) ? transaction.getTargetWallet().getId() : null;

        if (id1 != null && id2 != null) {
            // Issue 8: Lock wallets theo thứ tự ID tăng dần để chống triệt để Deadlock
            Long firstId = Math.min(id1, id2);
            Long secondId = Math.max(id1, id2);

            Wallet firstWallet = walletRepository.findByIdForUpdate(firstId)
                    .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví liên kết ID: " + firstId, HttpStatus.NOT_FOUND));
            Wallet secondWallet = firstId.equals(secondId) ? firstWallet : walletRepository.findByIdForUpdate(secondId)
                    .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví liên kết ID: " + secondId, HttpStatus.NOT_FOUND));

            Wallet lockedSource = (sourceWallet != null && sourceWallet.getId().equals(firstId)) ? firstWallet : secondWallet;
            Wallet lockedDest = (destWallet != null && destWallet.getId().equals(firstId)) ? firstWallet : secondWallet;

            // Kiểm tra số dư ví đích trước khi thu hồi tiền chuyển
            if (lockedDest.getBalance().compareTo(transaction.getAmount()) < 0) {
                throw new BusinessException("CANNOT_REVERSE_TRANSFER",
                        "Không thể xóa giao dịch chuyển tiền vì số dư hiện tại của ví nhận không đủ để hoàn tác.",
                        HttpStatus.BAD_REQUEST);
            }

            // Đảo ngược chuyển tiền: Hoàn lại tiền cho ví nguồn, thu hồi tiền từ ví đích
            lockedSource.deposit(transaction.getAmount());
            lockedDest.withdraw(transaction.getAmount());

            walletRepository.save(lockedSource);
            walletRepository.save(lockedDest);
            log.info("Đã đảo ngược số dư transfer: hoàn {} cho ví nguồn [{}] và thu hồi từ ví đích [{}]",
                    transaction.getAmount(), lockedSource.getId(), lockedDest.getId());
        } else if (sourceWallet != null) {
            Wallet lockedSource = walletRepository.findByIdForUpdate(sourceWallet.getId())
                    .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví liên kết", HttpStatus.NOT_FOUND));
            lockedSource.deposit(transaction.getAmount());
            walletRepository.save(lockedSource);
        }

        // Tự động soft-delete bản ghi đối ứng để không bị mồ côi (orphaned)
        if (transaction.getLinkedTransactionId() != null) {
            transactionRepository.findById(transaction.getLinkedTransactionId()).ifPresent(linkedTx -> {
                if (!Boolean.TRUE.equals(linkedTx.getIsDeleted())) {
                    linkedTx.setIsDeleted(true);
                    transactionRepository.save(linkedTx);
                    log.info("Đã tự động soft-delete bản ghi đối ứng [{}] của giao dịch chuyển khoản [{}]", linkedTx.getId(), transaction.getId());
                }
            });
        }
    }

    private boolean isIncomingTransfer(Transaction tx) {
        if (tx.getTransferDirection() != null) {
            return tx.getTransferDirection() == TransferDirection.INCOMING;
        }
        // Fallback linh hoạt cho các bản ghi transfer cũ chưa gắn TransferDirection
        if (tx.getNote() != null && tx.getNote().trim().toLowerCase().startsWith("nhận từ")) {
            return true;
        }
        return false;
    }

    private void validateReceiptUrl(String receiptUrl) {
        if (receiptUrl == null || receiptUrl.isBlank()) {
            return;
        }
        String lower = receiptUrl.trim().toLowerCase();
        if (lower.startsWith("/uploads/")) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(receiptUrl.trim());
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme)) {
                throw new BusinessException("INVALID_RECEIPT_URL", "Hóa đơn đính kèm chỉ hỗ trợ giao thức HTTPS hoặc thư mục nội bộ /uploads/");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank() || "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.")
                    || host.startsWith("169.254.") || host.endsWith(".internal") || host.endsWith(".local")) {
                throw new BusinessException("SSRF_DETECTED", "Địa chỉ URL hóa đơn không hợp lệ hoặc trỏ tới mạng nội bộ");
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_RECEIPT_URL", "Định dạng URL hóa đơn không hợp lệ");
        }
    }
}
