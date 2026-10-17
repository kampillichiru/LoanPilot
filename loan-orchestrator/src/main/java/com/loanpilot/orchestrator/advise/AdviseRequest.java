package com.loanpilot.orchestrator.advise;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param question     free text, e.g. "Can Priya afford 5L for 3 years?"
 * @param applicantId  optional ("APP-001"). Passed to tools through ToolContext, which the model never sees.
 *                     Lets you compare: model finds the applicant from the question vs. code supplies it
 */
public record AdviseRequest(
        @NotBlank @Size(max = 2_000) String question,
        String applicantId) {
}
