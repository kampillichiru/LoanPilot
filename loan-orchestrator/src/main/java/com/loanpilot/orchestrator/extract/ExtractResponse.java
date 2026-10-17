package com.loanpilot.orchestrator.extract;

import java.util.List;

/**
 * The extracted slip plus everything needed to debug and audit the extraction.
 *
 * @param slip          null when the model's output could not be parsed into a SalarySlip
 * @param totalDeductionsComputed  true when the slip printed no total and Java added up the deduction lines,
 *                      so an auditor can tell which numbers the LLM copied and which code calculated
 * @param valid        true only when it parsed AND passed Bean Validation
 * @param errors        parse error or validation violations ("netPay: must be greater than 0")
 * @param rawOutput     exactly what the model returned: the first thing to read when extraction fails
 * @param inputSha256   hash of the input text, so the call can be audited without storing the slip (rule 3)
 */
public record ExtractResponse(
        SalarySlip slip,
        boolean totalDeductionsComputed,
        boolean valid,
        List<String> errors,
        String rawOutput,
        String model,
        String promptVersion,
        boolean nativeSchema,
        String inputSha256,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs) {
}
