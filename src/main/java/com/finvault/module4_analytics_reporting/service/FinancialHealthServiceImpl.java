package com.finvault.module4_analytics_reporting.service;

import com.finvault.common.BusinessException;
import com.finvault.module1_security_rbac.entity.User;
import com.finvault.module1_security_rbac.repository.UserRepository;
import com.finvault.module2_wallet_ledger.entity.Wallet;
import com.finvault.module2_wallet_ledger.repository.WalletRepository;
import com.finvault.module3_budget_goals.entity.Budget;
import com.finvault.module3_budget_goals.entity.LoanCalculation;
import com.finvault.module3_budget_goals.repository.BudgetRepository;
import com.finvault.module3_budget_goals.repository.LoanCalculationRepository;
import com.finvault.module3_budget_goals.repository.SavingGoalRepository;
import com.finvault.module4_analytics_reporting.dto.FinancialHealthScoreDto;
import com.finvault.module4_analytics_reporting.repository.AnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ==============================================================================
 * [Owner: Member 4 - Analytics, BI & Reporting Module]
 * FinancialHealthServiceImpl: Thuật toán định lượng Sức khỏe Tài chính cá nhân & gia đình
 *
 * 4 Trụ cột đánh giá theo chuẩn Cố vấn Tài chính Quốc tế (CFP):
 * 1. Savings Rate (Tỷ lệ tiết kiệm): Thu nhập tích lũy hàng tháng.
 * 2. Budget Discipline (Kỷ luật ngân sách): Mức độ tuân thủ trần chi tiêu.
 * 3. Emergency Reserve (Quỹ khẩn cấp): Số tháng chi tiêu có thể duy trì khi mất thu nhập.
 * 4. Debt Burden (Gánh nặng nợ DTI): Tỷ lệ nợ phải trả trên thu nhập hàng tháng.
 * ==============================================================================
 */
@Service
@Transactional(readOnly = true)
public class FinancialHealthServiceImpl implements FinancialHealthService {

    private static final Logger log = LoggerFactory.getLogger(FinancialHealthServiceImpl.class);

    private final UserRepository userRepository;
    private final AnalyticsRepository analyticsRepository;
    private final BudgetRepository budgetRepository;
    private final SavingGoalRepository savingGoalRepository;
    private final WalletRepository walletRepository;
    private final LoanCalculationRepository loanCalculationRepository;

    public FinancialHealthServiceImpl(UserRepository userRepository,
                                      AnalyticsRepository analyticsRepository,
                                      BudgetRepository budgetRepository,
                                      SavingGoalRepository savingGoalRepository,
                                      WalletRepository walletRepository,
                                      LoanCalculationRepository loanCalculationRepository) {
        this.userRepository = userRepository;
        this.analyticsRepository = analyticsRepository;
        this.budgetRepository = budgetRepository;
        this.savingGoalRepository = savingGoalRepository;
        this.walletRepository = walletRepository;
        this.loanCalculationRepository = loanCalculationRepository;
    }

