package com.loanpilot.orchestrator.decision;

import static com.loanpilot.orchestrator.decision.PolicyLimits.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.loanpilot.orchestrator.decision.ApplicantFacts.EmploymentType;
import com.loanpilot.orchestrator.decision.Decision.Outcome;
import com.loanpilot.orchestrator.decision.RuleCheck.Status;
import org.springframework.stereotype.Component;

/**
 * The credit decision. Pure, deterministic Java: same facts in, same decision out, no LLM anywhere (rule 1,
 * ADR-0001). Each rule implements one clause of credit-policy-v1 and reports PASS / FAIL / REFER / NOT_CHECKED.
 *
 * Every rule always runs (no early return), so the memo and the officer see ALL the reasons, not just the first.
 * Outcome: any FAIL -> REJECT; else any REFER -> REFER; else APPROVE.
 *
 * Limits are compared on exact values; rounding happens only for display. A FOIR of 50.04% is above 50%, even
 * though it displays as "50.0%".
 */
@Component
public class DecisionEngine {

    static final String RULES_VERSION = "rules-v1/" + POLICY_VERSION;
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public Decision decide(ApplicantFacts f) {
        if (f.netMonthlyIncome() == null || f.netMonthlyIncome().signum() <= 0) {
            throw new IllegalArgumentException("netMonthlyIncome must be positive: the workflow must not call the engine without it");
        }
        List<RuleCheck> checks = new ArrayList<>();
        boolean salaried = f.employmentType() == EmploymentType.SALARIED;
        boolean found = "FOUND".equals(f.bureauStatus());
        boolean noHistory = "NO_CREDIT_HISTORY".equals(f.bureauStatus());

        if (!found && !noHistory) {
            checks.add(new RuleCheck("BUREAU_NOT_FOUND", "4.5", Status.REFER, f.bureauStatus(), "a bureau report",
                    "The credit bureau has no record for this applicant, so creditworthiness can't be assessed"));
        }

        // §5.1 amount, §5.2 tenure
        checks.add(range("AMOUNT_OUT_OF_RANGE", "5.1", f.requestedAmount(), MIN_AMOUNT, MAX_AMOUNT, "loan amount"));
        boolean tenureOk = f.tenureMonths() >= MIN_TENURE_MONTHS && f.tenureMonths() <= MAX_TENURE_MONTHS;
        checks.add(new RuleCheck("TENURE_OUT_OF_RANGE", "5.2", tenureOk ? Status.PASS : Status.FAIL,
                f.tenureMonths() + " months", MIN_TENURE_MONTHS + "-" + MAX_TENURE_MONTHS + " months",
                tenureOk ? "Tenure is within the allowed range" : "Tenure is outside the allowed range"));

        // §3.3 minimum income
        BigDecimal minIncome = salaried ? MIN_INCOME_SALARIED : MIN_INCOME_SELF_EMPLOYED;
        boolean incomeOk = f.netMonthlyIncome().compareTo(minIncome) >= 0;
        checks.add(new RuleCheck("INCOME_BELOW_MINIMUM", "3.3", incomeOk ? Status.PASS : Status.FAIL,
                rupees(f.netMonthlyIncome()), ">= " + rupees(minIncome),
                incomeOk ? "Net monthly income meets the minimum" : "Net monthly income is below the minimum"));

        // §4.5 CIBIL score, or §4.4 when there is no credit history
        if (found) {
            Integer score = f.cibilScore();
            boolean scoreOk = score != null && score >= MIN_CIBIL;
            checks.add(new RuleCheck("CIBIL_BELOW_MINIMUM", "4.5", scoreOk ? Status.PASS : Status.FAIL,
                    String.valueOf(score), ">= " + MIN_CIBIL,
                    scoreOk ? "CIBIL score meets the minimum" : "CIBIL score is below the minimum"));
        } else if (noHistory) {
            boolean thinOk = f.netMonthlyIncome().compareTo(THIN_FILE_MIN_INCOME) >= 0
                    && f.requestedAmount().compareTo(THIN_FILE_MAX_AMOUNT) <= 0;
            checks.add(new RuleCheck("NO_CREDIT_HISTORY_NOT_ELIGIBLE", "4.4", thinOk ? Status.PASS : Status.FAIL,
                    "no score, income " + rupees(f.netMonthlyIncome()) + ", amount " + rupees(f.requestedAmount()),
                    "income >= " + rupees(THIN_FILE_MIN_INCOME) + " and amount <= " + rupees(THIN_FILE_MAX_AMOUNT),
                    thinOk ? "No credit history, but income and amount meet the no-history conditions"
                            : "No credit history, and income or amount does not meet the no-history conditions"));
        }

        // §4.8 accounts 90+ days past due
        boolean dpdOk = f.dpd90Count() <= MAX_DPD90_ACCOUNTS;
        checks.add(new RuleCheck("DPD90_IN_LAST_24M", "4.8", dpdOk ? Status.PASS : Status.FAIL,
                f.dpd90Count() + " account(s)", "0 accounts",
                dpdOk ? "No account 90+ days past due in the last 24 months"
                        : "Has an account 90+ days past due in the last 24 months"));

        // §6.1 / §6.2 rate. Preferential eligibility uses FOIR at the standard rate, so there is no circularity.
        BigDecimal emiAtStandard = EmiCalculator.monthlyEmi(f.requestedAmount(), STANDARD_RATE, f.tenureMonths());
        BigDecimal foirAtStandard = foir(f.existingEmi(), emiAtStandard, f.netMonthlyIncome());
        boolean preferential = found && f.cibilScore() != null && f.cibilScore() >= PREFERENTIAL_MIN_CIBIL
                && foirAtStandard.compareTo(PREFERENTIAL_MAX_FOIR) <= 0;
        BigDecimal rate = preferential ? PREFERENTIAL_RATE : STANDARD_RATE;
        BigDecimal emi = preferential ? EmiCalculator.monthlyEmi(f.requestedAmount(), rate, f.tenureMonths()) : emiAtStandard;
        BigDecimal foir = preferential ? foir(f.existingEmi(), emi, f.netMonthlyIncome()) : foirAtStandard;
        // Informational, never a reason to reject: which rate applies and why.
        checks.add(new RuleCheck("INTEREST_RATE", preferential ? "6.2" : "6.1", Status.PASS,
                rate.stripTrailingZeros().toPlainString() + "%",
                "CIBIL >= " + PREFERENTIAL_MIN_CIBIL + " and FOIR <= " + PREFERENTIAL_MAX_FOIR + "%",
                preferential ? "Qualifies for the preferential rate" : "Standard rate: preferential conditions not met"));

        // §4.2 / §4.3 FOIR
        BigDecimal maxFoir = salaried ? MAX_FOIR_SALARIED : MAX_FOIR_SELF_EMPLOYED;
        boolean foirOk = foir.compareTo(maxFoir) <= 0;
        checks.add(new RuleCheck("FOIR_EXCEEDED", salaried ? "4.2" : "4.3", foirOk ? Status.PASS : Status.FAIL,
                display(foir) + "%", "<= " + maxFoir + "%",
                "Existing EMIs " + rupees(f.existingEmi()) + " + new EMI " + rupees(emi) + " on income "
                        + rupees(f.netMonthlyIncome()) + (foirOk ? " is within the limit" : " exceeds the limit")));

        // §4.7 maximum loan amount (and the §4.4 cap when there is no credit history)
        BigDecimal multiple = salaried ? LOAN_MULTIPLE_SALARIED : LOAN_MULTIPLE_SELF_EMPLOYED;
        BigDecimal maxLoan = f.netMonthlyIncome().multiply(multiple).min(LOAN_CAP);
        if (noHistory) {
            maxLoan = maxLoan.min(THIN_FILE_MAX_AMOUNT);
        }
        maxLoan = maxLoan.setScale(0, RoundingMode.DOWN);
        boolean loanOk = f.requestedAmount().compareTo(maxLoan) <= 0;
        checks.add(new RuleCheck("LOAN_ABOVE_MAXIMUM", "4.7", loanOk ? Status.PASS : Status.FAIL,
                rupees(f.requestedAmount()), "<= " + rupees(maxLoan) + " (" + multiple + " x income, cap "
                        + rupees(LOAN_CAP) + (noHistory ? ", no-history cap " + rupees(THIN_FILE_MAX_AMOUNT) : "") + ")",
                loanOk ? "Requested amount is within the maximum" : "Requested amount is above the maximum"));

        // §4.6 credit hunger -> a human must review
        boolean enquiriesOk = f.enquiries6m() <= MAX_ENQUIRIES_6M;
        checks.add(new RuleCheck("CREDIT_HUNGER", "4.6", enquiriesOk ? Status.PASS : Status.REFER,
                f.enquiries6m() + " enquiries", "<= " + MAX_ENQUIRIES_6M + " in 6 months",
                enquiriesOk ? "Recent enquiries are within normal range"
                        : "Too many recent credit enquiries: refer to a credit officer for manual review"));

        // In the policy, but no data source yet. Listed so nobody mistakes "not checked" for "passed".
        checks.add(notChecked("AGE_NOT_VERIFIED", "3.1", "age at application and at loan end"));
        checks.add(notChecked("EMPLOYMENT_STABILITY_NOT_VERIFIED", "3.4", "work experience and time with employer"));
        checks.add(notChecked("BOUNCES_NOT_VERIFIED", "4.8", "bounces in the last 6 months (needs bank statements)"));
        checks.add(notChecked("SALARY_CREDITS_NOT_VERIFIED", "7.3", "salary slips vs bank statement credits"));

        List<String> reasons = checks.stream()
                .filter(c -> c.status() == Status.FAIL || c.status() == Status.REFER)
                .map(RuleCheck::code)
                .toList();
        Outcome outcome = checks.stream().anyMatch(c -> c.status() == Status.FAIL) ? Outcome.REJECT
                : checks.stream().anyMatch(c -> c.status() == Status.REFER) ? Outcome.REFER
                : Outcome.APPROVE;

        return new Decision(outcome, reasons, rate, emi, display(foir), maxLoan, List.copyOf(checks), RULES_VERSION);
    }

