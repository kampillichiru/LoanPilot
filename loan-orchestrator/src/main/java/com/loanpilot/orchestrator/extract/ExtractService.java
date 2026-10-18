package com.loanpilot.orchestrator.extract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import jakarta.validation.Validator;
import com.loanpilot.orchestrator.guard.LlmGuardAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import static org.springframework.util.StringUtils.hasText;

/**
 * Salary-slip text → SalarySlip, then Bean Validation.
 *
 * Uses BeanOutputConverter directly instead of chatClient...entity(SalarySlip.class). Same mechanism
 * (schema appended to the prompt, JSON parsed back), but .entity() throws on bad JSON and the raw model
 * output is lost. Here we keep it, because the raw output is what you study when extraction fails.
 *
 * Logging: INFO shows the steps, never slip content (names, PAN, salary are PII and logs get shipped and
 * shared). The full prompt and reply are logged by SimpleLoggerAdvisor at DEBUG only, for local learning.
 */
@Service
public class ExtractService {

    private static final Logger log = LoggerFactory.getLogger(ExtractService.class);

    private final ChatClient chatClient;
    private final Validator validator;
    private final Resource systemPrompt;
    private final String promptVersion;
    private final String defaultModel;
    private final double temperature;
    private final int seed;
    private final BeanOutputConverter<SalarySlip> converter = new BeanOutputConverter<>(SalarySlip.class);

    public ExtractService(ChatClient.Builder builder,
                          Validator validator,
                          @Value("${loanpilot.extract.prompt}") Resource systemPrompt,
                          @Value("${loanpilot.extract.prompt-version}") String promptVersion,
                          @Value("${spring.ai.ollama.chat.model}") String defaultModel,
                          @Value("${loanpilot.extract.temperature}") double temperature,
                          @Value("${loanpilot.extract.seed}") int seed) {
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
        this.validator = validator;
        this.systemPrompt = systemPrompt;
        this.promptVersion = promptVersion;
        this.defaultModel = defaultModel;
        this.temperature = temperature;
        this.seed = seed;
    }

    public ExtractResponse extract(ExtractRequest request) {
        boolean nativeSchema = Boolean.TRUE.equals(request.nativeSchema());
        String inputHash = sha256(request.text());
        String id = inputHash.substring(0, 12);   // correlation id for log lines, safe to log (no PII)
        log.info("[slip {}] 1/4 Received slip: {} chars, nativeSchema={}", id, request.text().length(), nativeSchema);

        // Extraction should be repeatable (POC 1 lesson): fixed temperature + seed from config.
        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                .temperature(temperature)
                .seed(seed);
        if (nativeSchema) {
            options.format(converter.getJsonSchemaMap());
        }

        // getFormat() = "Your response should be in JSON format... <schema>". Appended in code, not in the
        // .st file: the schema is full of { } which StringTemplate would try to parse as placeholders.
        String userMessage = request.text() + "\n\n" + converter.getFormat();

        log.info("[slip {}] 2/4 Calling LLM: model={}, prompt={}, temperature={}, seed={}. Waiting...",
                id, defaultModel, promptVersion, temperature, seed);
        long start = System.currentTimeMillis();
        ChatResponse response = chatClient.prompt()
                .options(options)
                .system(systemPrompt)
                .user(userMessage)
                .advisors(a -> a.param(LlmGuardAdvisor.PURPOSE, "slip-extraction")
                        .param(LlmGuardAdvisor.PROMPT_VERSION, promptVersion))
                .call()
                .chatResponse();
        long latencyMs = System.currentTimeMillis() - start;

        String raw = response.getResult().getOutput().getText();
        String model = hasText(response.getMetadata().getModel()) ? response.getMetadata().getModel() : defaultModel;
        Usage usage = response.getMetadata().getUsage();
        Integer promptTokens = usage != null ? usage.getPromptTokens() : null;
        Integer completionTokens = usage != null ? usage.getCompletionTokens() : null;
        log.info("[slip {}] 3/4 LLM replied in {} ms: model={}, tokens in/out={}/{}, reply {} chars",
                id, latencyMs, model, promptTokens, completionTokens, raw != null ? raw.length() : 0);

        SalarySlip slip = null;
        boolean totalComputed = false;
        List<String> errors;
        try {
            SalarySlip extracted = converter.convert(raw);
            // Rule 1: the model copies, Java adds. Fill in totalDeductions when the slip didn't print one.
            slip = extracted.withComputedTotals();
            totalComputed = extracted.totalDeductions() == null && slip.totalDeductions() != null;
            if (totalComputed) {
                log.info("[slip {}] No total deductions printed: computed in Java from {} line(s)",
                        id, slip.deductions().size());
            }
            errors = validator.validate(slip).stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .toList();
            if (errors.isEmpty()) {
                log.info("[slip {}] 4/4 Parsed and valid", id);
            } else {
                // Property paths + constraint messages only; the rejected values are not logged.
                log.warn("[slip {}] 4/4 Parsed but INVALID, {} violation(s): {}", id, errors.size(), errors);
            }
        } catch (RuntimeException e) {
            // Parser messages can quote the model output (PII), so only the type at WARN.
            log.warn("[slip {}] 4/4 UNPARSEABLE model output ({}). See rawOutput in the response", id,
                    e.getClass().getSimpleName());
            log.debug("[slip {}] Parse error detail", id, e);
            errors = List.of("Unparseable model output: " + e.getMessage());
        }

        return new ExtractResponse(
                slip,
                totalComputed,
                slip != null && errors.isEmpty(),
                errors,
                raw,
                model,
                promptVersion,
                nativeSchema,
                inputHash,
                promptTokens,
                completionTokens,
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
