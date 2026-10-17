package com.loanpilot.bureau;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpMeta;

/** The tool as plain Java: lookups, statuses and what is (not) returned. */
class CreditBureauToolsTest {

    private final CreditBureauTools tools = new CreditBureauTools(new BureauRepository());

    @Test
    void priyaByName() {
        CreditReport report = tools.getCreditReport(null, "Priya", null);

        assertThat(report.status()).isEqualTo("FOUND");
        assertThat(report.applicantId()).isEqualTo("APP-001");
        assertThat(report.cibilScore()).isEqualTo(762);
        assertThat(report.totalExistingEmi()).isEqualByComparingTo("20000");
        assertThat(report.dpd90Count()).isZero();
    }

    @Test
    void idWinsOverName() {
        assertThat(tools.getCreditReport("APP-002", "Priya", null).cibilScore()).isEqualTo(641);
    }

    @Test
    void trustedMetaIdWinsOverWhatTheLlmFilledIn() {
        // POC 5: the orchestrator's code puts applicantId in _meta; its LLM filled applicantName.
        McpMeta meta = new McpMeta(Map.of("applicantId", "APP-004", "requestId", "abc123"));

        assertThat(tools.getCreditReport("APP-001", "Priya", meta).applicantId()).isEqualTo("APP-004");
    }

    @Test
    void metaWithoutApplicantIdFallsBackToArguments() {
        McpMeta meta = new McpMeta(Map.of("requestId", "abc123"));

        assertThat(tools.getCreditReport(null, "Priya", meta).applicantId()).isEqualTo("APP-001");
    }

    @Test
    void thinFileIsNoCreditHistoryNotNotFound() {
        CreditReport report = tools.getCreditReport("APP-003", null, null);

        assertThat(report.status()).isEqualTo("NO_CREDIT_HISTORY");
        assertThat(report.applicantId()).isEqualTo("APP-003");
        assertThat(report.cibilScore()).isNull();
    }

    @Test
    void unknownOrMissingArgumentsAreNotFound() {
        assertThat(tools.getCreditReport("APP-999", null, null).status()).isEqualTo("NOT_FOUND");
        assertThat(tools.getCreditReport(null, "Vikram", null).status()).isEqualTo("NOT_FOUND");
        assertThat(tools.getCreditReport(null, null, null).status()).isEqualTo("NOT_FOUND");
        assertThat(tools.getCreditReport(" ", " ", null).status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void reportNeverContainsPanOrName() {
        // Rule 2: what leaves this service must not identify the person beyond the bank's own id.
        String all = new BureauRepository().all().stream()
                .map(r -> tools.getCreditReport(r.applicantId(), null, null).toString())
                .reduce("", String::concat);

        assertThat(all).doesNotContain("ZZZPZ").doesNotContain("Sharma").doesNotContain("Verma");
    }
}
