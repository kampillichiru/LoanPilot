package com.loanpilot.orchestrator.application;

import java.math.BigDecimal;
import java.util.List;

import com.loanpilot.orchestrator.decision.ApplicantFacts.EmploymentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A loan application. Product limits (amount, tenure) are NOT validated here: an out-of-range request is a credit
 * outcome the engine reports with a policy reason (§5.1, §5.2), not a malformed request.
 *
 * @param salarySlips  the last 3 salary slips as text (§2.1: income = average net pay of the last three).
 *                     Synthetic only (rule 5)
 * @param declaredNetMonthlyIncome  FAST MODE for testing the rules and memo without ~5 minutes of CPU extraction:
 *                     used only when salarySlips is empty, and marked as unverified in the result
 */
public record LoanApplicationRequest(
        @NotBlank String applicantId,
        @NotNull EmploymentType employmentType,
        @NotNull @Positive BigDecimal amount,
        @Min(1) @Max(360) int tenureMonths,
        @Size(max = 200) String purpose,
        @Size(max = 3) List<@NotBlank @Size(max = 20_000) String> salarySlips,
        @Positive BigDecimal declaredNetMonthlyIncome) {

    public boolean fastMode() {
        return salarySlips == null || salarySlips.isEmpty();
    }
}
