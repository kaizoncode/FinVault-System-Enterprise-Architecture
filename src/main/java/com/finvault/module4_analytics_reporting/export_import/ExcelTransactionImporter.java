package com.finvault.module4_analytics_reporting.export_import;

import com.finvault.common.BusinessException;
import com.finvault.module1_security_rbac.entity.User;
import com.finvault.module1_security_rbac.repository.UserRepository;
import com.finvault.module2_wallet_ledger.entity.ApprovalStatus;
import com.finvault.module2_wallet_ledger.entity.Category;
import com.finvault.module2_wallet_ledger.entity.Transaction;
import com.finvault.module2_wallet_ledger.entity.TransactionType;
import com.finvault.module2_wallet_ledger.entity.Wallet;
import com.finvault.module2_wallet_ledger.entity.WalletMember;
import com.finvault.module2_wallet_ledger.entity.WalletMemberRole;
import com.finvault.module2_wallet_ledger.repository.CategoryRepository;
import com.finvault.module2_wallet_ledger.repository.TransactionRepository;
import com.finvault.module2_wallet_ledger.repository.WalletMemberRepository;
import com.finvault.module2_wallet_ledger.repository.WalletRepository;
import com.finvault.module4_analytics_reporting.dto.ImportReportResultDto;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * ==============================================================================
 * [Owner: Member 4 - Analytics, BI & Reporting Module]
 * ExcelTransactionImporter: Đọc và nhập hàng loạt dữ liệu giao dịch từ file Excel/CSV
 *
 * Tính năng thông minh chống lỗi:
 * - Kiểm tra trùng lặp giao dịch (Duplicate Detection): Ngăn chặn nhập 2 lần cùng một giao dịch
 *   (Cùng số tiền, ví, ngày và nội dung mô tả).
 * - Tự động đối chiếu và liên kết danh mục theo tên hoặc ID.
 * - Khóa ví bằng Pessimistic Lock (`findByIdForUpdate`) để cập nhật số dư ví chính xác và an toàn.
 * - Trả về báo cáo chi tiết: Số dòng thành công, Số dòng bỏ qua (trùng), Số dòng thất bại và nguyên nhân.
 * ==============================================================================
 */
