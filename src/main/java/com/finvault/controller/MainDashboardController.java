package com.finvault.controller;

import com.finvault.common.SecurityUtils;
import com.finvault.module1_security_rbac.dto.UserResponseDto;
import com.finvault.module1_security_rbac.service.UserService;
import com.finvault.module2_wallet_ledger.dto.CategoryResponseDto;
import com.finvault.module2_wallet_ledger.dto.TransactionResponseDto;
import com.finvault.module2_wallet_ledger.dto.WalletResponseDto;
import com.finvault.module2_wallet_ledger.service.CategoryService;
import com.finvault.module2_wallet_ledger.service.TransactionService;
import com.finvault.module2_wallet_ledger.service.WalletService;
import com.finvault.module3_budget_goals.dto.BudgetResponseDto;
import com.finvault.module3_budget_goals.dto.SavingGoalResponseDto;
import com.finvault.module3_budget_goals.service.BudgetService;
import com.finvault.module3_budget_goals.service.SavingGoalService;
import com.finvault.module4_analytics_reporting.dto.FinancialHealthScoreDto;
import com.finvault.module4_analytics_reporting.service.FinancialHealthService;
import com.finvault.module5_admin_audit.dto.AuditLogResponseDto;
import com.finvault.module5_admin_audit.dto.DataRecoveryResponseDto;
import com.finvault.module5_admin_audit.dto.SystemConfigResponseDto;
import com.finvault.module5_admin_audit.dto.SystemNotificationResponseDto;
import com.finvault.module5_admin_audit.service.AuditLogService;
import com.finvault.module5_admin_audit.service.SupportTicketRecoveryService;
import com.finvault.module5_admin_audit.service.SystemConfigService;
import com.finvault.module5_admin_audit.service.SystemNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ==============================================================================
 * [Phase 7 - Architecture Core & System Integration]
 * MainDashboardController: Master Dashboard Controller tích hợp toàn diện 5 phân hệ
 * Cung cấp giao diện Live Dashboard In-Place RBAC chung cho cả 4 nhóm quyền:
 * 1. ROLE_SUPER_ADMIN: Bảng điều khiển Live CMS, khôi phục dữ liệu đã xóa mềm
 * 2. ROLE_SYSTEM_AUDITOR: Bảng giám sát an ninh mạng, Audit Log, Data Masking
 * 3. ROLE_FAMILY_HEAD: Bảng quản trị Quỹ chung gia đình & Phê duyệt chi tiêu
 * 4. ROLE_STANDARD_USER: Bảng quản lý ví, ngân sách 50/30/20, mục tiêu tiết kiệm, điểm CFP
 * ==============================================================================
 */
@Controller
public class MainDashboardController {

    private static final Logger log = LoggerFactory.getLogger(MainDashboardController.class);

    private final UserService userService;
    private final WalletService walletService;
    private final TransactionService transactionService;
    private final BudgetService budgetService;
    private final SavingGoalService savingGoalService;
    private final FinancialHealthService financialHealthService;
    private final CategoryService categoryService;
    private final SystemConfigService systemConfigService;
    private final SystemNotificationService systemNotificationService;
    private final AuditLogService auditLogService;
    private final SupportTicketRecoveryService recoveryService;

    public MainDashboardController(
            UserService userService,
            WalletService walletService,
            TransactionService transactionService,
            BudgetService budgetService,
            SavingGoalService savingGoalService,
            FinancialHealthService financialHealthService,
            CategoryService categoryService,
            SystemConfigService systemConfigService,
            SystemNotificationService systemNotificationService,
            AuditLogService auditLogService,
            SupportTicketRecoveryService recoveryService) {
        this.userService = userService;
        this.walletService = walletService;
        this.transactionService = transactionService;
        this.budgetService = budgetService;
        this.savingGoalService = savingGoalService;
        this.financialHealthService = financialHealthService;
        this.categoryService = categoryService;
        this.systemConfigService = systemConfigService;
        this.systemNotificationService = systemNotificationService;
        this.auditLogService = auditLogService;
        this.recoveryService = recoveryService;
    }

