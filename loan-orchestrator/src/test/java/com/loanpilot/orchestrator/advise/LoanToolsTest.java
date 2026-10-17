package com.loanpilot.orchestrator.advise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** The maths and lookups are deterministic Java, so they get exact tests (rule 1). */
class LoanToolsTest {

    private final LoanTools tools = new LoanTools(new ApplicantDirectory());

    // --- calculateEmi ---

    @Test
    void priyaEmiMatchesArchitectureDoc() {
        // docs/architecture.md: 5,00,000 at 12% for 36 months -> 16,607
        LoanTools.EmiResult result = LoanTools.emi(new BigDecimal("500000"), new BigDecimal("12"), 36);

        assertThat(result.monthlyEmi()).isEqualByComparingTo("16607");
        assertThat(result.totalPayable()).isEqualByComparingTo("597852");   // 16,607 x 36
        assertThat(result.totalInterest()).isEqualByComparingTo("97852");
    }

    @Test
    void otherKnownEmis() {
        assertThat(LoanTools.emi(new BigDecimal("300000"), new BigDecimal("11"), 24).monthlyEmi())
                .isEqualByComparingTo("13982");
        assertThat(LoanTools.emi(new BigDecimal("1000000"), new BigDecimal("10.5"), 60).monthlyEmi())
                .isEqualByComparingTo("21494");
    }

    @Test
    void zeroInterestIsPrincipalSplitEvenly() {
        LoanTools.EmiResult result = LoanTools.emi(new BigDecimal("120000"), BigDecimal.ZERO, 12);

        assertThat(result.monthlyEmi()).isEqualByComparingTo("10000");
        assertThat(result.totalInterest()).isEqualByComparingTo("0");
    }

    @Test
    void badArgumentsGiveMessagesTheModelCanActOn() {
        assertThatThrownBy(() -> LoanTools.emi(new BigDecimal("5"), new BigDecimal("12"), 0))
                .hasMessageContaining("months must be between 1 and 360");
        assertThatThrownBy(() -> LoanTools.emi(BigDecimal.ZERO, new BigDecimal("12"), 36))
                .hasMessageContaining("principal must be a positive amount");
        assertThatThrownBy(() -> LoanTools.emi(new BigDecimal("500000"), new BigDecimal("120"), 36))
                .hasMessageContaining("annualRatePercent must be between 0 and 50");
    }

    // --- getCibilScore ---

    @Test
    void findsScoreByFirstName() {
        LoanTools.CibilResult result = tools.getCibilScore("priya", null);

        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.cibilScore()).isEqualTo(762);
        assertThat(result.applicantId()).isEqualTo("APP-001");
    }

    @Test
    void applicantIdFromToolContextWinsOverModelSuppliedName() {
        ToolContext context = new ToolContext(Map.of("applicantId", "APP-002"));

        LoanTools.CibilResult result = tools.getCibilScore("Priya", context);

        assertThat(result.applicantId()).isEqualTo("APP-002");
        assertThat(result.cibilScore()).isEqualTo(641);
    }

    @Test
    void thinFileIsAnExplicitStatusNotAnError() {
        LoanTools.CibilResult result = tools.getCibilScore("Arjun", null);

        assertThat(result.status()).isEqualTo("NO_CREDIT_HISTORY");
        assertThat(result.cibilScore()).isNull();
    }

    @Test
    void unknownApplicantIsNotFound() {
        assertThat(tools.getCibilScore("Vikram", null).status()).isEqualTo("NOT_FOUND");
        assertThat(tools.getCibilScore(null, null).status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void resultNeverContainsThePan() {
        assertThat(tools.getCibilScore("Priya", null).toString()).doesNotContain("ZZZPZ");
    }

    // --- what the model actually sees and calls ---

    @Test
    void schemaHidesToolContextAndNoPanParameter() {
        String schema = callback("getCibilScore").getToolDefinition().inputSchema();

        assertThat(schema).contains("applicantName").doesNotContain("toolContext").doesNotContainIgnoringCase("pan\"");
    }

    @Test
    void jsonRoundTripThroughSpringAi() {
        // Same path as a real tool call: JSON arguments in, JSON result out. The context mirrors what
        // AdviseService always sends (an empty context makes Spring AI refuse ToolContext methods).
        String emi = callback("calculateEmi").call("{\"principal\": 500000, \"annualRatePercent\": 12, \"months\": 36}");
        String score = callback("getCibilScore").call("{\"applicantName\": \"Priya\"}",
                new ToolContext(Map.of("requestId", "test")));

        assertThat(emi).contains("\"monthlyEmi\":16607");
        assertThat(score).contains("\"cibilScore\":762").contains("\"status\":\"FOUND\"");
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