@Component
public class ExcelTransactionImporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelTransactionImporter.class);
    private static final DateTimeFormatter[] DATE_FORMATTERS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    };

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final WalletMemberRepository walletMemberRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Value("${finvault.family.approval-threshold-vnd:5000000}")
    private BigDecimal approvalThreshold;

    public ExcelTransactionImporter(TransactionRepository transactionRepository,
                                    WalletRepository walletRepository,
                                    WalletMemberRepository walletMemberRepository,
                                    CategoryRepository categoryRepository,
                                    UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.walletMemberRepository = walletMemberRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ImportReportResultDto importTransactionsFromExcel(String username, Long defaultWalletId, InputStream inputStream) {
        log.info("Bắt đầu xử lý nhập hàng loạt giao dịch từ Excel cho user: '{}'", username);
        ImportReportResultDto result = new ImportReportResultDto();

        User user = userRepository.findByUsernameAndIsDeletedFalse(username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Không tìm thấy người dùng: " + username));

        Wallet defaultWallet = null;
        if (defaultWalletId != null) {
            defaultWallet = walletRepository.findByIdForUpdate(defaultWalletId)
                    .orElseThrow(() -> new BusinessException("WALLET_NOT_FOUND", "Không tìm thấy ví mặc định ID: " + defaultWalletId));
        }

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            int rowIndex = 0;
            // Bỏ qua dòng header nếu có
            if (rowIterator.hasNext()) {
                rowIterator.next();
                rowIndex++;
            }

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                rowIndex++;
                result.setTotalRowsProcessed(result.getTotalRowsProcessed() + 1);

                try {
                    // Cột 0: Ngày giao dịch
                    LocalDateTime txDate = parseRowDateTime(row.getCell(0));

                    // Cột 1: Loại giao dịch (INCOME / EXPENSE)
                    String typeStr = getCellStringValue(row.getCell(1));
                    TransactionType txType = parseTransactionType(typeStr);

                    // Cột 2: Số tiền
                    BigDecimal amount = parseAmount(row.getCell(2));
                    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                        result.setFailureCount(result.getFailureCount() + 1);
                        result.addErrorMessage("Dòng " + rowIndex + ": Số tiền phải lớn hơn 0");
                        continue;
                    }

                    // Cột 3: Tên danh mục hoặc ID danh mục
                    String categoryStr = getCellStringValue(row.getCell(3));
                    Category category = resolveCategory(user.getId(), categoryStr);

                    // Cột 4: Mô tả / Ghi chú
                    String description = getCellStringValue(row.getCell(4));

                    // Cột 5 (Tùy chọn): ID Ví cụ thể
                    Wallet targetWallet = defaultWallet;
                    String walletIdStr = getCellStringValue(row.getCell(5));
                    if (walletIdStr != null && !walletIdStr.isBlank()) {
                        try {
                            Long wId = Long.parseLong(walletIdStr.trim());
                            targetWallet = walletRepository.findByIdForUpdate(wId).orElse(defaultWallet);
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    if (targetWallet == null) {
                        result.setFailureCount(result.getFailureCount() + 1);
                        result.addErrorMessage("Dòng " + rowIndex + ": Không xác định được ví tài chính nhận giao dịch");
                        continue;
                    }

                    // Kiểm tra quyền hạn trên ví (Chống lỗ hổng IDOR - [H-3])
                    boolean isOwner = targetWallet.getUser().getId().equals(user.getId());
                    Optional<WalletMember> memberOpt = walletMemberRepository.findByWalletIdAndUserId(targetWallet.getId(), user.getId());
                    boolean isMember = memberOpt.map(m -> Boolean.TRUE.equals(m.getIsActive())).orElse(false);

                    if (!isOwner && !isMember) {
                        result.setFailureCount(result.getFailureCount() + 1);
                        result.addErrorMessage("Dòng " + rowIndex + ": Bạn không có quyền thêm giao dịch vào ví ID: " + targetWallet.getId());
                        continue;
                    }
                    if (!isOwner && memberOpt.isPresent() && memberOpt.get().getMemberRole() == WalletMemberRole.VIEWER) {
                        result.setFailureCount(result.getFailureCount() + 1);
                        result.addErrorMessage("Dòng " + rowIndex + ": Thành viên với quyền VIEWER không được thêm giao dịch vào ví");
                        continue;
                    }

                    // Kiểm tra trùng lặp (Duplicate Detection)
                    boolean isDuplicate = checkDuplicateTransaction(user.getId(), targetWallet.getId(), amount, txDate, description);
                    if (isDuplicate) {
                        result.setSkippedDuplicatesCount(result.getSkippedDuplicatesCount() + 1);
                        log.debug("Dòng {}: Bỏ qua giao dịch trùng lặp [{}] số tiền {}", rowIndex, description, amount);
                        continue;
                    }

                    // Quy trình phê duyệt quỹ gia đình - [L-C3]
                    boolean isFamilyWallet = Boolean.TRUE.equals(targetWallet.getIsFamilyWallet());
                    boolean requiresApproval = isFamilyWallet
                            && (txType == TransactionType.EXPENSE)
                            && (amount.compareTo(approvalThreshold) >= 0)
                            && !isOwner;

                    Transaction tx = new Transaction(
                            targetWallet,
                            user,
                            category,
                            txType,
                            amount,
                            txDate,
                            (description != null && !description.isBlank()) ? description : "Nhập từ file Excel"
                    );

                    if (requiresApproval) {
                        // Tạm thời KHÔNG trừ tiền ví, chuyển sang trạng thái chờ duyệt
                        tx.setApprovalStatus(ApprovalStatus.PENDING);
                    } else {
                        // Cập nhật số dư ví
                        if (txType == TransactionType.INCOME) {
                            targetWallet.deposit(amount);
                        } else if (txType == TransactionType.EXPENSE) {
                            targetWallet.withdraw(amount);
                        }
                        walletRepository.save(targetWallet);
                        tx.setApprovalStatus(ApprovalStatus.APPROVED);
                    }

                    transactionRepository.save(tx);
                    result.setSuccessCount(result.getSuccessCount() + 1);

                } catch (Exception e) {
                    result.setFailureCount(result.getFailureCount() + 1);
                    result.addErrorMessage("Dòng " + rowIndex + ": " + e.getMessage());
                    log.warn("Lỗi khi xử lý dòng {} trong file Excel: {}", rowIndex, e.getMessage());
                }
            }

            log.info("Hoàn tất nhập dữ liệu Excel. Tổng: {}, Thành công: {}, Trùng bỏ qua: {}, Lỗi: {}",
                    result.getTotalRowsProcessed(), result.getSuccessCount(), result.getSkippedDuplicatesCount(), result.getFailureCount());
            return result;
        } catch (Exception e) {
            log.error("Lỗi khi đọc file Excel nhập giao dịch: {}", e.getMessage(), e);
            throw new BusinessException("EXCEL_IMPORT_FAILED", "Không thể đọc định dạng file Excel: " + e.getMessage());
        }
    }

    private LocalDateTime parseRowDateTime(Cell cell) {
        if (cell == null) {
            return LocalDateTime.now();
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue();
        }
        String str = getCellStringValue(cell);
        if (str == null || str.isBlank()) {
            return LocalDateTime.now();
        }

        for (DateTimeFormatter dtf : DATE_FORMATTERS) {
            try {
                if (str.length() <= 10) {
                    return java.time.LocalDate.parse(str.trim(), dtf).atStartOfDay();
                }
                return LocalDateTime.parse(str.trim(), dtf);
            } catch (Exception ignored) {
            }
        }
        return LocalDateTime.now();
    }

    private TransactionType parseTransactionType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return TransactionType.EXPENSE;
        }
        String clean = typeStr.trim().toUpperCase();
        if (clean.contains("THU") || clean.contains("INCOME")) {
            return TransactionType.INCOME;
        }
        return TransactionType.EXPENSE;
    }

    private BigDecimal parseAmount(Cell cell) {
        if (cell == null) {
            return BigDecimal.ZERO;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        String str = getCellStringValue(cell);
        if (str == null || str.isBlank()) {
            return BigDecimal.ZERO;
        }
        String clean = str.replaceAll("[^0-9.]", "");
        try {
            return new BigDecimal(clean);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue().trim();
        } else if (cell.getCellType() == CellType.NUMERIC) {
            return String.valueOf((long) cell.getNumericCellValue());
        } else if (cell.getCellType() == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "";
    }

    private Category resolveCategory(Long userId, String categoryStr) {
        if (categoryStr == null || categoryStr.isBlank()) {
            return null;
        }
        // Thử tìm theo ID
        try {
            Long catId = Long.parseLong(categoryStr.trim());
            Optional<Category> opt = categoryRepository.findByIdAndIsDeletedFalse(catId);
            if (opt.isPresent()) return opt.get();
        } catch (NumberFormatException ignored) {
        }

        // Tìm theo tên danh mục
        List<Category> userCats = categoryRepository.findAvailableCategoriesForUser(userId);
        for (Category c : userCats) {
            if (c.getCategoryName().equalsIgnoreCase(categoryStr.trim())) {
                return c;
            }
        }
        return null;
    }

    private boolean checkDuplicateTransaction(Long userId, Long walletId, BigDecimal amount, LocalDateTime date, String description) {
        LocalDateTime windowStart = date.minusMinutes(5);
        LocalDateTime windowEnd = date.plusMinutes(5);

        List<Transaction> existing = transactionRepository.findTransactionsByWalletAndDateRange(walletId, windowStart, windowEnd);
        return existing.stream().anyMatch(tx ->
                tx.getUser().getId().equals(userId) &&
                tx.getAmount().compareTo(amount) == 0 &&
                ((description == null && tx.getDescription() == null) ||
                 (description != null && description.equalsIgnoreCase(tx.getDescription())))
        );
    }
}
