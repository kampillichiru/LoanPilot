package com.loanpilot.orchestrator.decision;

/**
 * The result of one rule, with the policy clause it implements. The memo explains these; it never adds to them.
 *
 * @param code    stable reason code, e.g. FOIR_EXCEEDED. Safe to store, count and report on
 * @param clause  policy section, e.g. "4.2"
 * @param actual  the applicant's value, e.g. "43.1%"
 * @param limit   the policy limit, e.g. "<= 50%"
 */
public record RuleCheck(
        String code,
        String clause,
        Status status,
        String actual,
        String limit,
        String reason) {

    /** NOT_CHECKED = the rule exists in the policy but we have no data for it yet. Never silently PASS. */
    public enum Status { PASS, FAIL, REFER, NOT_CHECKED }
}
