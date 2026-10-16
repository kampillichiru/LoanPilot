package com.loanpilot.orchestrator.ask;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class AskService {

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
        this.chatClient = builder.build();
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
                .user(request.question());
        if (useSystemPrompt) {
            spec = spec.system(systemPrompt);
        }

        long start = System.currentTimeMillis();
        ChatResponse response = spec.call().chatResponse();
        long latencyMs = System.currentTimeMillis() - start;

        String answer = response.getResult().getOutput().getText();
        String model = response.getMetadata().getModel() != null ? response.getMetadata().getModel() : defaultModel;
        Usage usage = response.getMetadata().getUsage();

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
