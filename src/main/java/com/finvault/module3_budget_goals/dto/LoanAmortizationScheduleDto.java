package com.finvault.module3_budget_goals.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ==============================================================================
 * [Owner: Member 3 - Budget & Financial Goals Module]
 * LoanAmortizationScheduleDto: DTO chi tiết từng kỳ trả nợ trong bảng lịch trình trả góp
 * ==============================================================================
 */
public class LoanAmortizationScheduleDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer periodNumber;
    private LocalDate paymentDate;
    private BigDecimal principalPayment;
    private BigDecimal interestPayment;
    private BigDecimal totalPayment;
    private BigDecimal remainingPrincipal;

    public LoanAmortizationScheduleDto() {
    }

    public LoanAmortizationScheduleDto(Integer periodNumber, LocalDate paymentDate,
                                       BigDecimal principalPayment, BigDecimal interestPayment,
                                       BigDecimal totalPayment, BigDecimal remainingPrincipal) {
        this.periodNumber = periodNumber;
        this.paymentDate = paymentDate;
        this.principalPayment = principalPayment;
        this.interestPayment = interestPayment;
        this.totalPayment = totalPayment;
        this.remainingPrincipal = remainingPrincipal;
    }

    public Integer getPeriodNumber() {
        return periodNumber;
    }

    public void setPeriodNumber(Integer periodNumber) {
        this.periodNumber = periodNumber;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDate = paymentDate;
    }

    public BigDecimal getPrincipalPayment() {
        return principalPayment;
    }

    public void setPrincipalPayment(BigDecimal principalPayment) {
        this.principalPayment = principalPayment;
    }

    public BigDecimal getInterestPayment() {
        return interestPayment;
    }

    public void setInterestPayment(BigDecimal interestPayment) {
        this.interestPayment = interestPayment;
    }

    public BigDecimal getTotalPayment() {
        return totalPayment;
    }

    public void setTotalPayment(BigDecimal totalPayment) {
        this.totalPayment = totalPayment;
    }

    public BigDecimal getRemainingPrincipal() {
        return remainingPrincipal;
    }

    public void setRemainingPrincipal(BigDecimal remainingPrincipal) {
        this.remainingPrincipal = remainingPrincipal;
    }
}
