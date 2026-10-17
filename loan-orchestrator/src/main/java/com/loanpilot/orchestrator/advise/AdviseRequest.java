package com.loanpilot.orchestrator.advise;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param question     free text, e.g. "Can Priya afford 5L for 3 years?"
 * @param applicantId  optional ("APP-001"). Sent to tools as a trusted value via ToolContext (MCP: the request's _meta),
 *                     and mentioned to the model so it doesn't invent a name. Not PII: the bank's own id
 *                     Compare: model finds the applicant from the question vs. code supplies it
 */
public record AdviseRequest(
        @NotBlank @Size(max = 2_000) String question,
        String applicantId) {
}
