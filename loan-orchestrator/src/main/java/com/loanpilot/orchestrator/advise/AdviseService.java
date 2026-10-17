package com.loanpilot.orchestrator.advise;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import static org.springframework.util.StringUtils.hasText;

/**
 * POC 3: question → agent loop (LLM ⇄ tools) → answer.
 *
 * Spring AI runs the loop inside .call(): the model replies with a tool request instead of text, Spring AI
 * runs the matching @Tool method, sends the result back, and repeats until the model writes a final answer.
 * Each tool is wrapped in a RecordingToolCallback so the response shows every call the model made.
 *
 * Logging: INFO shows steps and timing, never the question, tool arguments or results (they can hold PII).
 */
@Service
public class AdviseService {

    private static final Logger log = LoggerFactory.getLogger(AdviseService.class);

    private final ChatClient chatClient;
    private final ToolCallback[] tools;
    private final Resource systemPrompt;
    private final String promptVersion;
    private final String defaultModel;
    private final double temperature;
    private final int seed;

    public AdviseService(ChatClient.Builder builder,
                         LoanTools loanTools,
                         @Value("${loanpilot.advise.prompt}") Resource systemPrompt,
                         @Value("${loanpilot.advise.prompt-version}") String promptVersion,
                         @Value("${spring.ai.ollama.chat.model}") String defaultModel,
                         @Value("${loanpilot.advise.temperature}") double temperature,
                         @Value("${loanpilot.advise.seed}") int seed) {
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
        // Finds every @Tool method on LoanTools and builds its JSON schema from the method signature.
        // The provider throws when there are none, so the app can still start before the tools are written.
        boolean hasTools = Arrays.stream(loanTools.getClass().getMethods()).anyMatch(m -> m.isAnnotationPresent(Tool.class));
        this.tools = hasTools
                ? MethodToolCallbackProvider.builder().toolObjects(loanTools).build().getToolCallbacks()
                : new ToolCallback[0];
        this.systemPrompt = systemPrompt;
        this.promptVersion = promptVersion;
        this.defaultModel = defaultModel;
        this.temperature = temperature;
        this.seed = seed;
        if (tools.length == 0) {
            log.warn("LoanTools has no @Tool methods yet: /api/advise will answer without tools");
        }
    }

    public AdviseResponse advise(AdviseRequest request) {
        String id = UUID.randomUUID().toString().substring(0, 8);   // correlation id for log lines
        List<ToolCallRecord> calls = new CopyOnWriteArrayList<>();
        ToolCallback[] recording = Arrays.stream(tools)
                .map(t -> (ToolCallback) new RecordingToolCallback(t, calls, id))
                .toArray(ToolCallback[]::new);
        List<String> toolNames = Arrays.stream(tools).map(t -> t.getToolDefinition().name()).toList();

        // Same repeatability settings as extraction: easier to tell a prompt change from sampling noise.
        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                .temperature(temperature)
                .seed(seed);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .options(options)
                .system(systemPrompt)
                .user(request.question())
                .toolCallbacks(recording)
                // Reaches tools that declare a ToolContext parameter. Never sent to the model. Always non-empty:
                // Spring AI refuses to call a ToolContext method when the context is empty.
                .toolContext(hasText(request.applicantId())
                        ? Map.of("requestId", id, "applicantId", request.applicantId())
                        : Map.of("requestId", id));

        log.info("[advise {}] Calling LLM: model={}, prompt={}, tools={}, question {} chars, applicantId given={}. Waiting...",
                id, defaultModel, promptVersion, toolNames, request.question().length(), hasText(request.applicantId()));
        long start = System.currentTimeMillis();
        ChatResponse response = spec.call().chatResponse();
        long latencyMs = System.currentTimeMillis() - start;

        String answer = response.getResult().getOutput().getText();
        String model = hasText(response.getMetadata().getModel()) ? response.getMetadata().getModel() : defaultModel;
        Usage usage = response.getMetadata().getUsage();
        log.info("[advise {}] Done in {} ms: {} tool call(s) {}, final call tokens in/out={}/{}", id, latencyMs,
                calls.size(), calls.stream().map(ToolCallRecord::tool).toList(),
                usage != null ? usage.getPromptTokens() : null, usage != null ? usage.getCompletionTokens() : null);

        return new AdviseResponse(
                answer,
                List.copyOf(calls),
                toolNames,
                model,
                promptVersion,
                sha256(request.question()),
                usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null,
                latencyMs);
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
