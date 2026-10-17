package com.loanpilot.orchestrator.advise;

import java.util.List;

/**
 * The model's answer plus the full trail of how it got there.
 *
 * @param toolCalls       every tool the model called, in order. Empty = the model answered from its own
 *                        "knowledge" (POC 3 done-when needs both tools here for the Priya question)
 * @param toolsAvailable  tool names offered to the model. Empty = LoanTools has no @Tool methods yet
 * @param inputSha256     hash of the question, for auditing without storing it (rule 3)
 * @param promptTokens    tokens of the FINAL LLM call only. Each tool round-trip is another call, so the
 *                        real total is higher. Compare latencyMs with and without tools to see the cost
 * @param latencyMs       whole agent loop: every LLM call plus every tool call
 */
public record AdviseResponse(
        String answer,
        List<ToolCallRecord> toolCalls,
        List<String> toolsAvailable,
        String model,
        String promptVersion,
        String inputSha256,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs) {
}
