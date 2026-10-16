package com.loanpilot.orchestrator.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ByteArrayResource;

/**
 * Unit test with a fake ChatModel: no Ollama needed.
 * Lets you see exactly what Spring AI sends to the model.
 */
class AskServiceTest {

    /** Records the last prompt it received and replies with a fixed answer. */
    static class FakeChatModel implements ChatModel {
        Prompt lastPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("FOIR is fixed obligations to income ratio."))));
        }

        /**
         * ChatClient merges request options INTO the model's defaults, so the defaults' type wins.
         * The real OllamaChatModel has OllamaChatOptions defaults; mirror that or seed/topK get dropped.
         */
        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }
    }

    private final FakeChatModel fakeModel = new FakeChatModel();

    private final AskService service = new AskService(
            ChatClient.builder(fakeModel),
            new ByteArrayResource("You are LoanPilot.".getBytes()),
            "banking-assistant-v1",
            "qwen2.5:7b",
            0.2);

    @Test
    void sendsSystemPromptAndTemperature() {
        AskResponse response = service.ask(new AskRequest("What is FOIR?", 0.0, null));

        assertThat(response.answer()).contains("FOIR");
        assertThat(response.promptVersion()).isEqualTo("banking-assistant-v1");
        assertThat(response.temperature()).isEqualTo(0.0);
        assertThat(fakeModel.lastPrompt.getOptions().getTemperature()).isEqualTo(0.0);
        assertThat(fakeModel.lastPrompt.getInstructions())
                .anySatisfy(m -> assertThat(m).isInstanceOf(SystemMessage.class));
    }

    @Test
    void sendsSeedAndTopKForRepeatableAnswers() {
        AskResponse response = service.ask(new AskRequest("What is FOIR?", 0.0, null, 42, 1));

        assertThat(response.seed()).isEqualTo(42);
        assertThat(response.topK()).isEqualTo(1);
        assertThat(fakeModel.lastPrompt.getOptions()).isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions sent = (OllamaChatOptions) fakeModel.lastPrompt.getOptions();
        assertThat(sent.getSeed()).isEqualTo(42);
        assertThat(sent.getTopK()).isEqualTo(1);
        assertThat(sent.getTemperature()).isEqualTo(0.0);
    }

    @Test
    void canSkipSystemPrompt() {
        AskResponse response = service.ask(new AskRequest("What is FOIR?", null, false));

        assertThat(response.promptVersion()).isEqualTo("none");
        assertThat(response.temperature()).isEqualTo(0.2);
        assertThat(fakeModel.lastPrompt.getInstructions())
                .noneSatisfy(m -> assertThat(m).isInstanceOf(SystemMessage.class));
    }
}
