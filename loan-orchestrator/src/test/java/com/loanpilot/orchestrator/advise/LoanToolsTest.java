package com.loanpilot.orchestrator.advise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** The maths is deterministic Java, so it gets exact tests (rule 1). Credit data is tested in mcp-credit-bureau. */
class LoanToolsTest {

    private final LoanTools tools = new LoanTools();

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

    @Test
    void onlyCalculateEmiIsLocal() {
        // POC 5: credit data comes over MCP; a local getCibilScore would compete with getCreditReport.
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();

        assertThat(callbacks).extracting(t -> t.getToolDefinition().name()).containsExactly("calculateEmi");
    }

    @Test
    void jsonRoundTripThroughSpringAi() {
        // Same path as a real tool call: JSON arguments in, JSON result out.
        ToolCallback emi = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()[0];

        assertThat(emi.call("{\"principal\": 500000, \"annualRatePercent\": 12, \"months\": 36}"))
                .contains("\"monthlyEmi\":16607");
    }
}
