package com.loanpilot.orchestrator.application;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * mcp-credit-bureau's getCreditReport result, as the workflow reads it. Facts only: no PAN, no name, no verdict.
 * Numeric fields are null when status is NOT_FOUND.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BureauReport(
        String applicantId,
        String status,
        Integer cibilScore,
        Integer activeAccounts,
        BigDecimal totalExistingEmi,
        Integer dpd90Count,
        Integer enquiries6m,
        String reportDate) {
}
