package com.loanpilot.orchestrator.extract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One month's salary slip, as extracted by the LLM. Spring AI turns this record into the JSON schema the
 * model must fill, so the @JsonPropertyDescription texts are instructions the model reads next to each field.
 *
 * Design choices:
 * - BigDecimal for money: slips can have paise, and double can't represent 0.10 exactly.
 * - payPeriod as a "YYYY-MM" String: a simple, unambiguous schema for the model; @Pattern enforces it.
 * - No PAN / account number: the loan flow doesn't need them, so we don't extract them (data minimisation).
 * - The model COPIES numbers, Java does the ARITHMETIC (rule 1). In v1 the model was asked for a total that
 *   wasn't printed (slip-09) and added 1,800 + 200 + 9,150 = 19,700. Now it copies each deduction line and
 *   withComputedTotals() adds them up.
 * - deductions is declared before totalDeductions: the model writes fields in schema order, so it lists
 *   the lines before it reaches the total.
 * - Validation is the safety net for "parsed fine, but wrong". The cross-field checks catch most of those.
 */
public record SalarySlip(

        @NotBlank
        @JsonPropertyDescription("Full name of the employee, exactly as printed on the slip")
        String employeeName,

        @NotBlank
        @JsonPropertyDescription("Name of the employer company, exactly as printed (keep Pvt Ltd / Ltd / LLP)")
        String employerName,

        @NotNull
        @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be YYYY-MM")
        @JsonPropertyDescription("Month the slip is for, as YYYY-MM. Example: March 2026 or Mar'26 -> 2026-03")
        String payPeriod,

        @NotNull @Positive
        @JsonPropertyDescription("Total earnings for THIS month only (not year-to-date), in rupees as a plain number")
        BigDecimal grossEarnings,

        @NotNull @Valid
        @JsonPropertyDescription("Every deduction line for THIS month (not year-to-date), copied as printed. "
                + "Do not include a total line. Do not add them up")
        List<Deduction> deductions,

        @NotNull @PositiveOrZero
        @JsonPropertyDescription("Total deductions for THIS month ONLY IF a total line is printed on the slip, "
                + "else null. Never calculate it")
        BigDecimal totalDeductions,

        @NotNull @Positive
        @JsonPropertyDescription("Net pay / take-home for THIS month only (not year-to-date), in rupees as a plain number")
        BigDecimal netPay) {

    /** One deduction line, e.g. "Provident Fund", 1800. */
    public record Deduction(
            @NotBlank String name,
            @NotNull @PositiveOrZero BigDecimal amount) {
    }

    /**
     * Rule 1: arithmetic happens in Java, not in the LLM. If the slip didn't print a total, compute it from the
     * copied deduction lines. Returns this unchanged when the total was printed or the lines are unusable
     * (validation then reports what's missing).
     */
    public SalarySlip withComputedTotals() {
        if (totalDeductions != null || deductions == null || deductions.isEmpty()
                || deductions.stream().anyMatch(d -> d == null || d.amount() == null)) {
            return this;
        }
        BigDecimal sum = deductions.stream().map(Deduction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SalarySlip(employeeName, employerName, payPeriod, grossEarnings, deductions, sum, netPay);
    }

    /** Gross - deductions must equal net. A wrong pick (e.g. a YTD figure) almost always breaks this. */
    @JsonIgnore
    @AssertTrue(message = "grossEarnings - totalDeductions must equal netPay")
    public boolean isAmountsConsistent() {
        if (grossEarnings == null || totalDeductions == null || netPay == null) {
            return true;   // @NotNull already reports the missing field
        }
        return grossEarnings.subtract(totalDeductions).compareTo(netPay) == 0;
    }

    /** When the slip prints a total, the copied lines must add up to it. Catches a misread or skipped line. */
    @JsonIgnore
    @AssertTrue(message = "deduction lines must add up to totalDeductions")
    public boolean isDeductionLinesConsistent() {
        if (totalDeductions == null || deductions == null
                || deductions.stream().anyMatch(d -> d == null || d.amount() == null)) {
            return true;   // @NotNull / @Valid already report the problem
        }
        BigDecimal sum = deductions.stream().filter(Objects::nonNull).map(Deduction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.compareTo(totalDeductions) == 0;
    }
}
