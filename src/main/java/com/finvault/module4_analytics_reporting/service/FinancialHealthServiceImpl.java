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
        log.info("Bắt đầu phân tích và đánh giá Sức khỏe tài chính chuyên sâu cho User ID: {}", userId);

        LocalDate today = LocalDate.now();
        LocalDate past90Days = today.minusDays(90);
        LocalDate past30Days = today.minusDays(30);

        // 1. Phân tích Dòng tiền: Lấy cả 90 ngày và 30 ngày gần nhất để phản ánh độ nhạy chi tiêu
        BigDecimal totalIncome90d = analyticsRepository.sumTotalIncome(userId, past90Days, today);
        BigDecimal totalExpense90d = analyticsRepository.sumTotalExpense(userId, past90Days, today);
        BigDecimal expense30d = analyticsRepository.sumTotalExpense(userId, past30Days, today);

        BigDecimal monthlyIncome = totalIncome90d.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        // Tốc độ đốt tiền (Burn rate) lấy giá trị lớn hơn giữa trung bình 3 tháng và 30 ngày gần nhất
        BigDecimal monthlyExpense = totalExpense90d.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP).max(expense30d);

        // 2. Kiểm tra Số dư Khả dụng (Liquid Cash / Assets) trong toàn bộ các ví
        List<Wallet> wallets = walletRepository.findAllByUserIdAndIsDeletedFalse(userId);
        BigDecimal totalLiquidAssets = wallets.stream()
                .map(w -> w.getBalance() != null ? w.getBalance() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<String> strengths = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // --- TRỤ CỘT 1: TỶ LỆ TIẾT KIỆM & DÒNG TIỀN (Max 30 điểm) ---
        BigDecimal savingsRate = BigDecimal.ZERO;
        int savingsScore = 0;

        if (totalIncome90d.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal netSavings = totalIncome90d.subtract(totalExpense90d);
            savingsRate = netSavings.multiply(BigDecimal.valueOf(100)).divide(totalIncome90d, 2, RoundingMode.HALF_UP);

            if (savingsRate.compareTo(new BigDecimal("30.00")) >= 0) {
                savingsScore = 30; // Tiết kiệm vượt trội (>= 30%)
                strengths.add("Tỷ lệ tiết kiệm xuất sắc (" + savingsRate.toPlainString() + "%), dòng tiền thặng dư rất mạnh.");
            } else if (savingsRate.compareTo(new BigDecimal("20.00")) >= 0) {
                savingsScore = 25; // Đạt chuẩn vàng 50/30/20 (20 - 30%)
                strengths.add("Duy trì dòng tiền dương ổn định chuẩn 50/30/20 (tiết kiệm " + savingsRate.toPlainString() + "%).");
            } else if (savingsRate.compareTo(new BigDecimal("10.00")) >= 0) {
                savingsScore = 18; // Khá (10 - 20%)
                recommendations.add("Tỷ lệ tiết kiệm đạt " + savingsRate.toPlainString() + "%. Cần tối ưu thêm các khoản chi sở thích (Wants) để nâng lên mốc 20%.");
            } else if (savingsRate.compareTo(BigDecimal.ZERO) >= 0) {
                savingsScore = 10; // Thu chi suýt soát (0 - 10%)
                recommendations.add("Cảnh báo thu chi suýt soát (chỉ còn dư " + savingsRate.toPlainString() + "%). Rất dễ thâm hụt nếu có phát sinh đột xuất.");
            } else if (savingsRate.compareTo(new BigDecimal("-20.00")) >= 0) {
                savingsScore = 4; // Bội chi nhẹ (-20% đến 0%)
                recommendations.add("⚠️ BỘI CHI DÒNG TIỀN: Bạn đang chi tiêu vượt thu nhập " + savingsRate.abs().toPlainString() + "%. Bạn đang phải tiêu lạm vào tiền tích lũy cũ!");
            } else {
                savingsScore = 0; // Bội chi nặng (< -20%)
                recommendations.add("🚨 BỘI CHI NẶNG: Chi tiêu vượt quá mức thu nhập tới " + savingsRate.abs().toPlainString() + "%. Dòng tiền đang bị âm nghiêm trọng!");
            }
        } else if (totalExpense90d.compareTo(BigDecimal.ZERO) > 0) {
            savingsScore = 0;
            savingsRate = new BigDecimal("-100.00");
            recommendations.add("🚨 Chưa ghi nhận nguồn thu nhập nào nhưng chi tiêu vẫn liên tục phát sinh (" + formatMoney(totalExpense90d) + ").");
        } else {
            savingsScore = 15; // Chưa có thu chi
        }

        // --- TRỤ CỘT 2: KỶ LUẬT NGÂN SÁCH (Max 25 điểm) ---
        List<Budget> activeBudgets = budgetRepository.findActiveBudgetsByDate(userId, today);
        int budgetScore = 20;
        BigDecimal budgetAdherence = new BigDecimal("100.00");

        if (!activeBudgets.isEmpty()) {
            long compliantCount = activeBudgets.stream().filter(b -> !b.isOverBudget()).count();
            budgetAdherence = BigDecimal.valueOf(compliantCount * 100L)
                    .divide(BigDecimal.valueOf(activeBudgets.size()), 2, RoundingMode.HALF_UP);

            List<String> brokenBudgets = activeBudgets.stream()
                    .filter(Budget::isOverBudget)
                    .map(b -> b.getBudgetName() + " (" + b.getUsagePercentage().toPlainString() + "%)")
                    .toList();

            if (budgetAdherence.compareTo(new BigDecimal("100.00")) >= 0) {
                budgetScore = 25;
                strengths.add("Tuân thủ 100% trần ngân sách đã đề ra, kiểm soát chi tiêu rất chặt chẽ.");
            } else if (budgetAdherence.compareTo(new BigDecimal("75.00")) >= 0) {
                budgetScore = 18;
                recommendations.add("Chú ý danh mục ngân sách đã vượt hạn mức: " + String.join(", ", brokenBudgets) + ".");
            } else if (budgetAdherence.compareTo(new BigDecimal("50.00")) >= 0) {
                budgetScore = 10;
                recommendations.add("Cảnh báo: Hơn 25% danh mục ngân sách đã bị vỡ trần chi tiêu (" + String.join(", ", brokenBudgets) + "). Cần phong tỏa chi tiêu các nhóm này.");
            } else {
                budgetScore = 3;
                recommendations.add("🚨 VỠ KẾ HOẠCH TÀI CHÍNH: Đa số các ngân sách đã bị bội chi (" + String.join(", ", brokenBudgets) + ").");
            }
        }

        // --- TRỤ CỘT 3: QUỸ DỰ PHÒNG KHẨN CẤP & THANH KHOẢN (Max 25 điểm) ---
        BigDecimal emergencyMonths = BigDecimal.ZERO;
        int emergencyScore = 0;

        if (totalLiquidAssets.compareTo(BigDecimal.ZERO) <= 0) {
            // Hết nhẵn tiền mặt hoặc âm tài khoản
            emergencyScore = 0;
            emergencyMonths = BigDecimal.ZERO;
            recommendations.add("🚨 CẠN KIỆT THANH KHOẢN: Tổng số dư ví khả dụng hiện tại là " + formatMoney(totalLiquidAssets) + ". Bạn đã tiêu hết sạch tiền mặt hoặc đang gánh nợ tín dụng!");
        } else if (monthlyExpense.compareTo(BigDecimal.ZERO) > 0) {
            emergencyMonths = totalLiquidAssets.divide(monthlyExpense, 2, RoundingMode.HALF_UP);

            if (emergencyMonths.compareTo(new BigDecimal("6.00")) >= 0) {
                emergencyScore = 25; // An toàn tuyệt đối (>= 6 tháng)
                strengths.add("Quỹ dự phòng khẩn cấp rất vững chắc (" + emergencyMonths.toPlainString() + " tháng chi tiêu).");
            } else if (emergencyMonths.compareTo(new BigDecimal("3.00")) >= 0) {
                emergencyScore = 20; // Tốt (3 - 6 tháng)
                strengths.add("Quỹ dự phòng an toàn (đủ trang trải " + emergencyMonths.toPlainString() + " tháng sinh hoạt).");
            } else if (emergencyMonths.compareTo(new BigDecimal("1.00")) >= 0) {
                emergencyScore = 12; // Tạm chấp nhận (1 - 3 tháng)
                recommendations.add("Quỹ dự phòng còn mỏng (" + emergencyMonths.toPlainString() + " tháng chi tiêu). Hãy ưu tiên tích lũy lên mốc tối thiểu 3 tháng.");
            } else {
                emergencyScore = 4; // Rất mỏng (< 1 tháng)
                recommendations.add("Quỹ khẩn cấp mỏng manh (chưa đủ sống 1 tháng nếu mất thu nhập). Cần gom tiền lập quỹ khẩn cấp ngay.");
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
                debtScore = 20;
            } else if (dtiRatio.compareTo(new BigDecimal("35.00")) <= 0) {
                debtScore = 15;
                recommendations.add("Tỷ lệ trả nợ DTI ở mức " + dtiRatio.toPlainString() + "% (ngưỡng an toàn là dưới 35%).");
            } else if (dtiRatio.compareTo(new BigDecimal("50.00")) <= 0) {
                debtScore = 8;
                recommendations.add("Cảnh báo: Áp lực trả nợ đang chiếm " + dtiRatio.toPlainString() + "% thu nhập hàng tháng.");
            } else {
                debtScore = 0;
                recommendations.add("🚨 GÁNH NẶNG NỢ QUÁ TẢI: Tiền trả nợ chiếm " + dtiRatio.toPlainString() + "% thu nhập! Cần tái cơ cấu nợ gấp.");
            }
        }

        // --- TỔNG KẾT ĐIỂM SỐ & XỬ LÝ PHẠT KIỆT QUỆ THANH KHOẢN ---
        int rawScore = savingsScore + budgetScore + emergencyScore + debtScore;

        // Điểm phạt nếu tiêu quá trớn đến hết tiền hoặc bội chi sâu:
        if (totalLiquidAssets.compareTo(BigDecimal.ZERO) <= 0) {
            rawScore -= 25; // Phạt nặng nếu hết nhẵn tiền mặt
            if (rawScore > 35) rawScore = 35; // Không thể vượt quá ngưỡng POOR/CRITICAL khi hết tiền
        }
        if (savingsRate.compareTo(new BigDecimal("-20.00")) < 0) {
            rawScore -= 15; // Phạt bội chi nặng
        }

        int totalScore = Math.max(0, Math.min(100, rawScore));

        // --- PHÂN LOẠI 5 MỐC ĐỘNG VÀ ĐÁNH GIÁ CHUYÊN BIỆT ---
        String grade;
        String assessment;

        if (totalScore >= 85) {
            grade = "EXCELLENT";
            assessment = "Tình trạng tài chính xuất sắc! Dòng tiền thặng dư dồi dào, kỷ luật ngân sách vững vàng và quỹ dự phòng rất an toàn.";
            if (recommendations.isEmpty()) {
                recommendations.add("Có thể cân nhắc phân bổ một phần tiền nhàn rỗi sang các kênh đầu tư sinh lời dài hạn.");
            }
        } else if (totalScore >= 70) {
            grade = "GOOD";
            assessment = "Tình trạng tài chính tốt. Bạn đang quản lý tốt thu chi, duy trì dòng tiền dương ổn định.";
            if (recommendations.isEmpty()) {
                recommendations.add("Tiếp tục duy trì kỷ luật tích lũy để sớm nâng hạng lên mức Xuất sắc.");
            }
        } else if (totalScore >= 50) {
            grade = "FAIR";
            assessment = "Tình trạng tài chính ở mức trung bình. Thu chi suýt soát hoặc quỹ dự phòng còn mỏng, cần thắt chặt các khoản chi tùy hứng.";
        } else if (totalScore >= 30) {
            grade = "POOR";
            assessment = "Cảnh báo: Sức khỏe tài chính suy yếu! Bạn đang có dấu hiệu bội chi hoặc số dư ví sụt giảm nguy hiểm.";
            recommendations.add("Cắt giảm ngay lập tức các chi phí mong muốn (ăn ngoài, mua sắm xa xỉ) để bảo toàn dòng tiền.");
        } else {
            grade = "CRITICAL";
            assessment = "🚨 BÁO ĐỘNG ĐỎ: Tình trạng tài chính rất nguy kịch! Bạn đã tiêu quá trớn đến cạn sạch tiền mặt, bội chi nặng hoặc vỡ ngân sách nghiêm trọng!";
            recommendations.add("NGỪNG TOÀN BỘ CÁC KHOẢN CHI TIÊU KHÔNG THIẾT YẾU ngay hôm nay!");
            recommendations.add("Tìm kiếm nguồn thu bổ sung hoặc thanh lý bớt tài sản không sinh lời để phục hồi thanh khoản.");
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

    private String formatMoney(BigDecimal amount) {
        if (amount == null) return "0 VND";
        return new java.text.DecimalFormat("#,##0 VND").format(amount);
    }
}
