package com.loanpilot.orchestrator.policy;

import java.util.List;

/**
 * A grounded answer plus everything needed to check and audit it.
 *
 * @param citations             sections the answer cites, e.g. ["4.2"]
 * @param unsupportedCitations  cited sections that were NOT in the retrieved clauses: the model cited something
 *                              it was never shown, so treat the answer as unreliable
 * @param citationCheck         OK, NO_CITATION or UNSUPPORTED_CITATION (decided by code, not by the model)
 * @param retrieved             the clauses the model was given, with similarity scores (rule 3)
 * @param llmCalled             false when no clause was similar enough: code answered "not covered" itself
 */
public record PolicyAnswer(
        String answer,
        List<String> citations,
        List<String> unsupportedCitations,
        String citationCheck,
        List<RetrievedClause> retrieved,
        boolean llmCalled,
        String model,
        String promptVersion,
        String questionSha256,
        Integer promptTokens,
        Integer completionTokens,
        long retrievalMs,
        long llmMs) {
}
