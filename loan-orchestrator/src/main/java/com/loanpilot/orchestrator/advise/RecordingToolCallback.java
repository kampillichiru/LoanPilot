package com.loanpilot.orchestrator.advise;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorator around a real tool: runs it unchanged and records the call (rule 3: every step is auditable).
 *
 * Spring AI runs the agent loop internally, so without this the final ChatResponse doesn't tell you which
 * tools were called or with what. One instance per request, writing into that request's list.
 *
 * Logging: INFO shows tool name, timing and sizes only. Arguments and results can contain PII.
 */
class RecordingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RecordingToolCallback.class);

    private final ToolCallback delegate;
    private final List<ToolCallRecord> calls;
    private final String requestId;

    RecordingToolCallback(ToolCallback delegate, List<ToolCallRecord> calls, String requestId) {
        this.delegate = delegate;
        this.calls = calls;
        this.requestId = requestId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return record(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return record(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String record(String toolInput, Supplier<String> invocation) {
        String name = delegate.getToolDefinition().name();
        log.info("[advise {}] Model requested tool '{}' (args {} chars)", requestId, name,
                toolInput != null ? toolInput.length() : 0);
        long start = System.currentTimeMillis();
        try {
            String result = invocation.get();
            long latencyMs = System.currentTimeMillis() - start;
            calls.add(new ToolCallRecord(name, toolInput, result, null, latencyMs));
            log.info("[advise {}] Tool '{}' returned in {} ms (result {} chars)", requestId, name, latencyMs,
                    result != null ? result.length() : 0);
            return result;
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - start;
            calls.add(new ToolCallRecord(name, toolInput, null, e.getMessage(), latencyMs));
            log.warn("[advise {}] Tool '{}' threw {} after {} ms", requestId, name, e.getClass().getSimpleName(),
                    latencyMs);
            throw e;   // Spring AI decides what the model sees (by default: the error message)
        }
    }
}
