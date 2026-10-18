package com.loanpilot.orchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.loanpilot.orchestrator.decision.ApplicantFacts.EmploymentType;
import com.loanpilot.orchestrator.decision.Decision.Outcome;
import com.loanpilot.orchestrator.decision.DecisionEngine;
import com.loanpilot.orchestrator.extract.ExtractService;
import com.loanpilot.orchestrator.policy.PolicyChunker;
import com.loanpilot.orchestrator.policy.PolicyLibrary;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The workflow with fakes at the edges (bureau tool, LLM) and the real pieces in the middle (extraction parsing
 * and validation, decision engine, policy lookup). No Ollama, no MCP server, no Qdrant.
 */
class ApplicationServiceTest {

    static final String PRIYA_REPORT = """
            [{"type":"text","text":"{\\"applicantId\\":\\"APP-001\\",\\"status\\":\\"FOUND\\",\\"cibilScore\\":762,\
            \\"activeAccounts\\":2,\\"totalExistingEmi\\":20000,\\"dpd90Count\\":0,\\"enquiries6m\\":1,\
            \\"reportDate\\":\\"2026-10-01\\",\\"note\\":\\"synthetic\\"}"}]""";

    /** Plays the bureau's getCreditReport tool and remembers the _meta (ToolContext) it was called with. */
    static class FakeBureauTool implements ToolCallback {
        String result = PRIYA_REPORT;
        RuntimeException failure;
        ToolContext lastContext;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("getCreditReport").description("bureau").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            if (failure != null) {
                throw failure;
            }
            lastContext = toolContext;
            return result;
        }
    }

    /** Answers extraction prompts from a queue of slip JSON, and memo prompts with a fixed memo. */
    static class FakeChatModel implements ChatModel {
        final Deque<String> slipReplies = new ArrayDeque<>();
        String memoReply = "Recommendation: APPROVE\n\nSummary: FOIR 43.1% is within §4.2 and CIBIL 762 meets §4.5.";
        int extractionCalls;
        int memoCalls;
        Prompt lastMemoPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            String reply;
            if (prompt.getSystemMessage().getText().startsWith("Extract")) {
                extractionCalls++;
                reply = slipReplies.removeFirst();
            } else {
                memoCalls++;
                lastMemoPrompt = prompt;
                reply = memoReply;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }
    }

    static String slipJson(String period, int net) {
        int gross = net + 10000;
        return """
                {"employeeName": "Priya Sharma", "employerName": "Acme Infotech Pvt Ltd", "payPeriod": "%s",
                 "grossEarnings": %d, "deductions": [{"name": "Provident Fund", "amount": 1800},
                 {"name": "Professional Tax", "amount": 200}, {"name": "Income Tax (TDS)", "amount": 8000}],
                 "totalDeductions": 10000, "netPay": %d}""".formatted(period, gross, net);
    }

    private final FakeBureauTool bureauTool = new FakeBureauTool();
    private final FakeChatModel chat = new FakeChatModel();
    private final JsonMapper json = JsonMapper.builder().build();
    private final ApplicationService service = new ApplicationService(
            new BureauClient(List.of(() -> new ToolCallback[] { bureauTool }), json),
            new ExtractService(ChatClient.builder(chat), Validation.buildDefaultValidatorFactory().getValidator(),
                    new ByteArrayResource("Extract the salary slip.".getBytes()), "extract-test", "qwen2.5:7b", 0.0, 42),
            new DecisionEngine(),
            new PolicyLibrary(new FileSystemResource("../policy-docs/credit-policy-v1.md"), new PolicyChunker()),
            ChatClient.builder(chat),
            new ByteArrayResource("You write credit memos.".getBytes()), "credit-memo-test", "qwen2.5:7b", 0.0, 42);

    private static LoanApplicationRequest priya(List<String> slips) {
        return new LoanApplicationRequest("APP-001", EmploymentType.SALARIED, new BigDecimal("500000"), 36,
                "Home renovation", slips, null);
    }

    private static final List<String> THREE_SLIPS = List.of("slip jul", "slip aug", "slip sep");

    @Test
    void priyaGoesFromSalarySlipsToAnApprovedMemoInOneCall() {
        chat.slipReplies.addAll(List.of(slipJson("2026-07", 84000), slipJson("2026-08", 85000), slipJson("2026-09", 86000)));

        ApplicationResult r = service.process(priya(THREE_SLIPS));

        assertThat(r.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(r.income().source()).isEqualTo("SALARY_SLIPS");
        assertThat(r.income().netMonthlyIncome()).isEqualByComparingTo("85000");   // average of 84k, 85k, 86k
        assertThat(r.decision().emi()).isEqualByComparingTo("16607");
        assertThat(r.decision().foirPercent()).isEqualByComparingTo("43.1");
        assertThat(r.memoCheck().status()).isEqualTo("OK");
        assertThat(r.memoCheck().promptVersion()).isEqualTo("credit-memo-test");
        assertThat(r.steps()).extracting(ApplicationResult.Step::name)
                .containsExactly("BUREAU", "INCOME", "DECIDE", "POLICY", "MEMO");
        assertThat(chat.extractionCalls).isEqualTo(3);
        // the bureau was called by code with trusted ids in _meta, not by an LLM
        assertThat(bureauTool.lastContext.getContext()).containsEntry("applicantId", "APP-001").containsKey("requestId");
    }

    @Test
    void memoInputHasTheDecisionAndClausesButNoApplicantName() {
        chat.slipReplies.addAll(List.of(slipJson("2026-07", 84000), slipJson("2026-08", 85000), slipJson("2026-09", 86000)));

        service.process(priya(THREE_SLIPS));

        String memoInput = chat.lastMemoPrompt.getUserMessage().getText();
        assertThat(memoInput)
                .contains("Recommendation: APPROVE")
                .contains("FOIR: 43.1%")
                .contains("[§1.3]")                       // decision authority clause, looked up by section
                .doesNotContain("[§4.2]")                 // passed: no clause text, the engine's reason is enough
                .contains("NOT_CHECKED §3.1")             // the officer is told what wasn't verified
                .doesNotContain("Priya")                  // data minimisation: id only
                .doesNotContain("ZZZPZ");
    }

    @Test
    void memoThatContradictsTheEngineIsFlaggedAndTheEngineWins() {
        chat.slipReplies.addAll(List.of(slipJson("2026-07", 84000), slipJson("2026-08", 85000), slipJson("2026-09", 86000)));
        chat.memoReply = "**Recommendation:** REJECT\n\nThe applicant looks risky.";

        ApplicationResult r = service.process(priya(THREE_SLIPS));

        assertThat(r.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(r.memoCheck().status()).isEqualTo("OUTCOME_MISMATCH");
        assertThat(r.memoCheck().memoRecommendation()).isEqualTo("REJECT");
    }

    @Test
    void memoCitingASectionItWasNotGivenIsFlagged() {
        chat.slipReplies.addAll(List.of(slipJson("2026-07", 84000), slipJson("2026-08", 85000), slipJson("2026-09", 86000)));
        chat.memoReply = "Recommendation: APPROVE\n\nMeets §4.2 and §9.9.";

        ApplicationResult r = service.process(priya(THREE_SLIPS));

        assertThat(r.memoCheck().status()).isEqualTo("UNSUPPORTED_CITATION");
        assertThat(r.memoCheck().unsupportedCitations()).containsExactly("9.9");
    }

    @Test
    void sameMonthTwiceIsReferredWithoutRunningTheRulesOrTheMemoLlm() {
        chat.slipReplies.addAll(List.of(slipJson("2026-07", 84000), slipJson("2026-08", 85000), slipJson("2026-08", 85000)));

        ApplicationResult r = service.process(priya(THREE_SLIPS));

        assertThat(r.outcome()).isEqualTo(Outcome.REFER);
        assertThat(r.reasonCodes()).containsExactly("DUPLICATE_PAY_PERIOD");
        assertThat(r.decision()).isNull();
        assertThat(r.memoCheck().status()).isEqualTo("NOT_GENERATED");
        assertThat(chat.memoCalls).isZero();
    }

    @Test
    void invalidSlipIsReferred() {
        chat.slipReplies.addAll(List.of(slipJson("2026-07", 84000), "not json at all", slipJson("2026-09", 86000)));

        ApplicationResult r = service.process(priya(THREE_SLIPS));

        assertThat(r.reasonCodes()).containsExactly("SLIP_EXTRACTION_FAILED");
        assertThat(r.income().slips()).extracting(ApplicationResult.Slip::valid).containsExactly(true, false, true);
    }

    @Test
    void bureauDownFailsFastBeforeAnySlipIsExtracted() {
        bureauTool.failure = new IllegalStateException("connection refused");

        assertThatThrownBy(() -> service.process(priya(THREE_SLIPS)))
                .isInstanceOf(BureauUnavailableException.class)
                .hasMessageContaining("connection refused");
        assertThat(chat.extractionCalls).isZero();
    }

    @Test
    void fastModeUsesDeclaredIncomeAndSaysItIsUnverified() {
        LoanApplicationRequest fast = new LoanApplicationRequest("APP-001", EmploymentType.SALARIED,
                new BigDecimal("500000"), 36, null, List.of(), new BigDecimal("85000"));

        ApplicationResult r = service.process(fast);

        assertThat(r.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(r.income().source()).isEqualTo("DECLARED");
        assertThat(chat.extractionCalls).isZero();
        assertThat(chat.lastMemoPrompt.getUserMessage().getText()).contains("NOT verified");
    }

    @Test
    void rejectionSendsTheTextOfTheFailedClausesToTheMemo() {
        bureauTool.result = """
                [{"type":"text","text":"{\\"applicantId\\":\\"APP-005\\",\\"status\\":\\"FOUND\\",\\"cibilScore\\":698,\
                \\"activeAccounts\\":3,\\"totalExistingEmi\\":26000,\\"dpd90Count\\":0,\\"enquiries6m\\":7}"}]""";
        chat.memoReply = "Recommendation: REJECT\n\nCIBIL 698 is below §4.5; 7 enquiries trigger §4.6.";
        LoanApplicationRequest karan = new LoanApplicationRequest("APP-005", EmploymentType.SALARIED,
                new BigDecimal("600000"), 36, null, List.of(), new BigDecimal("95000"));

        ApplicationResult r = service.process(karan);

        assertThat(r.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(r.memoCheck().status()).isEqualTo("OK");
        assertThat(chat.lastMemoPrompt.getUserMessage().getText())
                .contains("FAIL §4.5 [CIBIL_BELOW_MINIMUM]")
                .contains("[§4.5]").contains("[§4.6]")      // failed / referred: clause text included
                .doesNotContain("[§4.2]");                  // passed: not included
    }

    @Test
    void needsExactlyThreeSlipsOrADeclaredIncome() {
        assertThatThrownBy(() -> service.process(priya(new ArrayList<>(List.of("one", "two")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.process(priya(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesTheMcpContentBlockAroundTheReport() {
        BureauReport report = new BureauClient(List.<ToolCallbackProvider>of(), json).parse(PRIYA_REPORT);

        assertThat(report.cibilScore()).isEqualTo(762);
        assertThat(report.totalExistingEmi()).isEqualByComparingTo("20000");
    }
}
