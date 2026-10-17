package com.loanpilot.orchestrator.advise;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
import org.springframework.ai.tool.ToolCallbackProvider;
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
 * runs the matching tool, sends the result back, and repeats until the model writes a final answer.
 * Each tool is wrapped in a RecordingToolCallback so the response shows every call the model made.
 *
 * POC 5: tools come from two places, and the model can't tell them apart:
 * - local: @Tool methods on LoanTools (calculateEmi), run in-process
 * - remote: tools discovered over MCP (getCreditReport on mcp-credit-bureau). The MCP client starter exposes
 *   them as a ToolCallbackProvider; calling one sends tools/call over HTTP to the server.
 * The MCP connection is lazy (spring.ai.mcp.client.initialized=false), so the orchestrator starts even when the
 * bureau is down. A dead bureau shows up in one of two places, and neither fails the request:
 * - at discovery (no tool list fetched yet): remote tools are left out and remoteToolsError says why
 * - at call time (tool list already cached by the MCP client): the call fails, Spring AI hands the error to the
 *   model as the tool result, and toolCalls[].error records it. The prompt tells the model to say the report
 *   is unavailable instead of inventing a score.
 *
 * Logging: INFO shows steps and timing, never the question, tool arguments or results (they can hold PII).
 */
@Service
public class AdviseService {

    private static final Logger log = LoggerFactory.getLogger(AdviseService.class);

    private final ChatClient chatClient;
    private final ToolCallback[] localTools;
    private final List<ToolCallbackProvider> remoteTools;
    private final Resource systemPrompt;
    private final String promptVersion;
    private final String defaultModel;
    private final double temperature;
    private final int seed;

    public AdviseService(ChatClient.Builder builder,
                         LoanTools loanTools,
                         List<ToolCallbackProvider> remoteTools,
                         @Value("${loanpilot.advise.prompt}") Resource systemPrompt,
                         @Value("${loanpilot.advise.prompt-version}") String promptVersion,
                         @Value("${spring.ai.ollama.chat.model}") String defaultModel,
                         @Value("${loanpilot.advise.temperature}") double temperature,
                         @Value("${loanpilot.advise.seed}") int seed) {
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
        // Finds every @Tool method on LoanTools and builds its JSON schema from the method signature.
        // The provider throws when there are none, so the app can still start before the tools are written.
        boolean hasTools = Arrays.stream(loanTools.getClass().getMethods()).anyMatch(m -> m.isAnnotationPresent(Tool.class));
        this.localTools = hasTools
                ? MethodToolCallbackProvider.builder().toolObjects(loanTools).build().getToolCallbacks()
                : new ToolCallback[0];
        this.systemPrompt = systemPrompt;
        this.promptVersion = promptVersion;
        this.defaultModel = defaultModel;
        this.temperature = temperature;
        this.seed = seed;
        this.remoteTools = remoteTools;
        if (localTools.length == 0) {
            log.warn("LoanTools has no @Tool methods yet");
        }
    }

    public AdviseResponse advise(AdviseRequest request) {
        String id = UUID.randomUUID().toString().substring(0, 8);   // correlation id for log lines
        List<ToolCallRecord> calls = new CopyOnWriteArrayList<>();
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(localTools));
        String remoteToolsError = addRemoteTools(tools, id);
        ToolCallback[] recording = tools.stream()
                .map(t -> (ToolCallback) new RecordingToolCallback(t, calls, id))
                .toArray(ToolCallback[]::new);
        List<String> toolNames = tools.stream().map(t -> t.getToolDefinition().name()).toList();

        // Same repeatability settings as extraction: easier to tell a prompt change from sampling noise.
        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                .temperature(temperature)
                .seed(seed);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .options(options)
                .system(systemPrompt)
                .user(userMessage(request))
                .toolCallbacks(recording)
                // Never sent to the model. Local tools can declare a ToolContext parameter; MCP tools receive it
                // as the request's _meta (requestId correlates logs across services, applicantId is a trusted id
                // the bureau prefers over a name the model extracted). Always non-empty: Spring AI refuses to call
                // a ToolContext method when the context is empty.
                .toolContext(hasText(request.applicantId())
                        ? Map.of("requestId", id, "applicantId", request.applicantId())
                        : Map.of("requestId", id));

        log.info("[advise {}] Calling LLM: model={}, prompt={}, tools={}, question {} chars, applicantId given={}. Waiting...",
                id, defaultModel, promptVersion, toolNames, request.question().length(), hasText(request.applicantId()));
        long start = System.currentTimeMillis();
        ChatResponse response = spec.call().chatResponse();
        long latencyMs = System.currentTimeMillis() - start;

        String answer = response.getResult().getOutput().getText();
        if (!hasText(answer)) {
            // Seen live: with getCreditReport missing (bureau down), qwen2.5 returned empty text for a credit
            // question. Never hand an officer a blank answer; say what happened, decided by code, not the prompt.
            log.warn("[advise {}] Model returned an empty answer (remote tools available={})", id, remoteToolsError == null);
            answer = remoteToolsError != null
                    ? "No answer: the credit bureau is unavailable right now, so credit data could not be fetched. Try again later."
                    : "No answer: the model returned an empty reply. Try rephrasing the question.";
        }
        String model = hasText(response.getMetadata().getModel()) ? response.getMetadata().getModel() : defaultModel;
        Usage usage = response.getMetadata().getUsage();
        log.info("[advise {}] Done in {} ms: {} tool call(s) {}, final call tokens in/out={}/{}", id, latencyMs,
                calls.size(), calls.stream().map(ToolCallRecord::tool).toList(),
                usage != null ? usage.getPromptTokens() : null, usage != null ? usage.getCompletionTokens() : null);

        return new AdviseResponse(
                answer,
                List.copyOf(calls),
                toolNames,
                remoteToolsError,
                model,
                promptVersion,
                sha256(request.question()),
                usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null,
                latencyMs);
    }

    /**
     * Seen live: given "Can this applicant afford...?" plus a code-supplied applicantId, qwen2.5 invented a name
     * ("Rajiv") for getCreditReport and used it in the answer. The data was right (the _meta id wins at the
     * bureau) but the prose named the wrong person, and telling the tool description "never guess a name" didn't
     * change it. Fix: give the model the real id so it has nothing to invent. The id is not PII; the trusted copy
     * still travels in _meta and wins over whatever the model passes.
     */
    private static String userMessage(AdviseRequest request) {
        return hasText(request.applicantId())
                ? request.question() + "\n\n(Applicant id: " + request.applicantId() + ")"
                : request.question();
    }

    /** Adds the MCP tools to the list. Returns null on success, or why remote tools are missing. */
    private String addRemoteTools(List<ToolCallback> tools, String id) {
        try {
            for (ToolCallbackProvider provider : remoteTools) {
                tools.addAll(Arrays.asList(provider.getToolCallbacks()));
            }
            return null;
        } catch (RuntimeException e) {
            // Bureau down or unreachable: answer with local tools only. The prompt tells the model to say a score
            // is unavailable rather than invent one, and the response shows why.
            log.warn("[advise {}] Remote MCP tools unavailable ({}): continuing with local tools only", id,
                    e.getClass().getSimpleName());
            return "Remote MCP tools unavailable: " + e.getMessage();
        }
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
