package com.loanpilot.orchestrator.ask;

import java.util.UUID;

import com.loanpilot.orchestrator.guard.LlmGuardAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import static org.springframework.util.StringUtils.hasText;

/**
 * Logging: INFO shows the steps but not the question text (a user may type PII into it).
 * The full prompt and reply are logged by SimpleLoggerAdvisor at DEBUG only.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final ChatClient chatClient;
    private final Resource systemPrompt;
    private final String promptVersion;
    private final String defaultModel;
    private final double defaultTemperature;

    public AskService(ChatClient.Builder builder,
                      @Value("${loanpilot.prompt.system}") Resource systemPrompt,
                      @Value("${loanpilot.prompt.version}") String promptVersion,
                      @Value("${spring.ai.ollama.chat.model}") String defaultModel,
                      @Value("${spring.ai.ollama.chat.temperature}") double defaultTemperature) {
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
        this.systemPrompt = systemPrompt;
        this.promptVersion = promptVersion;
        this.defaultModel = defaultModel;
        this.defaultTemperature = defaultTemperature;
    }

    public AskResponse ask(AskRequest request) {
        double temperature = request.temperature() != null ? request.temperature() : defaultTemperature;
        boolean useSystemPrompt = request.useSystemPrompt() == null || request.useSystemPrompt();

        // Ollama-specific options: the generic ChatOptions has no seed.
        // null seed/topK = not sent, so Ollama uses its defaults (random seed).
        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                .seed(request.seed())
                .topK(request.topK())
                .temperature(temperature);

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .options(options)
                .user(request.question())
                .advisors(a -> a.param(LlmGuardAdvisor.PURPOSE, "ask")
                        .param(LlmGuardAdvisor.PROMPT_VERSION, useSystemPrompt ? promptVersion : "none"));
        if (useSystemPrompt) {
            spec = spec.system(systemPrompt);
        }

        String id = UUID.randomUUID().toString().substring(0, 8);   // correlation id for log lines
        log.info("[ask {}] Calling LLM: model={}, prompt={}, question {} chars, temperature={}, seed={}, topK={}. Waiting...",
                id, defaultModel, useSystemPrompt ? promptVersion : "none", request.question().length(),
                temperature, request.seed(), request.topK());

        long start = System.currentTimeMillis();
        ChatResponse response;
        MDC.put(LlmGuardAdvisor.MDC_REQUEST_ID, id);   // the guard's audit record carries this id
        try {
            response = spec.call().chatResponse();
        } finally {
            MDC.remove(LlmGuardAdvisor.MDC_REQUEST_ID);
        }
        long latencyMs = System.currentTimeMillis() - start;

        String answer = response.getResult().getOutput().getText();
        String model = hasText(response.getMetadata().getModel()) ? response.getMetadata().getModel() : defaultModel;
        Usage usage = response.getMetadata().getUsage();
        log.info("[ask {}] LLM replied in {} ms: model={}, tokens in/out={}/{}, answer {} chars", id, latencyMs, model,
                usage != null ? usage.getPromptTokens() : null, usage != null ? usage.getCompletionTokens() : null,
                answer != null ? answer.length() : 0);

        return new AskResponse(
                answer,
                model,
                useSystemPrompt ? promptVersion : "none",
                temperature,
                request.seed(),
                request.topK(),
                usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null,
                latencyMs);
    }
}
