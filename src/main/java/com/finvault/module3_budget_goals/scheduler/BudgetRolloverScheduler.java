package com.finvault.module3_budget_goals.scheduler;

import com.finvault.module3_budget_goals.entity.Budget;
import com.finvault.module3_budget_goals.repository.BudgetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * ==============================================================================
 * [Owner: Member 3 - Budget & Financial Goals Module]
 * BudgetRolloverScheduler: Tác vụ tự động kiểm tra và gia hạn chu kỳ ngân sách định kỳ
 *
 * Lịch trình thực thi:
 * - Chạy tự động vào 00:00:00 ngày đầu tiên của mỗi tháng (Cron: 0 0 0 1 * ?)
 * - Quét các ngân sách đã hết hạn chu kỳ, tổng kết số dư và tự động vô hiệu hóa chu kỳ cũ.
 * ==============================================================================
 */
@Component
public class BudgetRolloverScheduler {

    private static final Logger log = LoggerFactory.getLogger(BudgetRolloverScheduler.class);

    private final BudgetRepository budgetRepository;

    public BudgetRolloverScheduler(BudgetRepository budgetRepository) {
        this.budgetRepository = budgetRepository;
    }

    /**
     * Tự động quét và xử lý các ngân sách hết hạn vào đầu tháng
     */
    @Scheduled(cron = "${finvault.budget.rollover-cron:0 0 0 1 * ?}")
    @Transactional
    public void processMonthlyBudgetRollover() {
        LocalDate today = LocalDate.now();
        log.info("Bắt đầu tác vụ kiểm tra gia hạn chu kỳ ngân sách hàng tháng (Ngày: {})", today);

        List<Budget> expiredBudgets = budgetRepository.findExpiredActiveBudgets(today);
        if (expiredBudgets.isEmpty()) {
            log.info("Không có ngân sách nào hết hạn cần đóng chu kỳ.");
            return;
        }

        int count = 0;
        for (Budget budget : expiredBudgets) {
            budget.setIsActive(false);
            budgetRepository.save(budget);
            count++;
            log.info("Đã đóng chu kỳ cũ của ngân sách [{}]: Đã chi {} / Phân bổ {}",
                    budget.getBudgetName(), budget.getSpentAmount(), budget.getAllocatedAmount());
        }

        log.info("Hoàn tất tác vụ Rollover. Đã đóng chu kỳ thành công {} ngân sách.", count);
    }
}
