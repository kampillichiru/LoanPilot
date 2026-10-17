package com.loanpilot.orchestrator.advise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** The recorder must be invisible to the tool and the model: same result, same errors, plus a record. */
class RecordingToolCallbackTest {

    /** A fake tool that echoes its input, or throws when asked to. */
    static class EchoTool implements ToolCallback {
        ToolContext lastContext;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("echo").description("Echoes input").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            if (toolInput.contains("boom")) {
                throw new IllegalStateException("tool failed");
            }
            return "echo:" + toolInput;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            this.lastContext = toolContext;
            return call(toolInput);
        }
    }

    private final EchoTool tool = new EchoTool();
    private final List<ToolCallRecord> calls = new ArrayList<>();
    private final RecordingToolCallback recorder = new RecordingToolCallback(tool, calls, "test");

    @Test
    void returnsToolResultUnchangedAndRecordsTheCall() {
        String result = recorder.call("{\"x\":1}");

        assertThat(result).isEqualTo("echo:{\"x\":1}");
        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.tool()).isEqualTo("echo");
            assertThat(c.arguments()).isEqualTo("{\"x\":1}");
            assertThat(c.result()).isEqualTo("echo:{\"x\":1}");
            assertThat(c.error()).isNull();
        });
    }

    @Test
    void passesToolContextThrough() {
        ToolContext context = new ToolContext(Map.of("applicantId", "APP-001"));

        recorder.call("{}", context);

        assertThat(tool.lastContext).isSameAs(context);
    }

    @Test
    void recordsAndRethrowsToolErrors() {
        assertThatThrownBy(() -> recorder.call("boom")).isInstanceOf(IllegalStateException.class);

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.result()).isNull();
            assertThat(c.error()).isEqualTo("IllegalStateException: tool failed");
        });
    }

    @Test
    void exposesTheDelegateDefinitionSoTheModelSeesTheRealTool() {
        assertThat(recorder.getToolDefinition().name()).isEqualTo("echo");
    }
}
