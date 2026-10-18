package com.loanpilot.orchestrator.audit;

import java.time.Instant;
import java.util.Map;

import com.loanpilot.orchestrator.privacy.PiiType;

/**
 * One LLM call, as the audit log keeps it (CLAUDE.md rule 3: model, prompt version, input hash, output).
 *
 * The input itself is NOT stored, only its SHA-256 after masking: enough to prove which prompt produced an output
 * (hash the same input again and compare) without keeping a second copy of applicant documents.
 *
 * @param requestId  the application / request this call belongs to (null for calls outside one)
 * @param purpose    what the call was for: slip-extraction, credit-memo, policy-answer, advise, ask
 * @param piiMasked  how many values of each type the guard replaced before the call (counts, never values)
 * @param error      the exception type when the call failed, else null
 */
public record LlmCallRecord(
        String id,
        Instant at,
        String requestId,
        String purpose,
        String model,
        String promptVersion,
        String inputSha256,
        Map<PiiType, Integer> piiMasked,
        String output,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs,
        String error) {
}
