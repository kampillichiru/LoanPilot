package com.loanpilot.orchestrator.decision;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The one EMI formula in the system. The decision engine (POC 7) and the calculateEmi tool (POC 3) both use it,
 * so the number the LLM quotes in chat and the number the rules decide on can never drift apart.
 *
 * EMI = P·r·(1+r)^n / ((1+r)^n − 1), with r = annual rate / 12 / 100, rounded to the nearest rupee.
 */
public final class EmiCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal("1200");

    private EmiCalculator() {
    }

    public static BigDecimal monthlyEmi(BigDecimal principal, BigDecimal annualRatePercent, int months) {
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("principal must be a positive amount in rupees, e.g. 500000");
        }
        if (annualRatePercent == null || annualRatePercent.signum() < 0 || annualRatePercent.compareTo(new BigDecimal("50")) > 0) {
            throw new IllegalArgumentException("annualRatePercent must be between 0 and 50, e.g. 12 for 12%");
        }
        if (months < 1 || months > 360) {
            throw new IllegalArgumentException("months must be between 1 and 360, e.g. 36 for 3 years");
        }

        BigDecimal exactEmi;
        if (annualRatePercent.signum() == 0) {
            exactEmi = principal.divide(BigDecimal.valueOf(months), MC);   // the formula divides by zero at 0%
        } else {
            BigDecimal r = annualRatePercent.divide(TWELVE_HUNDRED, MC);
            BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);
            exactEmi = principal.multiply(r, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC);
        }
        return exactEmi.setScale(0, RoundingMode.HALF_UP);
    }
}
