package com.loanpilot.orchestrator.advise;

/**
 * One tool call the model asked for during a request, in the order it happened.
 *
 * @param tool       tool name, as the model saw it
 * @param arguments  the JSON arguments the model generated. Read these when a tool is called wrongly
 * @param result     what your Java code returned to the model, or null if it threw
 * @param error      exception message when the tool threw, else null
 */
public record ToolCallRecord(
        String tool,
        String arguments,
        String result,
        String error,
        long latencyMs) {
}
