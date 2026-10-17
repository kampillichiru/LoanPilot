package com.loanpilot.bureau;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the mock bureau stores per person. Follows bureau_reports in docs/architecture.md. All values synthetic.
 *
 * This is the bureau's INTERNAL record. What a tool returns to MCP clients is a separate decision (see
 * CreditBureauTools): Claude Code is a cloud LLM, so anything a tool returns leaves the machine.
 *
 * @param applicantId     the bank's applicant id ("APP-001"), shared with loan-orchestrator's mock data
 * @param pan             synthetic PAN. PII: think hard before returning it to any client (rule 2)
 * @param cibilScore      300-900, or null when there is no credit history (thin file)
 * @param activeAccounts  open loans and credit cards
 * @param totalExistingEmi sum of EMIs on active loans, per month, in rupees
 * @param dpd90Count      accounts that were 90+ days past due in the last 24 months (a serious red flag)
 * @param enquiries6m     credit enquiries in the last 6 months (many = credit hungry)
 * @param reportDate      when the bureau last refreshed this record
 */
public record BureauRecord(
        String applicantId,
        String name,
        String pan,
        Integer cibilScore,
        int activeAccounts,
        BigDecimal totalExistingEmi,
        int dpd90Count,
        int enquiries6m,
        LocalDate reportDate) {
}
