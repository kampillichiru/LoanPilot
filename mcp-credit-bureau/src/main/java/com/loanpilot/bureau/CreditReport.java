package com.loanpilot.bureau;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What getCreditReport returns to ANY MCP client. Deliberately smaller than BureauRecord:
 * - No PAN and no name (rule 2, data minimisation). The caller already knows who it asked about, and a cloud
 *   client (Claude Code) sends everything it receives to its provider.
 * - No verdict fields ("creditworthy", "risk band"): facts only, the bank's rules engine decides (rule 1).
 *
 * @param status  FOUND, NO_CREDIT_HISTORY (known applicant, no bureau history, score is null) or NOT_FOUND
 * @param note    one sentence a client's LLM can repeat as-is, so it doesn't have to interpret the status
 */
public record CreditReport(
        String applicantId,
        String status,
        Integer cibilScore,
        Integer activeAccounts,
        BigDecimal totalExistingEmi,
        Integer dpd90Count,
        Integer enquiries6m,
        LocalDate reportDate,
        String note) {

    static CreditReport of(BureauRecord r) {
        if (r.cibilScore() == null) {
            return new CreditReport(r.applicantId(), "NO_CREDIT_HISTORY", null, r.activeAccounts(),
                    r.totalExistingEmi(), r.dpd90Count(), r.enquiries6m(), r.reportDate(),
                    "The bureau has no credit history for this applicant, so there is no CIBIL score.");
        }
        return new CreditReport(r.applicantId(), "FOUND", r.cibilScore(), r.activeAccounts(),
                r.totalExistingEmi(), r.dpd90Count(), r.enquiries6m(), r.reportDate(),
                "Credit report from the bureau. dpd90Count = accounts 90+ days past due in the last 24 months.");
    }

    static CreditReport notFound() {
        return new CreditReport(null, "NOT_FOUND", null, null, null, null, null, null,
                "No applicant found. Do not guess a score; ask for the applicant id (APP-xxx) or full name.");
    }
}
