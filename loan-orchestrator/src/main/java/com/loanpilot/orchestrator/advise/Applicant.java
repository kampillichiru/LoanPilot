package com.loanpilot.orchestrator.advise;

import java.math.BigDecimal;

/**
 * A mock applicant record, standing in for the bank's customer DB and bureau data until POC 4.
 * All values are synthetic.
 *
 * @param id                 internal applicant id ("APP-001"). Safe to show the model: it means nothing outside the bank
 * @param pan                synthetic PAN. PII: keep it on the Java side (rule 2)
 * @param netMonthlyIncome   take-home pay per month, in rupees
 * @param existingEmi        total EMIs already being paid per month, in rupees
 * @param cibilScore         300-900, or null when the applicant has no credit history (thin file)
 */
public record Applicant(
        String id,
        String name,
        String pan,
        BigDecimal netMonthlyIncome,
        BigDecimal existingEmi,
        Integer cibilScore) {
}
