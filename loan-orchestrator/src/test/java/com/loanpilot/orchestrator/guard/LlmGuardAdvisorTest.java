package com.loanpilot.orchestrator.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.loanpilot.orchestrator.audit.InMemoryAuditLog;
import com.loanpilot.orchestrator.audit.LlmCallRecord;
import com.loanpilot.orchestrator.privacy.PiiMasker;
import com.loanpilot.orchestrator.privacy.PiiType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/** The guard with a fake model: what the model sees, and what the audit log keeps. */
class LlmGuardAdvisorTest {

    /** Remembers every message text it was sent. */
    static class SpyModel implements ChatModel {
        final List<String> seen = new ArrayList<>();
        RuntimeException failure;

        @Override
        public ChatResponse call(Prompt prompt) {
            prompt.getInstructions().stream().map(Message::getText).forEach(seen::add);
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Net pay 84,000"))));
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }
    }

    private final SpyModel model = new SpyModel();
    private final InMemoryAuditLog audit = new InMemoryAuditLog();
    private final ChatClient client = ChatClient.builder(model)
            .defaultAdvisors(new LlmGuardAdvisor(new PiiMasker(), audit, "qwen2.5:7b"))
            .build();

    private static final String SLIP = "PAN : ZZZPZ0001Z\nBank A/c No. : 0000 0000 0001\nMobile 9876543210\nNet Pay: 84,000";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private String call() {
        return client.prompt()
                .system("Extract the salary slip.")
                .user(SLIP)
                .advisors(a -> a.param(LlmGuardAdvisor.PURPOSE, "slip-extraction")
                        .param(LlmGuardAdvisor.PROMPT_VERSION, "extract-salary-slip-v2"))
                .call()
                .content();
    }

    @Test
    void theModelNeverSeesThePanAccountOrPhone() {
        call();

        String sent = String.join("\n", model.seen);
        assertThat(sent).doesNotContain("ZZZPZ0001Z", "0000 0000 0001", "9876543210")
                .contains("PAN : [PAN]", "Bank A/c No. : [ACCOUNT_NUMBER]", "Mobile [PHONE]", "Net Pay: 84,000")
                .contains("Extract the salary slip.");   // the system prompt is untouched
    }

    @Test
    void everyCallIsAuditedWithPurposePromptVersionHashAndCounts() {
        MDC.put(LlmGuardAdvisor.MDC_REQUEST_ID, "app12345");

        String answer = call();

        assertThat(audit.records).hasSize(1);
        LlmCallRecord r = audit.records.getFirst();
        assertThat(r.requestId()).isEqualTo("app12345");
        assertThat(r.purpose()).isEqualTo("slip-extraction");
        assertThat(r.promptVersion()).isEqualTo("extract-salary-slip-v2");
        assertThat(r.model()).isEqualTo("qwen2.5:7b");
        assertThat(r.output()).isEqualTo(answer).isEqualTo("Net pay 84,000");
        assertThat(r.piiMasked()).containsEntry(PiiType.PAN, 1).containsEntry(PiiType.ACCOUNT_NUMBER, 1)
                .containsEntry(PiiType.PHONE, 1);
        assertThat(r.inputSha256()).hasSize(64);
        assertThat(r.error()).isNull();
        // The audit record holds a hash of the masked input, never the PII itself.
        assertThat(r.toString()).doesNotContain("ZZZPZ0001Z", "0000 0000 0001", "9876543210");
    }

    @Test
    void sameInputGivesTheSameHashSoAnOutputCanBeTracedToItsPrompt() {
        call();
        call();
        assertThat(audit.records.get(0).inputSha256()).isEqualTo(audit.records.get(1).inputSha256());
    }

    @Test
    void aFailedCallIsAuditedTooAndTheErrorStillReachesTheCaller() {
        model.failure = new IllegalStateException("Ollama is down");

        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class);

        assertThat(audit.records).hasSize(1);
        assertThat(audit.records.getFirst().error()).isEqualTo("IllegalStateException");
        assertThat(audit.records.getFirst().output()).isNull();
    }
}