    @GetMapping("/")
    public String rootRedirect() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    @PreAuthorize("isAuthenticated()")
    public String showDashboard(Model model) {
        String username = SecurityUtils.getCurrentUsername();
        log.info("Khởi tạo dữ liệu giao diện Live Dashboard cho người dùng: [{}]", username);

        // 1. Thông tin người dùng hiện tại & phân quyền đa cấp
        UserResponseDto currentUser = userService.getUserByUsername(username);
        boolean isSuperAdmin = SecurityUtils.isSuperAdmin();
        boolean isSystemAuditor = SecurityUtils.isSystemAuditor();
        boolean isFamilyHead = SecurityUtils.isFamilyHead();
        boolean isStandardUser = SecurityUtils.isStandardUser();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("username", username);
        model.addAttribute("isSuperAdmin", isSuperAdmin);
        model.addAttribute("isSystemAuditor", isSystemAuditor);
        model.addAttribute("isFamilyHead", isFamilyHead);
        model.addAttribute("isStandardUser", isStandardUser);

        // 2. Banner thông báo hệ thống và các thông báo hoạt động
        String bannerMessage = systemConfigService.getConfigValue(
                "SYSTEM_BANNER_MESSAGE",
                "Hệ thống FinVault Enterprise đang hoạt động ổn định với cơ chế Live UI & Dynamic RBAC."
        );
        model.addAttribute("bannerMessage", bannerMessage);

        List<SystemNotificationResponseDto> activeNotifications = systemNotificationService.getActiveNotifications();
        model.addAttribute("activeNotifications", activeNotifications);

        // 3. Dữ liệu ví tài chính cá nhân & gia đình
        List<WalletResponseDto> userWallets = walletService.getUserWallets(username);
        model.addAttribute("wallets", userWallets);

        // Tính tổng số dư khả dụng (Có áp dụng Data Masking nếu là Auditor)
        BigDecimal totalBalance = BigDecimal.ZERO;
        for (WalletResponseDto w : userWallets) {
            if (w.getBalance() != null) {
                totalBalance = totalBalance.add(w.getBalance());
            }
        }
        String totalBalanceFormatted = SecurityUtils.formatCurrencyWithMasking(totalBalance, "VND");
        model.addAttribute("totalBalanceFormatted", totalBalanceFormatted);
        model.addAttribute("totalBalanceRaw", totalBalance);

        // 4. Lịch sử 10 giao dịch gần nhất
        Page<TransactionResponseDto> txPage = transactionService.getUserTransactions(
                username,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "transactionDate"))
        );
        List<TransactionResponseDto> recentTransactions = txPage != null ? txPage.getContent() : Collections.emptyList();
        model.addAttribute("recentTransactions", recentTransactions);

        // 5. Ngân sách thông minh 50/30/20 & Quỹ mục tiêu tiết kiệm
        List<BudgetResponseDto> activeBudgets = budgetService.getActiveBudgets(username);
        model.addAttribute("activeBudgets", activeBudgets);

        List<SavingGoalResponseDto> savingGoals = savingGoalService.getUserGoals(username);
        model.addAttribute("savingGoals", savingGoals);

        // 6. Điểm sức khỏe tài chính toàn diện CFP 4 Trụ cột
        try {
            FinancialHealthScoreDto healthScore = financialHealthService.calculateFinancialHealth(username);
            model.addAttribute("healthScore", healthScore);
        } catch (Exception ex) {
            log.warn("Không thể tính điểm sức khỏe tài chính cho [{}]: {}", username, ex.getMessage());
            model.addAttribute("healthScore", null);
        }

        // 7. Danh mục thu chi khả dụng để phục vụ Modal thêm giao dịch nhanh
        List<CategoryResponseDto> categories = categoryService.getAvailableCategories(username);
        model.addAttribute("categories", categories);

        // 8. KHỐI TÍNH NĂNG ĐẶC BIỆT DÀNH CHO GIA TRƯỞNG (ROLE_FAMILY_HEAD)
        if (isFamilyHead || isSuperAdmin) {
            List<TransactionResponseDto> pendingApprovals = transactionService.getPendingFamilyTransactions(username);
            model.addAttribute("pendingApprovals", pendingApprovals);

            List<WalletResponseDto> familyWallets = walletService.getFamilyWallets(username);
            model.addAttribute("familyWallets", familyWallets);
        } else {
            model.addAttribute("pendingApprovals", Collections.emptyList());
            model.addAttribute("familyWallets", Collections.emptyList());
        }

        // 9. KHỐI TÍNH NĂNG ĐẶC BIỆT DÀNH CHO KIỂM TOÁN VIÊN (ROLE_SYSTEM_AUDITOR)
        if (isSystemAuditor || isSuperAdmin) {
            List<AuditLogResponseDto> recentAuditLogs = auditLogService.getRecentAuditLogs();
            model.addAttribute("recentAuditLogs", recentAuditLogs);

            List<UserResponseDto> lockedAccounts = auditLogService.getCurrentlyLockedAccounts();
            model.addAttribute("lockedAccounts", lockedAccounts);
        } else {
            model.addAttribute("recentAuditLogs", Collections.emptyList());
            model.addAttribute("lockedAccounts", Collections.emptyList());
        }

        // 10. KHỐI TÍNH NĂNG ĐẶC BIỆT DÀNH CHO QUẢN TRỊ VIÊN CẤP CAO (ROLE_SUPER_ADMIN)
        if (isSuperAdmin) {
            List<SystemConfigResponseDto> systemConfigs = systemConfigService.getAllConfigs();
            model.addAttribute("systemConfigs", systemConfigs);

            List<SystemNotificationResponseDto> allNotifications = systemNotificationService.getAllNotifications();
            model.addAttribute("allNotifications", allNotifications);

            List<DataRecoveryResponseDto> softDeletedItems = recoveryService.getSoftDeletedItems();
            model.addAttribute("softDeletedItems", softDeletedItems);

            List<UserResponseDto> allUsers = userService.getAllUsers();
            model.addAttribute("allUsers", allUsers);
        } else {
            model.addAttribute("systemConfigs", Collections.emptyList());
            model.addAttribute("allNotifications", Collections.emptyList());
            model.addAttribute("softDeletedItems", Collections.emptyList());
            model.addAttribute("allUsers", Collections.emptyList());
        }

        return "dashboard";
    }
}
