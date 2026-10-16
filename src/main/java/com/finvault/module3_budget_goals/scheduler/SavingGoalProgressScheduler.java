package com.finvault.module3_budget_goals.scheduler;

import com.finvault.module3_budget_goals.entity.SavingGoal;
import com.finvault.module3_budget_goals.repository.SavingGoalRepository;
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
 * SavingGoalProgressScheduler: Tác vụ định kỳ kiểm tra tiến độ các Quỹ mục tiêu tiết kiệm
 *
 * Lịch trình thực thi:
 * - Chạy định kỳ vào 08:00 sáng mỗi ngày (Cron: 0 0 8 * * ?)
 * - Rà soát các mục tiêu tiết kiệm sắp đến hạn trong vòng 30 ngày để ghi nhận và cảnh báo.
 * ==============================================================================
 */
@Component
public class SavingGoalProgressScheduler {

    private static final Logger log = LoggerFactory.getLogger(SavingGoalProgressScheduler.class);

    private final SavingGoalRepository savingGoalRepository;

    public SavingGoalProgressScheduler(SavingGoalRepository savingGoalRepository) {
        this.savingGoalRepository = savingGoalRepository;
    }

    /**
     * Quét các mục tiêu tiết kiệm sắp đến hạn trong vòng 30 ngày
     */
    @Scheduled(cron = "${finvault.goals.check-cron:0 0 8 * * ?}")
    @Transactional(readOnly = true)
    public void checkUpcomingSavingGoals() {
        LocalDate today = LocalDate.now();
        LocalDate upcoming30Days = today.plusDays(30);

        log.info("Bắt đầu quét các Mục tiêu tiết kiệm sắp đến hạn (Từ {} đến {})", today, upcoming30Days);

        List<SavingGoal> upcomingGoals = savingGoalRepository.findUpcomingDueGoals(today, upcoming30Days);
        if (upcomingGoals.isEmpty()) {
            log.info("Không có mục tiêu tiết kiệm nào sắp đến hạn trong 30 ngày tới.");
            return;
        }

        for (SavingGoal goal : upcomingGoals) {
            log.info("Mục tiêu tiết kiệm [{}] của user [{}] sắp đến hạn vào ngày {}. Tiến độ hiện tại: {}% ({} / {})",
                    goal.getGoalName(),
                    goal.getUser().getUsername(),
                    goal.getTargetDate(),
                    goal.getProgressPercentage(),
                    goal.getCurrentAmount(),
                    goal.getTargetAmount());
        }
    }
}
