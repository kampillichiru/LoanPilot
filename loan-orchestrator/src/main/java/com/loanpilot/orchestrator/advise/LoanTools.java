package com.loanpilot.orchestrator.advise;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.loanpilot.orchestrator.decision.EmiCalculator;
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

    /** Delegates to the shared EmiCalculator (same formula the decision engine uses), then adds the totals. */
    static EmiResult emi(BigDecimal principal, BigDecimal annualRatePercent, int months) {
        BigDecimal monthlyEmi = EmiCalculator.monthlyEmi(principal, annualRatePercent, months);
        BigDecimal totalPayable = monthlyEmi.multiply(BigDecimal.valueOf(months));
        return new EmiResult(monthlyEmi, totalPayable.subtract(principal).setScale(0, RoundingMode.HALF_UP),
                totalPayable, principal, annualRatePercent, months);
    }
}
