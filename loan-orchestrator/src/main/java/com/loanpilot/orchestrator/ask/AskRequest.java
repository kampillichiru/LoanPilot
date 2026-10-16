package com.loanpilot.orchestrator.ask;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * @param question         the user's question
 * @param temperature      optional override (0.0 = deterministic-ish, 2.0 = very random)
 * @param useSystemPrompt  set to false to see how the model behaves without instructions
 * @param seed             optional fixed random seed: same seed + temperature 0 = repeatable answers
 * @param topK             optional: only sample from the K most likely tokens (1 = always the top token)
 */
public record AskRequest(
        @NotBlank String question,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        Boolean useSystemPrompt,
        Integer seed,
        @Min(1) Integer topK) {

    /** Shorthand for callers that don't care about seed / topK. */
    public AskRequest(String question, Double temperature, Boolean useSystemPrompt) {
        this(question, temperature, useSystemPrompt, null, null);
    }
}
