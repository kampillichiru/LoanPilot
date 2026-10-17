package com.loanpilot.orchestrator.extract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param text          raw salary-slip text (synthetic only, never a real slip)
 * @param nativeSchema  true = also pass the JSON schema to Ollama's "format" option, so the model is
 *                      constrained to valid JSON while generating. false/null = schema only in the prompt.
 */
public record ExtractRequest(
        @NotBlank @Size(max = 20_000) String text,
        Boolean nativeSchema) {
}
