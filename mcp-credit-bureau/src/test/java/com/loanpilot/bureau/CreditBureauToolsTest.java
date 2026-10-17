package com.loanpilot.bureau;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The tool as plain Java: lookups, statuses and what is (not) returned. */
class CreditBureauToolsTest {

    private final CreditBureauTools tools = new CreditBureauTools(new BureauRepository());

    @Test
    void priyaByName() {
        CreditReport report = tools.getCreditReport(null, "Priya");

        assertThat(report.status()).isEqualTo("FOUND");
        assertThat(report.applicantId()).isEqualTo("APP-001");
        assertThat(report.cibilScore()).isEqualTo(762);
        assertThat(report.totalExistingEmi()).isEqualByComparingTo("20000");
        assertThat(report.dpd90Count()).isZero();
    }

    @Test
    void idWinsOverName() {
        assertThat(tools.getCreditReport("APP-002", "Priya").cibilScore()).isEqualTo(641);
    }

    @Test
    void thinFileIsNoCreditHistoryNotNotFound() {
        CreditReport report = tools.getCreditReport("APP-003", null);

        assertThat(report.status()).isEqualTo("NO_CREDIT_HISTORY");
        assertThat(report.applicantId()).isEqualTo("APP-003");
        assertThat(report.cibilScore()).isNull();
    }

    @Test
    void unknownOrMissingArgumentsAreNotFound() {
        assertThat(tools.getCreditReport("APP-999", null).status()).isEqualTo("NOT_FOUND");
        assertThat(tools.getCreditReport(null, "Vikram").status()).isEqualTo("NOT_FOUND");
        assertThat(tools.getCreditReport(null, null).status()).isEqualTo("NOT_FOUND");
        assertThat(tools.getCreditReport(" ", " ").status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void reportNeverContainsPanOrName() {
        // Rule 2: what leaves this service must not identify the person beyond the bank's own id.
        String all = new BureauRepository().all().stream()
                .map(r -> tools.getCreditReport(r.applicantId(), null).toString())
                .reduce("", String::concat);

        assertThat(all).doesNotContain("ZZZPZ").doesNotContain("Sharma").doesNotContain("Verma");
    }
}
