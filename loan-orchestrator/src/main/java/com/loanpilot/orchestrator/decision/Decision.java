package com.loanpilot.orchestrator.decision;

import java.math.BigDecimal;
import java.util.List;

/**
 * The engine's recommendation. A credit officer takes the final decision (§1.3).
 *
 * @param reasonCodes   codes of every FAIL and REFER check: why it isn't a plain APPROVE
 * @param interestRate  §6.1 standard or §6.2 preferential, percent per annum
 * @param emi           monthly EMI of the requested loan at interestRate
 * @param foirPercent   (existing EMIs + new EMI) / net monthly income, one decimal for display
 * @param maxLoanAmount the most this applicant could borrow under §4.7 (and §4.4 for no credit history)
 * @param rulesVersion  which rules and policy produced this, so it can be re-run and audited
 */
public record Decision(
        Outcome outcome,
        List<String> reasonCodes,
        BigDecimal interestRate,
        BigDecimal emi,
        BigDecimal foirPercent,
        BigDecimal maxLoanAmount,
        List<RuleCheck> checks,
        String rulesVersion) {

    /** APPROVE and REJECT are recommendations; REFER means a human must look before anything else happens. */
    public enum Outcome { APPROVE, REFER, REJECT }
}
