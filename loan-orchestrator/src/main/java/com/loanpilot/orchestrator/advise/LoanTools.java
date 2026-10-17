package com.loanpilot.orchestrator.advise;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Local tools: pure calculations that belong inside the orchestrator. AdviseService offers every @Tool method
 * here to the model, together with the tools discovered over MCP.
 *
 * POC 5: getCibilScore moved out. Credit data now comes from the mcp-credit-bureau server (getCreditReport),
 * which owns that data. Rule of thumb: maths stays in-process (no network hop, no shared state to own);
 * data owned by another domain comes over MCP.
 *
 * Design choices:
 * - Tools return facts, never verdicts (rule 1). No "affordable" / "approve" field.
 * - Bad arguments throw IllegalArgumentException with a plain message. Spring AI sends that message back to
 *   the model, which usually retries with corrected arguments.
 */
@Component
public class LoanTools {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal("1200");

    /** EMI result. Amounts in rupees, rounded to the nearest rupee as banks show them. */
    public record EmiResult(BigDecimal monthlyEmi, BigDecimal totalInterest, BigDecimal totalPayable,
                            BigDecimal principal, BigDecimal annualRatePercent, int months) {
    }

    @Tool(description = """
            Calculates the exact monthly EMI (equated monthly instalment) for a loan, plus total interest and \
            total amount payable. Always use this for any EMI, instalment or repayment amount: never calculate \
            EMIs yourself. Convert amounts first: 1 lakh = 100000, 1 crore = 10000000; years x 12 = months.""")
    public EmiResult calculateEmi(
            @ToolParam(description = "Loan amount in rupees as a plain number, e.g. 500000 for 5 lakh")
            BigDecimal principal,
            @ToolParam(description = "Annual interest rate in percent, e.g. 12 for 12% per year")
            BigDecimal annualRatePercent,
            @ToolParam(description = "Loan tenure in months, e.g. 36 for 3 years")
            int months) {
        return emi(principal, annualRatePercent, months);
    }

    /**
     * EMI = P·r·(1+r)^n / ((1+r)^n − 1), with r = annual rate / 12 / 100. Static and pure so it can be tested
     * without Spring, and reused by the decision engine in POC 7.
     */
    static EmiResult emi(BigDecimal principal, BigDecimal annualRatePercent, int months) {
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

        BigDecimal monthlyEmi = exactEmi.setScale(0, RoundingMode.HALF_UP);
        BigDecimal totalPayable = monthlyEmi.multiply(BigDecimal.valueOf(months));
        return new EmiResult(monthlyEmi, totalPayable.subtract(principal).setScale(0, RoundingMode.HALF_UP),
                totalPayable, principal, annualRatePercent, months);
    }
}
