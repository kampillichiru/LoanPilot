package com.loanpilot.orchestrator.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.loanpilot.orchestrator.decision.ApplicantFacts;
import com.loanpilot.orchestrator.decision.Decision;
import com.loanpilot.orchestrator.privacy.PiiType;

/**
 * One application, start to finish: the engine's decision, the facts it used and where they came from, the memo,
 * and a trace of every step (rule 3).
 *
 * @param outcome    the decision engine's recommendation. The memo never changes it
 * @param decision   null when facts couldn't be established (e.g. a slip failed extraction): outcome is then REFER
 * @param facts      exactly what the engine decided on. Null when it didn't run
 * @param memoCheck  code's check of the memo against the decision
 */
public record ApplicationResult(
        String applicationId,
        String applicantId,
        Decision.Outcome outcome,
        List<String> reasonCodes,
        Decision decision,
        ApplicantFacts facts,
        Income income,
        BureauReport bureau,
        String memo,
        MemoCheck memoCheck,
        List<Step> steps,
        List<LlmCall> llmCalls,
        long totalMs) {

    /**
     * @param source  SALARY_SLIPS (extracted and validated) or DECLARED (fast mode: not verified)
     * @param slips   one entry per salary slip. Empty in fast mode
     */
    public record Income(String source, BigDecimal netMonthlyIncome, List<Slip> slips) {
    }

    /** One extracted slip. No name or PAN here: the hash ties it back to the input for audit. */
    public record Slip(String payPeriod, BigDecimal netPay, boolean valid, List<String> errors,
                       String promptVersion, String inputSha256, long latencyMs) {
    }

    /**
     * @param status    OK, OUTCOME_MISMATCH (memo recommends something other than the engine), UNSUPPORTED_CITATION,
     *                  MISSING_RECOMMENDATION, or NOT_GENERATED (code wrote the memo; no LLM call)
     * @param faithful  POC 8: does the memo state only what the decision says, and everything it must?
     *                  null when no LLM wrote the memo. Details in faithfulnessIssues (see MemoFaithfulness)
     */
    public record MemoCheck(String status, String memoRecommendation, List<String> citations,
                            List<String> unsupportedCitations, String model, String promptVersion,
                            Integer promptTokens, Integer completionTokens,
                            Boolean faithful, List<String> faithfulnessIssues) {
    }

    /**
     * One LLM call made for this application, from the audit log (POC 8). The full record, including the output,
     * is in the audit file and at GET /api/audit?requestId={applicationId}.
     *
     * @param piiMasked how many values of each type the guard masked before the call
     */
    public record LlmCall(String purpose, String model, String promptVersion, String inputSha256,
                          Map<PiiType, Integer> piiMasked, Integer promptTokens, Integer completionTokens,
                          long latencyMs, String auditId) {
    }

    /** @param status OK, FAILED or SKIPPED */
    public record Step(String name, String status, long ms, String detail) {
    }
}
