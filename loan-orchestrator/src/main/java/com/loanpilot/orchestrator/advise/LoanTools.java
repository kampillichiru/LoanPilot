package com.loanpilot.orchestrator.advise;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import static org.springframework.util.StringUtils.hasText;

/**
 * POC 3: the tools the model can call. Every @Tool method here is offered to the model by AdviseService.
 *
 * The model only sees: the tool name (method name), the @Tool description, and each parameter's name, type
 * and @ToolParam description. That text decides WHEN the model calls a tool and HOW it fills the arguments,
 * so it is written like a prompt.
 *
 * Design choices:
 * - Tools return facts, never verdicts (rule 1). No "affordable" / "approve" field anywhere: that decision
 *   belongs to the rules engine (POC 7). The model can only explain numbers Java produced.
 * - No PAN in or out (rule 2). getCibilScore takes a name or uses the applicantId from ToolContext; the PAN
 *   lookup stays in Java. The model never needs to see, generate or repeat a PAN.
 * - "No data" is a normal answer, not an exception: an explicit status the model can read and repeat,
 *   instead of a stack-trace message it might paraphrase into a guess.
 * - Bad arguments DO throw IllegalArgumentException with a plain message. Spring AI sends that message back
 *   to the model, which usually retries with corrected arguments.
 */
@Component
public class LoanTools {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal("1200");

    private final ApplicantDirectory applicants;

    public LoanTools(ApplicantDirectory applicants) {
        this.applicants = applicants;
    }

    /** EMI result. Amounts in rupees, rounded to the nearest rupee as banks show them. */
    public record EmiResult(BigDecimal monthlyEmi, BigDecimal totalInterest, BigDecimal totalPayable,
                            BigDecimal principal, BigDecimal annualRatePercent, int months) {
    }

    /**
     * @param status  FOUND, NO_CREDIT_HISTORY (score is null: no bureau record yet) or NOT_FOUND
     * @param note    a sentence the model can repeat as-is, so it doesn't have to interpret the status
     */
    public record CibilResult(String applicantId, Integer cibilScore, String status, String note) {
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

    @Tool(description = """
            Returns the applicant's current CIBIL credit score (300-900) from the credit bureau. Use it whenever \
            the question involves an applicant's creditworthiness, eligibility or ability to borrow. If the result \
            has no score, say so: never estimate or invent a credit score.""")
    public CibilResult getCibilScore(
            @ToolParam(required = false,
                    description = "Applicant's name as written in the question, e.g. Priya. Leave empty if no name is given")
            String applicantName,
            ToolContext toolContext) {
        // Code-supplied id wins over a name the model extracted: less room for the model to pick the wrong person.
        Optional<Applicant> applicant = contextApplicantId(toolContext)
                .flatMap(applicants::findById)
                .or(() -> hasText(applicantName) ? applicants.findByName(applicantName) : Optional.empty());

        if (applicant.isEmpty()) {
            return new CibilResult(null, null, "NOT_FOUND",
                    "No applicant found with that name. Do not guess a score; ask for the applicant's full name or id.");
        }
        Applicant a = applicant.get();
        if (a.cibilScore() == null) {
            return new CibilResult(a.id(), null, "NO_CREDIT_HISTORY",
                    "The bureau has no credit history for this applicant, so there is no CIBIL score.");
        }
        return new CibilResult(a.id(), a.cibilScore(), "FOUND", "CIBIL score from the credit bureau.");
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

    private static Optional<String> contextApplicantId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return Optional.empty();
        }
        return toolContext.getContext().get("applicantId") instanceof String id && hasText(id)
                ? Optional.of(id)
                : Optional.empty();
    }
}