    /** FOIR in percent, exact (not rounded): used for comparisons. */
    static BigDecimal foir(BigDecimal existingEmi, BigDecimal newEmi, BigDecimal netMonthlyIncome) {
        return existingEmi.add(newEmi).multiply(HUNDRED).divide(netMonthlyIncome, MC);
    }

    private static RuleCheck range(String code, String clause, BigDecimal value, BigDecimal min, BigDecimal max, String what) {
        boolean ok = value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
        return new RuleCheck(code, clause, ok ? Status.PASS : Status.FAIL, rupees(value),
                rupees(min) + " - " + rupees(max), ok ? "The " + what + " is within the allowed range"
                : "The " + what + " is outside the allowed range");
    }

    private static RuleCheck notChecked(String code, String clause, String what) {
        return new RuleCheck(code, clause, Status.NOT_CHECKED, "no data", "see §" + clause,
                "Not checked: no data source yet for " + what + ". The credit officer must verify it");
    }

    private static BigDecimal display(BigDecimal percent) {
        return percent.setScale(1, RoundingMode.HALF_UP);
    }

    /** "Rs 85,000". Shared with the memo input so the memo quotes exactly the engine's formatting. */
    public static String rupees(BigDecimal amount) {
        return "Rs " + String.format("%,d", amount.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
}
