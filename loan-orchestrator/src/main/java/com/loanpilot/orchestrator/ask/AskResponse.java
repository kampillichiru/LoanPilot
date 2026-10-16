package com.loanpilot.orchestrator.ask;

/**
 * Returns the answer plus the metadata an AI architect cares about:
 * which model, which prompt version, how many tokens, how long it took.
 */
public record AskResponse(
        String answer,
        String model,
        String promptVersion,
        Double temperature,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs) {
}
