package com.finvault.module3_budget_goals.event;

import com.finvault.module2_wallet_ledger.entity.ApprovalStatus;
import com.finvault.module2_wallet_ledger.entity.Transaction;
import com.finvault.module2_wallet_ledger.entity.TransactionType;
import com.finvault.module2_wallet_ledger.event.TransactionCreatedEvent;
import com.finvault.module2_wallet_ledger.event.TransactionDeletedEvent;
import com.finvault.module3_budget_goals.dto.BudgetAlertNotificationDto;
import com.finvault.module3_budget_goals.entity.Budget;
import com.finvault.module3_budget_goals.repository.BudgetRepository;
import com.finvault.module3_budget_goals.rule.BudgetAlertRuleEngine;
import com.finvault.module3_budget_goals.websocket.BudgetAlertWebSocketSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ==============================================================================
 * [Owner: Member 3 - Budget & Financial Goals Module]
 * TransactionBudgetEventListener: Thành phần lắng nghe sự kiện giao dịch tài chính (Observer Pattern)
 *
 * Quy trình xử lý tự động:
 * 1. Lắng nghe TransactionCreatedEvent từ Module 2 (Core Ledger).
 * 2. Lọc các giao dịch chi tiêu (EXPENSE) đã được phê duyệt (APPROVED).
 * 3. Trích xuất primitive IDs từ Event nhằm chống LazyInitializationException trong ngữ cảnh @Async.
 * 4. Tìm các ngân sách theo Danh mục cụ thể VÀ ngân sách phân bổ chung 50/30/20 (category IS NULL).
 * 5. Tự động ghi nhận số tiền chi tiêu (recordExpense) và cập nhật số dư ngân sách.
 * 6. Kích hoạt BudgetAlertRuleEngine (Strategy Pattern) để kiểm tra ngưỡng 80% / 100%.
 * 7. Tự động đẩy cảnh báo tức thì qua WebSocket tới màn hình chính của người dùng.
 * ==============================================================================
 */
