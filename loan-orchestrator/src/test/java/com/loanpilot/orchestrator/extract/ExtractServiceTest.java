package com.loanpilot.orchestrator.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ByteArrayResource;

/**
 * Tests the plumbing around extraction with a fake model: what is sent, and how the reply is handled.
 * Whether a real model extracts correctly is measured by the runner in evals/, not here.
 */
class ExtractServiceTest {

    private static final String SLIP = "PAYSLIP MARCH 2026\nEmployee: Test Person\nNet Pay: 85,000";

    private static final String VALID_JSON = """
            {"employeeName": "Test Person", "employerName": "Acme Infotech Pvt Ltd", "payPeriod": "2026-03",
             "grossEarnings": 95000,
             "deductions": [{"name": "Provident Fund", "amount": 1800}, {"name": "Professional Tax", "amount": 200},
                            {"name": "Income Tax (TDS)", "amount": 8000.50}],
             "totalDeductions": 10000.50, "netPay": 84999.50}""";

    /** Returns a canned reply and remembers the prompt it was given. */
    static class FakeChatModel implements ChatModel {
        String reply = VALID_JSON;
        Prompt lastPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        /** Mirror OllamaChatModel's defaults type, or seed/format get dropped when ChatClient merges options. */
        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }
    }

    private final FakeChatModel fakeModel = new FakeChatModel();
    private final ExtractService service = new ExtractService(
            ChatClient.builder(fakeModel),
            Validation.buildDefaultValidatorFactory().getValidator(),
            new ByteArrayResource("Extract the salary slip.".getBytes()),
            "extract-salary-slip-test",
            "qwen2.5:7b",
            0.0,
            42);

    @Test
    void sendsSlipAndSchemaWithRepeatableOptions() {
        service.extract(new ExtractRequest(SLIP, null));

        Prompt sent = fakeModel.lastPrompt;
        assertThat(sent.getSystemMessage().getText()).isEqualTo("Extract the salary slip.");
        assertThat(sent.getUserMessage().getText())
                .contains(SLIP)
                .contains("\"netPay\"")                          // the schema generated from SalarySlip
                .contains("not year-to-date")                      // @JsonPropertyDescription reaches the model
                .doesNotContain("amountsConsistent");              // the @JsonIgnore validation methods don't
        OllamaChatOptions options = (OllamaChatOptions) sent.getOptions();
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getSeed()).isEqualTo(42);
        assertThat(options.getFormat()).isNull();
    }

    @Test
    void nativeSchemaSendsJsonSchemaAsOllamaFormat() {
        ExtractResponse response = service.extract(new ExtractRequest(SLIP, true));

        assertThat(response.nativeSchema()).isTrue();
        Object format = ((OllamaChatOptions) fakeModel.lastPrompt.getOptions()).getFormat();
        assertThat(format).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) format).containsKey("properties")).isTrue();
    }

    @Test
    void parsesValidJsonIntoSlip() {
        ExtractResponse response = service.extract(new ExtractRequest(SLIP, null));

        assertThat(response.slip()).isEqualTo(new SalarySlip("Test Person", "Acme Infotech Pvt Ltd", "2026-03",
                new BigDecimal("95000"),
                List.of(new SalarySlip.Deduction("Provident Fund", new BigDecimal("1800")),
                        new SalarySlip.Deduction("Professional Tax", new BigDecimal("200")),
                        new SalarySlip.Deduction("Income Tax (TDS)", new BigDecimal("8000.50"))),
                new BigDecimal("10000.50"), new BigDecimal("84999.50")));
        assertThat(response.totalDeductionsComputed()).isFalse();
        assertThat(response.valid()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.promptVersion()).isEqualTo("extract-salary-slip-test");
        assertThat(response.inputSha256()).hasSize(64).doesNotContain("Test Person");
    }

    @Test
    void stripsMarkdownFencesAroundJson() {
        fakeModel.reply = "```json\n" + VALID_JSON + "\n```";

        ExtractResponse response = service.extract(new ExtractRequest(SLIP, null));

        assertThat(response.valid()).isTrue();
    }

    @Test
    void unparseableOutputKeepsRawTextForDebugging() {
        fakeModel.reply = "The net pay is ₹85,000.";

        ExtractResponse response = service.extract(new ExtractRequest(SLIP, null));

        assertThat(response.slip()).isNull();
        assertThat(response.valid()).isFalse();
        assertThat(response.errors()).singleElement().asString().startsWith("Unparseable model output");
        assertThat(response.rawOutput()).isEqualTo("The net pay is ₹85,000.");
    }

    @Test
    void amountsThatDontAddUpAreInvalid() {
        // e.g. the model picked a YTD figure for netPay
        fakeModel.reply = VALID_JSON.replace("84999.50", "509997.00");

        ExtractResponse response = service.extract(new ExtractRequest(SLIP, null));

        assertThat(response.slip()).isNotNull();
        assertThat(response.valid()).isFalse();
        assertThat(response.errors()).containsExactly("amountsConsistent: grossEarnings - totalDeductions must equal netPay");
    }

    @Test
    void missingTotalIsComputedInJavaNotByTheModel() {
        // slip-09 case: no total line printed, the model returns null and Java adds up the lines (rule 1)
        fakeModel.reply = VALID_JSON.replace("\"totalDeductions\": 10000.50", "\"totalDeductions\": null");

        ExtractResponse response = service.extract(new ExtractRequest(SLIP, null));

        assertThat(response.valid()).isTrue();
        assertThat(response.totalDeductionsComputed()).isTrue();
        assertThat(response.slip().totalDeductions()).isEqualByComparingTo("10000.50");
    }

    @Test
    void deductionLinesThatDontMatchPrintedTotalAreInvalid() {
        // e.g. the model skipped or misread a line
        fakeModel.reply = VALID_JSON.replace("\"amount\": 8000.50", "\"amount\": 800.50");

        ExtractResponse response = service.extract(new ExtractRequest(SLIP, null));

        assertThat(response.valid()).isFalse();
        assertThat(response.errors()).containsExactly("deductionLinesConsistent: deduction lines must add up to totalDeductions");
    }

    @Test
    void missingFieldAndBadMonthFormatAreInvalid() {
        fakeModel.reply = VALID_JSON.replace("\"2026-03\"", "\"Mar'26\"").replace("\"Test Person\"", "null");

        ExtractResponse response = service.extract(new ExtractRequest(SLIP, null));

        assertThat(response.valid()).isFalse();
        assertThat(response.errors()).containsExactly("employeeName: must not be blank", "payPeriod: must be YYYY-MM");
    }
}
