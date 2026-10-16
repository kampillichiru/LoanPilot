package com.loanpilot.orchestrator.ask;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

/**
 * @param question         the user's question
 * @param temperature      optional override (0.0 = deterministic-ish, 2.0 = very random)
 * @param useSystemPrompt  set to false to see how the model behaves without instructions
 */
public record AskRequest(
        @NotBlank String question,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        Boolean useSystemPrompt) {
}
