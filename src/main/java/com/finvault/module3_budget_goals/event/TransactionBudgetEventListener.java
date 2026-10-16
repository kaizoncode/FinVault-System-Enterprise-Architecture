package com.finvault.module3_budget_goals.event;

import com.finvault.module2_wallet_ledger.entity.ApprovalStatus;
import com.finvault.module2_wallet_ledger.entity.Transaction;
import com.finvault.module2_wallet_ledger.entity.TransactionType;
import com.finvault.module2_wallet_ledger.event.TransactionCreatedEvent;
import com.finvault.module3_budget_goals.dto.BudgetAlertNotificationDto;
import com.finvault.module3_budget_goals.entity.Budget;
import com.finvault.module3_budget_goals.repository.BudgetRepository;
import com.finvault.module3_budget_goals.rule.BudgetAlertRuleEngine;
import com.finvault.module3_budget_goals.websocket.BudgetAlertWebSocketSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * ==============================================================================
 * [Owner: Member 3 - Budget & Financial Goals Module]
 * TransactionBudgetEventListener: Thành phần lắng nghe sự kiện giao dịch tài chính (Observer Pattern)
 *
 * Quy trình xử lý tự động:
 * 1. Lắng nghe TransactionCreatedEvent từ Module 2 (Core Ledger).
 * 2. Lọc các giao dịch chi tiêu (EXPENSE) đã được phê duyệt (APPROVED).
 * 3. Tìm các ngân sách đang hoạt động của người dùng khớp với Danh mục chi tiêu hoặc Ngân sách chung.
 * 4. Tự động ghi nhận số tiền chi tiêu (recordExpense) và cập nhật số dư ngân sách.
 * 5. Kích hoạt BudgetAlertRuleEngine (Strategy Pattern) để kiểm tra ngưỡng 80% / 100%.
 * 6. Tự động đẩy cảnh báo tức thì qua WebSocket tới màn hình chính của người dùng.
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
    @Transactional
    public void onTransactionCreated(TransactionCreatedEvent event) {
        Transaction tx = event.getTransaction();
        if (tx == null) {
            return;
        }

        // Chỉ xử lý các giao dịch chi tiêu đã được phê duyệt chính thức
        if (tx.getTransactionType() != TransactionType.EXPENSE || tx.getApprovalStatus() != ApprovalStatus.APPROVED) {
            return;
        }

        Long userId = tx.getUser().getId();
        String username = tx.getUser().getUsername();
        LocalDate txDate = tx.getTransactionDate().toLocalDate();

        log.debug("Observer Pattern: Nhận sự kiện chi tiêu ID [{}] số tiền {} VND của user [{}]",
                tx.getId(), tx.getAmount(), username);

        // 1. Tìm các ngân sách khớp với danh mục cụ thể của giao dịch
        List<Budget> matchingBudgets;
        if (tx.getCategory() != null) {
            matchingBudgets = budgetRepository.findActiveBudgetsByCategoryAndDate(userId, tx.getCategory().getId(), txDate);
        } else {
            matchingBudgets = budgetRepository.findActiveBudgetsByDate(userId, txDate);
        }

        if (matchingBudgets.isEmpty()) {
            log.debug("Không tìm thấy ngân sách nào đang áp dụng cho giao dịch ID [{}]", tx.getId());
            return;
        }

        for (Budget budget : matchingBudgets) {
            // Cập nhật số tiền đã chi
            budget.recordExpense(tx.getAmount());

            // Đánh giá quy tắc cảnh báo bằng Rule Engine (Strategy Pattern)
            Optional<BudgetAlertNotificationDto> alertOpt = alertRuleEngine.evaluateAndCreateAlert(budget);

            // Lưu ngân sách với số tiền chi tiêu mới và cờ cảnh báo đã cập nhật
            budgetRepository.save(budget);

            // Nếu chạm ngưỡng cảnh báo -> Bắn WebSocket tới người dùng
            alertOpt.ifPresent(alert -> {
                webSocketSender.sendAlert(username, alert);
                log.warn("Đã phát hiện và phát cảnh báo ngân sách [{}] cho người dùng [{}] qua WebSocket",
                        budget.getBudgetName(), username);
            });
        }
    }
}