    @Override
    public FinancialHealthScoreDto calculateFinancialHealth(String username) {
        User user = userRepository.findByUsernameAndIsDeletedFalse(username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Không tìm thấy người dùng: " + username));
        return calculateFinancialHealthByUserId(user.getId());
    }

    @Override
    public FinancialHealthScoreDto calculateFinancialHealthByUserId(Long userId) {
        log.info("Bắt đầu phân tích và đánh giá Sức khỏe tài chính cho User ID: {}", userId);

        LocalDate today = LocalDate.now();
        LocalDate past90Days = today.minusDays(90);

        // 1. Phân tích Dòng tiền 90 ngày gần nhất (Trung bình 3 tháng)
        BigDecimal totalIncome90d = analyticsRepository.sumTotalIncome(userId, past90Days, today);
        BigDecimal totalExpense90d = analyticsRepository.sumTotalExpense(userId, past90Days, today);

        BigDecimal monthlyIncome = totalIncome90d.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        BigDecimal monthlyExpense = totalExpense90d.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);

        // --- TRỤ CỘT 1: TỶ LỆ TIẾT KIỆM (Max 30 điểm) ---
        BigDecimal savingsRate = BigDecimal.ZERO;
        int savingsScore = 0;
        if (totalIncome90d.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal netSavings = totalIncome90d.subtract(totalExpense90d);
            savingsRate = netSavings.multiply(BigDecimal.valueOf(100)).divide(totalIncome90d, 2, RoundingMode.HALF_UP);

            if (savingsRate.compareTo(new BigDecimal("20.00")) >= 0) {
                savingsScore = 30; // Rất tốt, chuẩn 50/30/20
            } else if (savingsRate.compareTo(new BigDecimal("10.00")) >= 0) {
                savingsScore = 20; // Khá tốt
            } else if (savingsRate.compareTo(BigDecimal.ZERO) > 0) {
                savingsScore = 10; // Có tiết kiệm nhưng còn thấp
            } else {
                savingsScore = 0; // Chi tiêu vượt thu nhập (âm tiền)
            }
        } else {
            savingsScore = 10; // Chưa có thu nhập ghi nhận, mức trung tính
        }

        // --- TRỤ CỘT 2: KỶ LUẬT NGÂN SÁCH (Max 25 điểm) ---
        List<Budget> activeBudgets = budgetRepository.findActiveBudgetsByDate(userId, today);
        int budgetScore = 20;
        BigDecimal budgetAdherence = new BigDecimal("100.00");

        if (!activeBudgets.isEmpty()) {
            long compliantCount = activeBudgets.stream().filter(b -> !b.isOverBudget()).count();
            budgetAdherence = BigDecimal.valueOf(compliantCount * 100L)
                    .divide(BigDecimal.valueOf(activeBudgets.size()), 2, RoundingMode.HALF_UP);

            if (budgetAdherence.compareTo(new BigDecimal("100.00")) >= 0) {
                budgetScore = 25;
            } else if (budgetAdherence.compareTo(new BigDecimal("75.00")) >= 0) {
                budgetScore = 18;
            } else if (budgetAdherence.compareTo(new BigDecimal("50.00")) >= 0) {
                budgetScore = 10;
            } else {
                budgetScore = 5;
            }
        }

        // --- TRỤ CỘT 3: QUỸ DỰ PHÒNG KHẨN CẤP (Max 25 điểm) ---
        List<Wallet> wallets = walletRepository.findAllByUserIdAndIsDeletedFalse(userId);
        BigDecimal totalLiquidAssets = wallets.stream()
                .map(Wallet::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSavings = savingGoalRepository.sumTotalCurrentAmountByUserId(userId);
        BigDecimal totalReserves = totalLiquidAssets.add(totalSavings);

        BigDecimal emergencyMonths = BigDecimal.ZERO;
        int emergencyScore = 0;
        if (monthlyExpense.compareTo(BigDecimal.ZERO) > 0) {
            emergencyMonths = totalReserves.divide(monthlyExpense, 2, RoundingMode.HALF_UP);

            if (emergencyMonths.compareTo(new BigDecimal("6.00")) >= 0) {
                emergencyScore = 25; // An toàn tuyệt đối (>= 6 tháng)
            } else if (emergencyMonths.compareTo(new BigDecimal("3.00")) >= 0) {
                emergencyScore = 20; // Tốt (3 - 6 tháng)
            } else if (emergencyMonths.compareTo(new BigDecimal("1.00")) >= 0) {
                emergencyScore = 12; // Tạm chấp nhận (1 - 3 tháng)
            } else {
                emergencyScore = 4; // Rất mỏng (< 1 tháng)
            }
        } else {
            emergencyScore = 15;
            emergencyMonths = new BigDecimal("3.00");
        }

        // --- TRỤ CỘT 4: GÁNH NẶNG NỢ DTI (Max 20 điểm) ---
        List<LoanCalculation> loans = loanCalculationRepository.findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(userId);
        BigDecimal monthlyDebtPayment = loans.stream()
                .map(LoanCalculation::getMonthlyPayment)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal dtiRatio = BigDecimal.ZERO;
        int debtScore = 20;
        if (monthlyIncome.compareTo(BigDecimal.ZERO) > 0) {
            dtiRatio = monthlyDebtPayment.multiply(BigDecimal.valueOf(100)).divide(monthlyIncome, 2, RoundingMode.HALF_UP);

            if (dtiRatio.compareTo(new BigDecimal("20.00")) <= 0) {
                debtScore = 20; // An toàn cao (< 20%)
            } else if (dtiRatio.compareTo(new BigDecimal("35.00")) <= 0) {
                debtScore = 15; // Mức chấp nhận được (20 - 35%)
            } else if (dtiRatio.compareTo(new BigDecimal("50.00")) <= 0) {
                debtScore = 8;  // Nguy hiểm (35 - 50%)
            } else {
                debtScore = 0;  // Quá tải nợ (> 50%)
            }
        }

        // Tổng kết điểm số
        int totalScore = savingsScore + budgetScore + emergencyScore + debtScore;
        if (totalScore > 100) totalScore = 100;

        String grade;
        String assessment;
        List<String> strengths = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        if (totalScore >= 85) {
            grade = "EXCELLENT";
            assessment = "Tình trạng tài chính xuất sắc! Dòng tiền ổn định, tích lũy vượt trội và khả năng phòng thủ rủi ro rất cao.";
            strengths.add("Tỷ lệ tiết kiệm và duy trì ngân sách ở mức rất tốt.");
            strengths.add("Quỹ dự phòng đảm bảo an tâm trước mọi biến cố tài chính.");
            recommendations.add("Có thể cân nhắc phân bổ một phần tiền nhàn rỗi sang các kênh đầu tư sinh lời dài hạn.");
        } else if (totalScore >= 70) {
            grade = "GOOD";
            assessment = "Tình trạng tài chính tốt. Bạn đang quản lý tốt thu chi nhưng cần tối ưu hóa một số danh mục chi tiêu.";
            strengths.add("Duy trì dòng tiền dương đều đặn.");
            recommendations.add("Tăng cường tích lũy để đạt mốc 6 tháng quỹ khẩn cấp.");
            recommendations.add("Kiểm soát chặt chẽ các khoản chi ngoài kế hoạch.");
        } else if (totalScore >= 50) {
            grade = "FAIR";
            assessment = "Tình trạng tài chính ở mức trung bình. Cần thắt chặt chi tiêu và xây dựng quỹ dự phòng sớm.";
            recommendations.add("Áp dụng nghiêm ngặt quy tắc ngân sách 50/30/20.");
            recommendations.add("Hạn chế vay nợ mới và ưu tiên thanh toán các khoản nợ có lãi suất cao.");
        } else {
            grade = "POOR";
            assessment = "Cảnh báo rủi ro tài chính cao! Chi tiêu đang sát hoặc vượt thu nhập, quỹ dự phòng mỏng manh.";
            recommendations.add("Cắt giảm ngay lập tức các chi phí mong muốn (Wants - cà phê, ăn ngoài, mua sắm).");
            recommendations.add("Tìm cách gia tăng nguồn thu nhập phụ để tái cân bằng ngân sách.");
        }

        FinancialHealthScoreDto dto = new FinancialHealthScoreDto();
        dto.setTotalScore(totalScore);
        dto.setHealthGrade(grade);
        dto.setSummaryAssessment(assessment);
        dto.setSavingsRate(savingsRate);
        dto.setSavingsRateScore(savingsScore);
        dto.setBudgetAdherenceRate(budgetAdherence);
        dto.setBudgetAdherenceScore(budgetScore);
        dto.setEmergencyFundMonths(emergencyMonths);
        dto.setEmergencyFundScore(emergencyScore);
        dto.setDebtToIncomeRatio(dtiRatio);
        dto.setDebtToIncomeScore(debtScore);
        dto.setStrengthPoints(strengths);
        dto.setImprovementRecommendations(recommendations);

        log.info("Hoàn tất đánh giá sức khỏe tài chính User ID [{}]: Tổng điểm {}/100, Xếp loại [{}]",
                userId, totalScore, grade);
        return dto;
    }
}
