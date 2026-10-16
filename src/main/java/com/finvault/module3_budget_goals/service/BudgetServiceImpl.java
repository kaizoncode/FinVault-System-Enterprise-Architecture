package com.finvault.module3_budget_goals.service;

import com.finvault.common.BusinessException;
import com.finvault.module1_security_rbac.entity.User;
import com.finvault.module1_security_rbac.repository.UserRepository;
import com.finvault.module2_wallet_ledger.entity.Category;
import com.finvault.module2_wallet_ledger.entity.Wallet;
import com.finvault.module2_wallet_ledger.repository.CategoryRepository;
import com.finvault.module2_wallet_ledger.repository.WalletRepository;
import com.finvault.module3_budget_goals.dto.Budget50_30_20Request;
import com.finvault.module3_budget_goals.dto.BudgetRequest;
import com.finvault.module3_budget_goals.dto.BudgetResponseDto;
import com.finvault.module3_budget_goals.entity.AllocationType;
import com.finvault.module3_budget_goals.entity.Budget;
import com.finvault.module3_budget_goals.entity.BudgetType;
import com.finvault.module3_budget_goals.repository.BudgetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ==============================================================================
 * [Owner: Member 3 - Budget & Financial Goals Module]
 * BudgetServiceImpl: Triển khai toàn diện nghiệp vụ Quản lý Ngân sách thông minh
 *
 * Tính năng chính:
 * - Khởi tạo ngân sách cá nhân / ngân sách theo danh mục thu chi
 * - Tự động thiết lập trọn gói 3 ngân sách theo Tỷ lệ vàng 50/30/20 (Needs/Wants/Savings)
 * - Tra cứu và cập nhật tiến độ chi tiêu thời gian thực
 * - Kiểm soát phân quyền IDOR chặt chẽ giữa các người dùng
 * - Cung cấp mẫu ngân sách chuẩn (STANDARD_TEMPLATE) cho toàn hệ thống
 * ==============================================================================
 */
