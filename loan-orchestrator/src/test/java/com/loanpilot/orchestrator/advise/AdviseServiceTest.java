package com.loanpilot.orchestrator.advise;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;

/**
 * POC 5 plumbing with a fake model: local and MCP tools are merged, and a dead MCP server degrades the
 * request instead of failing it. Real tool calling over MCP is checked end to end against running services.
 */
class AdviseServiceTest {

    /** Answers with fixed text and never asks for a tool. */
    static class FakeChatModel implements ChatModel {
        String reply = "EMI is 16607.";

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }
    }

    /** Stands in for the MCP client's provider: the tool's call() would go over HTTP in the real thing. */
    static ToolCallback remoteTool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("remote").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }

    private final FakeChatModel model = new FakeChatModel();

    private AdviseService service(List<ToolCallbackProvider> remote) {
        return new AdviseService(ChatClient.builder(model), new LoanTools(), remote,
                new ByteArrayResource("You are a test advisor.".getBytes()), "loan-advisor-test", "qwen2.5:7b",
                0.0, 42);
    }

    @Test
    void offersLocalAndRemoteToolsTogether() {
        ToolCallbackProvider bureau = () -> new ToolCallback[] {remoteTool("getCreditReport")};

        AdviseResponse response = service(List.of(bureau)).advise(new AdviseRequest("Can Priya afford 5L?", null));

        assertThat(response.toolsAvailable()).containsExactlyInAnyOrder("calculateEmi", "getCreditReport");
        assertThat(response.remoteToolsError()).isNull();
    }

    @Test
    void bureauDownMeansLocalToolsOnlyNotAFailedRequest() {
        ToolCallbackProvider deadBureau = () -> {
            throw new IllegalStateException("Connection refused");
        };

        AdviseResponse response = service(List.of(deadBureau)).advise(new AdviseRequest("EMI for 5L?", null));

        assertThat(response.answer()).isEqualTo("EMI is 16607.");
        assertThat(response.toolsAvailable()).containsExactly("calculateEmi");
        assertThat(response.remoteToolsError()).contains("Connection refused");
    }

    @Test
    void emptyModelReplyBecomesAnExplicitAnswer() {
        model.reply = "";
        ToolCallbackProvider deadBureau = () -> {
            throw new IllegalStateException("Connection refused");
        };

        AdviseResponse down = service(List.of(deadBureau)).advise(new AdviseRequest("Priya's score?", null));
        AdviseResponse up = service(List.of()).advise(new AdviseRequest("Priya's score?", null));

        assertThat(down.answer()).contains("credit bureau is unavailable");
        assertThat(up.answer()).contains("model returned an empty reply");
    }

    @Test
    void worksWithNoMcpServersConfigured() {
        AdviseResponse response = service(List.of()).advise(new AdviseRequest("EMI for 5L?", null));

        assertThat(response.toolsAvailable()).containsExactly("calculateEmi");
        assertThat(response.remoteToolsError()).isNull();
    }
}
