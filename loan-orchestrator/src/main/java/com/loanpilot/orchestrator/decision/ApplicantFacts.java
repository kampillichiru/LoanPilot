package com.loanpilot.orchestrator.decision;

import java.math.BigDecimal;

/**
 * Everything the engine decides on. Gathered by the workflow from verified sources (extracted salary slips,
 * the credit bureau), never typed in by the LLM.
 *
 * @param netMonthlyIncome  average net pay of the last 3 salary slips (§2.1)
 * @param bureauStatus      FOUND, NO_CREDIT_HISTORY or NOT_FOUND, as the bureau reports it
 * @param cibilScore        null when there is no credit history
 * @param existingEmi       total EMIs the applicant already pays per month (§2.2)
 */
public record ApplicantFacts(
        String applicantId,
        EmploymentType employmentType,
        BigDecimal requestedAmount,
        int tenureMonths,
        BigDecimal netMonthlyIncome,
        String bureauStatus,
        Integer cibilScore,
        BigDecimal existingEmi,
        int dpd90Count,
        int enquiries6m) {

    public enum EmploymentType { SALARIED, SELF_EMPLOYED }
}