@Component
public class TransactionBudgetEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionBudgetEventListener.class);

    private final BudgetRepository budgetRepository;
    private final BudgetAlertRuleEngine alertRuleEngine;
    private final BudgetAlertWebSocketSender webSocketSender;

    public TransactionBudgetEventListener(
            BudgetRepository budgetRepository,
            BudgetAlertRuleEngine alertRuleEngine,
            BudgetAlertWebSocketSender webSocketSender) {
        this.budgetRepository = budgetRepository;
        this.alertRuleEngine = alertRuleEngine;
        this.webSocketSender = webSocketSender;
    }

    @Async
    @EventListener
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100)
    )
    @Transactional
    public void onTransactionCreated(TransactionCreatedEvent event) {
        if (event == null) {
            return;
        }

        // Chỉ xử lý các giao dịch chi tiêu đã được phê duyệt chính thức
        if (event.getTransactionType() != TransactionType.EXPENSE || event.getApprovalStatus() != ApprovalStatus.APPROVED) {
            return;
        }

        Long userId = event.getUserId();
        String username = event.getUsername();
        if (userId == null) {
            log.warn("Bỏ qua cập nhật ngân sách vì userId trong sự kiện giao dịch là null (txId: {})", event.getTransactionId());
            return;
        }

        BigDecimal amount = event.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Bỏ qua sự kiện chi tiêu với số tiền không hợp lệ hoặc <= 0");
            return;
        }

        LocalDate txDate = (event.getTransactionDate() != null)
                ? event.getTransactionDate().toLocalDate()
                : LocalDate.now();
        Long categoryId = event.getCategoryId();

        log.debug("Observer Pattern: Nhận sự kiện chi tiêu ID [{}] số tiền {} VND của user [{}]",
                event.getTransactionId(), amount, username);

        // 1. Tìm các ngân sách khớp với danh mục cụ thể của giao dịch (nếu giao dịch có danh mục)
        List<Budget> categoryBudgets = (categoryId != null)
                ? budgetRepository.findActiveBudgetsByCategoryAndDate(userId, categoryId, txDate)
                : Collections.emptyList();

        // 2. Tìm thêm ngân sách chung / quy tắc 50-30-20 (category IS NULL) của người dùng
        List<Budget> generalBudgets = budgetRepository.findActiveBudgetsWithNullCategoryByUserAndDate(userId, txDate);

        // Hợp nhất danh sách để cập nhật đồng thời, đảm bảo không trùng lặp ngân sách
        Set<Long> processedBudgetIds = new HashSet<>();
        List<Budget> matchingBudgets = new ArrayList<>();

        for (Budget b : categoryBudgets) {
            if (b != null && b.getId() != null && processedBudgetIds.add(b.getId())) {
                matchingBudgets.add(b);
            }
        }
        for (Budget b : generalBudgets) {
            if (b != null && b.getId() != null && processedBudgetIds.add(b.getId())) {
                matchingBudgets.add(b);
            }
        }

        // Fallback linh hoạt: Nếu cả 2 đều rỗng và categoryId == null, tìm theo toàn bộ active budgets của ngày đó
        if (matchingBudgets.isEmpty() && categoryId == null) {
            for (Budget b : budgetRepository.findActiveBudgetsByDate(userId, txDate)) {
                if (b != null && b.getId() != null && processedBudgetIds.add(b.getId())) {
                    matchingBudgets.add(b);
                }
            }
        }

        if (matchingBudgets.isEmpty()) {
            log.debug("Không tìm thấy ngân sách nào đang áp dụng cho giao dịch ID [{}] của user [{}]", event.getTransactionId(), userId);
            return;
        }

        for (Budget budget : matchingBudgets) {
            // Cập nhật số tiền đã chi
            budget.recordExpense(amount);

            // Đánh giá quy tắc cảnh báo bằng Rule Engine (Strategy Pattern)
            Optional<BudgetAlertNotificationDto> alertOpt = alertRuleEngine.evaluateAndCreateAlert(budget);

            // Lưu ngân sách với số tiền chi tiêu mới và cờ cảnh báo đã cập nhật
            budgetRepository.save(budget);

            // Nếu chạm ngưỡng cảnh báo -> Bắn WebSocket tới người dùng
            alertOpt.ifPresent(alert -> {
                if (username != null) {
                    webSocketSender.sendAlert(username, alert);
                    log.warn("Đã phát hiện và phát cảnh báo ngân sách [{}] cho người dùng [{}] qua WebSocket",
                            budget.getBudgetName(), username);
                }
            });
        }
    }

    /**
     * Lắng nghe sự kiện xóa giao dịch để tự động hoàn tác (giảm) spentAmount của ngân sách tương ứng.
     */
    @Async
    @EventListener
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100)
    )
    @Transactional
    public void onTransactionDeleted(TransactionDeletedEvent event) {
        if (event == null) {
            return;
        }

        // Chỉ hoàn tác chi tiêu đối với các giao dịch chi tiêu (EXPENSE) đã được phê duyệt (APPROVED)
        if (event.getTransactionType() != TransactionType.EXPENSE || event.getApprovalStatus() != ApprovalStatus.APPROVED) {
            return;
        }

        Long userId = event.getUserId();
        String username = event.getUsername();
        if (userId == null) {
            log.warn("Bỏ qua giảm ngân sách vì userId trong sự kiện xóa giao dịch là null (txId: {})", event.getTransactionId());
            return;
        }

        BigDecimal amount = event.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Bỏ qua sự kiện xóa chi tiêu với số tiền không hợp lệ hoặc <= 0");
            return;
        }

        LocalDate txDate = (event.getTransactionDate() != null)
                ? event.getTransactionDate().toLocalDate()
                : LocalDate.now();
        Long categoryId = event.getCategoryId();

        log.debug("Observer Pattern: Nhận sự kiện XÓA chi tiêu ID [{}] số tiền {} VND của user [{}]",
                event.getTransactionId(), amount, username);

        // 1. Tìm các ngân sách khớp với danh mục cụ thể của giao dịch (nếu giao dịch có danh mục)
        List<Budget> categoryBudgets = (categoryId != null)
                ? budgetRepository.findActiveBudgetsByCategoryAndDate(userId, categoryId, txDate)
                : Collections.emptyList();

        // 2. Tìm thêm ngân sách chung / quy tắc 50-30-20 (category IS NULL) của người dùng
        List<Budget> generalBudgets = budgetRepository.findActiveBudgetsWithNullCategoryByUserAndDate(userId, txDate);

        // Hợp nhất danh sách để cập nhật đồng thời, đảm bảo không trùng lặp ngân sách
        Set<Long> processedBudgetIds = new HashSet<>();
        List<Budget> matchingBudgets = new ArrayList<>();

        for (Budget b : categoryBudgets) {
            if (b != null && b.getId() != null && processedBudgetIds.add(b.getId())) {
                matchingBudgets.add(b);
            }
        }
        for (Budget b : generalBudgets) {
            if (b != null && b.getId() != null && processedBudgetIds.add(b.getId())) {
                matchingBudgets.add(b);
            }
        }

        // Fallback linh hoạt: Nếu cả 2 đều rỗng và categoryId == null, tìm theo toàn bộ active budgets của ngày đó
        if (matchingBudgets.isEmpty() && categoryId == null) {
            for (Budget b : budgetRepository.findActiveBudgetsByDate(userId, txDate)) {
                if (b != null && b.getId() != null && processedBudgetIds.add(b.getId())) {
                    matchingBudgets.add(b);
                }
            }
        }

        if (matchingBudgets.isEmpty()) {
            log.debug("Không tìm thấy ngân sách nào đang áp dụng để giảm cho giao dịch xóa ID [{}] của user [{}]",
                    event.getTransactionId(), userId);
            return;
        }

        for (Budget budget : matchingBudgets) {
            // Giảm số tiền đã chi
            budget.revertExpense(amount);
            budgetRepository.save(budget);
            log.info("Đã giảm spentAmount cho ngân sách [{}] (mới: {} VND) sau khi xóa giao dịch ID [{}] của user [{}]",
                    budget.getBudgetName(), budget.getSpentAmount(), event.getTransactionId(), username);
        }
    }
}