@Service
@Transactional
public class BudgetServiceImpl implements BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetServiceImpl.class);

    private final BudgetRepository budgetRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final WalletRepository walletRepository;

    public BudgetServiceImpl(BudgetRepository budgetRepository,
                             UserRepository userRepository,
                             CategoryRepository categoryRepository,
                             WalletRepository walletRepository) {
        this.budgetRepository = budgetRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.walletRepository = walletRepository;
    }

    @Override
    public BudgetResponseDto createBudget(String username, BudgetRequest request) {
        log.info("Khởi tạo ngân sách mới cho người dùng: '{}', tên ngân sách: '{}'", username, request.getBudgetName());
        validateBudgetDates(request.getStartDate(), request.getEndDate());

        User user = getUserByUsername(username);

        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findByIdAndIsDeletedFalse(request.getCategoryId())
                    .orElseThrow(() -> new BusinessException("CATEGORY_NOT_FOUND", "Không tìm thấy danh mục chi tiêu với ID: " + request.getCategoryId()));
        }

        Wallet wallet = null;
        if (request.getWalletId() != null) {
            wallet = walletRepository.findByIdAndIsDeletedFalse(request.getWalletId())
                    .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví với ID: " + request.getWalletId()));
            validateWalletAccess(wallet, user);
        }

        BudgetType type = (request.getBudgetType() != null) ? request.getBudgetType() : BudgetType.CUSTOM;
        AllocationType allocationType = (request.getAllocationType() != null) ? request.getAllocationType() : AllocationType.NEEDS;

        Budget budget = new Budget(
                user,
                category,
                wallet,
                request.getBudgetName(),
                type,
                allocationType,
                request.getAllocatedAmount(),
                request.getStartDate(),
                request.getEndDate()
        );

        Budget savedBudget = budgetRepository.save(budget);
        log.info("Khởi tạo thành công ngân sách ID: {} cho user: '{}'", savedBudget.getId(), username);
        return BudgetResponseDto.fromEntity(savedBudget);
    }

    @Override
    public List<BudgetResponseDto> create50_30_20Budgets(String username, Budget50_30_20Request request) {
        log.info("Khởi tạo bộ 3 ngân sách 50/30/20 cho user: '{}', thu nhập: {}", username, request.getMonthlyIncome());
        validateBudgetDates(request.getStartDate(), request.getEndDate());

        User user = getUserByUsername(username);

        Wallet wallet = null;
        if (request.getWalletId() != null) {
            wallet = walletRepository.findByIdAndIsDeletedFalse(request.getWalletId())
                    .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví với ID: " + request.getWalletId()));
            validateWalletAccess(wallet, user);
        }

        BigDecimal monthlyIncome = request.getMonthlyIncome();
        BigDecimal needsAmount = monthlyIncome.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal wantsAmount = monthlyIncome.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal savingsAmount = monthlyIncome.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);

        List<Budget> budgetsToCreate = new ArrayList<>();

        // 1. Ngân sách 50% Nhu cầu thiết yếu (Needs)
        Budget needsBudget = new Budget(
                user,
                null,
                wallet,
                "Ngân sách Nhu cầu thiết yếu (Needs - 50%)",
                BudgetType.RULE_50_30_20,
                AllocationType.NEEDS,
                needsAmount,
                request.getStartDate(),
                request.getEndDate()
        );
        budgetsToCreate.add(needsBudget);

        // 2. Ngân sách 30% Mong muốn / Sở thích (Wants)
        Budget wantsBudget = new Budget(
                user,
                null,
                wallet,
                "Ngân sách Sở thích & Mong muốn (Wants - 30%)",
                BudgetType.RULE_50_30_20,
                AllocationType.WANTS,
                wantsAmount,
                request.getStartDate(),
                request.getEndDate()
        );
        budgetsToCreate.add(wantsBudget);

        // 3. Ngân sách 20% Tích lũy tiết kiệm (Savings)
        Budget savingsBudget = new Budget(
                user,
                null,
                wallet,
                "Ngân sách Tích lũy & Đầu tư (Savings - 20%)",
                BudgetType.RULE_50_30_20,
                AllocationType.SAVINGS,
                savingsAmount,
                request.getStartDate(),
                request.getEndDate()
        );
        budgetsToCreate.add(savingsBudget);

        List<Budget> savedBudgets = budgetRepository.saveAll(budgetsToCreate);
        log.info("Khởi tạo thành công bộ 3 ngân sách 50/30/20 cho user: '{}'", username);
        return savedBudgets.stream()
                .map(BudgetResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public BudgetResponseDto updateBudget(Long budgetId, String username, BudgetRequest request) {
        log.info("Cập nhật ngân sách ID: {} bởi user: '{}'", budgetId, username);
        validateBudgetDates(request.getStartDate(), request.getEndDate());

        Budget budget = getBudgetEntityById(budgetId);
        validateBudgetOwnership(budget, username);

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findByIdAndIsDeletedFalse(request.getCategoryId())
                    .orElseThrow(() -> new BusinessException("CATEGORY_NOT_FOUND", "Không tìm thấy danh mục chi tiêu với ID: " + request.getCategoryId()));
            budget.setCategory(category);
        } else {
            budget.setCategory(null);
        }

        if (request.getWalletId() != null) {
            Wallet wallet = walletRepository.findByIdAndIsDeletedFalse(request.getWalletId())
                    .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví với ID: " + request.getWalletId()));
            validateWalletAccess(wallet, budget.getUser());
            budget.setWallet(wallet);
        } else {
            budget.setWallet(null);
        }

        budget.setBudgetName(request.getBudgetName());
        budget.setAllocatedAmount(request.getAllocatedAmount());
        budget.setStartDate(request.getStartDate());
        budget.setEndDate(request.getEndDate());
        if (request.getBudgetType() != null) {
            budget.setBudgetType(request.getBudgetType());
        }
        if (request.getAllocationType() != null) {
            budget.setAllocationType(request.getAllocationType());
        }

        // Tự động reset cờ cảnh báo nếu người dùng nâng hạn mức ngân sách cao hơn số tiền đã chi
        if (budget.getSpentAmount().compareTo(budget.getAllocatedAmount()) < 0) {
            budget.setAlertThreshold100Sent(false);
            BigDecimal eightyPercent = budget.getAllocatedAmount().multiply(new BigDecimal("0.80"));
            if (budget.getSpentAmount().compareTo(eightyPercent) < 0) {
                budget.setAlertThreshold80Sent(false);
            }
        }

        Budget updated = budgetRepository.save(budget);
        log.info("Cập nhật hoàn tất ngân sách ID: {}", updated.getId());
        return BudgetResponseDto.fromEntity(updated);
    }

    @Override
    public void deleteBudget(Long budgetId, String username) {
        log.info("Xóa (Soft delete) ngân sách ID: {} bởi user: '{}'", budgetId, username);
        Budget budget = getBudgetEntityById(budgetId);
        validateBudgetOwnership(budget, username);

        budget.setIsDeleted(true);
        budget.setIsActive(false);
        budgetRepository.save(budget);
        log.info("Đã soft-delete ngân sách ID: {}", budgetId);
    }

    @Override
    @Transactional(readOnly = true)
    public BudgetResponseDto getBudgetById(Long budgetId, String username) {
        Budget budget = getBudgetEntityById(budgetId);
        validateBudgetOwnership(budget, username);
        return BudgetResponseDto.fromEntity(budget);
    }

    @Override
    @Transactional(readOnly = true)
    public Budget getBudgetEntityById(Long budgetId) {
        return budgetRepository.findByIdAndIsDeletedFalse(budgetId)
                .orElseThrow(() -> new BusinessException("BUDGET_NOT_FOUND", "Không tìm thấy ngân sách với ID: " + budgetId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetResponseDto> getUserBudgets(String username) {
        User user = getUserByUsername(username);
        return budgetRepository.findAllByUserIdAndIsDeletedFalse(user.getId()).stream()
                .map(BudgetResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetResponseDto> getActiveBudgets(String username) {
        User user = getUserByUsername(username);
        LocalDate today = LocalDate.now();
        return budgetRepository.findActiveBudgetsByDate(user.getId(), today).stream()
                .map(BudgetResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetResponseDto> getStandardTemplates() {
        return budgetRepository.findAllByBudgetTypeAndIsDeletedFalse(BudgetType.STANDARD_TEMPLATE).stream()
                .map(BudgetResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public BudgetResponseDto createStandardTemplate(String adminUsername, BudgetRequest request) {
        log.info("Admin '{}' khởi tạo mẫu ngân sách hệ thống chuẩn: '{}'", adminUsername, request.getBudgetName());
        validateBudgetDates(request.getStartDate(), request.getEndDate());

        User adminUser = getUserByUsername(adminUsername);
        Budget template = new Budget(
                adminUser,
                null,
                null,
                request.getBudgetName(),
                BudgetType.STANDARD_TEMPLATE,
                (request.getAllocationType() != null) ? request.getAllocationType() : AllocationType.NEEDS,
                request.getAllocatedAmount(),
                request.getStartDate(),
                request.getEndDate()
        );

        Budget saved = budgetRepository.save(template);
        log.info("Đã tạo mẫu ngân sách chuẩn ID: {}", saved.getId());
        return BudgetResponseDto.fromEntity(saved);
    }

    private User getUserByUsername(String username) {
        return userRepository.findByUsernameAndIsDeletedFalse(username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Không tìm thấy người dùng: " + username));
    }

    private void validateBudgetDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new BusinessException("INVALID_DATES", "Ngày bắt đầu và ngày kết thúc ngân sách không được null");
        }
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("INVALID_DATE_RANGE", "Ngày kết thúc ngân sách không thể trước ngày bắt đầu");
        }
    }

    private void validateBudgetOwnership(Budget budget, String username) {
        if (budget.getBudgetType() == BudgetType.STANDARD_TEMPLATE) {
            return; // Mẫu chuẩn hệ thống cho phép đọc chung
        }
        if (budget.getUser() == null || !budget.getUser().getUsername().equals(username)) {
            throw new BusinessException("ACCESS_DENIED", "Bạn không có quyền truy cập hoặc chỉnh sửa ngân sách này");
        }
    }

    private void validateWalletAccess(Wallet wallet, User user) {
        if (wallet.getUser() != null && !wallet.getUser().getId().equals(user.getId())) {
            boolean isMember = wallet.getMembers().stream()
                    .anyMatch(m -> m.getUser().getId().equals(user.getId()) && Boolean.TRUE.equals(m.getIsActive()));
            if (!isMember) {
                throw new BusinessException("WALLET_ACCESS_DENIED", "Bạn không có quyền liên kết ví ID: " + wallet.getId());
            }
        }
    }
}
