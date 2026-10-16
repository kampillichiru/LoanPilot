package com.loanpilot.orchestrator.ask;

/**
 * Returns the answer plus the metadata an AI architect cares about:
 * which model, which prompt version, which sampling options, how many tokens, how long it took.
 * seed / topK are null when not set (Ollama then picks a random seed).
 */
public record AskResponse(
        String answer,
        String model,
        String promptVersion,
        Double temperature,
        Integer seed,
        Integer topK,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs) {
}
