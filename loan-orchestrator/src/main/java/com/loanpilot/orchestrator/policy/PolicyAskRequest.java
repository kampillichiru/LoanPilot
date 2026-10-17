package com.loanpilot.orchestrator.policy;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param question  a question about the bank's credit policy
 * @param topK      optional override: how many clauses to retrieve (default loanpilot.policy.top-k)
 * @param minScore  optional override: clauses less similar than this are dropped (default loanpilot.policy.min-score)
 */
public record PolicyAskRequest(
        @NotBlank @Size(max = 2_000) String question,
        @Min(1) @Max(20) Integer topK,
        @DecimalMin("0.0") @DecimalMax("1.0") Double minScore) {
}
