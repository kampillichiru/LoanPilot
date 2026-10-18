package com.loanpilot.orchestrator.guard;

import static org.springframework.util.StringUtils.hasText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.loanpilot.orchestrator.audit.AuditLog;
import com.loanpilot.orchestrator.audit.LlmCallRecord;
import com.loanpilot.orchestrator.privacy.MaskResult;
import com.loanpilot.orchestrator.privacy.PiiMasker;
import com.loanpilot.orchestrator.privacy.PiiType;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * The POC 8 guard. Every ChatClient call passes through it (GuardConfig registers it on all of them), like a servlet
 * filter in front of the LLM:
 *
 *   1. MASK   PII in every user message is replaced by tokens (PiiMasker) BEFORE anything else sees the prompt,
 *             including SimpleLoggerAdvisor's DEBUG logs. System prompts are ours and carry no applicant data.
 *   2. CALL   the rest of the chain (other advisors, tool calling, the model)
 *   3. AUDIT  one LlmCallRecord: model, prompt version, SHA-256 of the masked input, output, tokens, latency, and
 *             how many PII values were masked (rule 3). Failed calls are recorded too, then the error is rethrown.
 *
 * Callers say what the call is for with advisor params, e.g.
 *   .advisors(a -> a.param(LlmGuardAdvisor.PURPOSE, "credit-memo").param(LlmGuardAdvisor.PROMPT_VERSION, version))
 * and the request id comes from the logging MDC ("requestId"), set by the service that owns the request.
 *
 * Not covered: embedding calls (POC 6 question embeddings) don't go through ChatClient. Those questions are typed
 * by an officer about policy, so the risk is low, but a production guard would wrap the EmbeddingModel too.
 */
public class LlmGuardAdvisor implements CallAdvisor {

    public static final String PURPOSE = "loanpilot.purpose";
    public static final String PROMPT_VERSION = "loanpilot.promptVersion";
    public static final String MDC_REQUEST_ID = "requestId";

    private final PiiMasker masker;
    private final AuditLog auditLog;
    private final String defaultModel;

    public LlmGuardAdvisor(PiiMasker masker, AuditLog auditLog, String defaultModel) {
        this.masker = masker;
        this.auditLog = auditLog;
        this.defaultModel = defaultModel;
    }

    @Override
    public String getName() {
        return "LlmGuardAdvisor";
    }

    /** First in the chain, so nothing downstream (loggers, tools, the model) ever sees unmasked text. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1000;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 1 MASK
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        List<Message> messages = request.prompt().getInstructions().stream()
                .map(m -> m instanceof UserMessage user ? mask(user, counts) : m)
                .toList();
        Prompt masked = request.prompt().mutate().messages(messages).build();
        ChatClientRequest guarded = request.mutate().prompt(masked).build();
        String inputHash = sha256(messages.stream().map(m -> m.getMessageType() + ": " + m.getText())
                .collect(Collectors.joining("\n---\n")));
        Map<PiiType, Integer> maskedCounts = Collections.unmodifiableMap(counts);

        String purpose = Objects.toString(request.context().get(PURPOSE), "unspecified");
        String promptVersion = Objects.toString(request.context().get(PROMPT_VERSION), "none");
        String requestId = MDC.get(MDC_REQUEST_ID);

        // 2 CALL
        long start = System.currentTimeMillis();
        ChatClientResponse response;
        try {
            response = chain.nextCall(guarded);
        } catch (RuntimeException e) {
            auditLog.record(new LlmCallRecord(UUID.randomUUID().toString(), Instant.now(), requestId, purpose,
                    defaultModel, promptVersion, inputHash, maskedCounts, null, null, null,
                    System.currentTimeMillis() - start, e.getClass().getSimpleName()));
            throw e;
        }
        long latencyMs = System.currentTimeMillis() - start;

        // 3 AUDIT
        ChatResponse chat = response.chatResponse();
        String output = chat != null && chat.getResult() != null ? chat.getResult().getOutput().getText() : null;
        String model = chat != null && hasText(chat.getMetadata().getModel()) ? chat.getMetadata().getModel() : defaultModel;
        Usage usage = chat != null ? chat.getMetadata().getUsage() : null;
        auditLog.record(new LlmCallRecord(UUID.randomUUID().toString(), Instant.now(), requestId, purpose, model,
                promptVersion, inputHash, maskedCounts, output,
                usage != null ? usage.getPromptTokens() : null, usage != null ? usage.getCompletionTokens() : null,
                latencyMs, null));
        return response;
    }

    private UserMessage mask(UserMessage user, Map<PiiType, Integer> counts) {
        MaskResult result = masker.mask(user.getText());
        if (result.total() == 0) {
            return user;
        }
        result.counts().forEach((type, n) -> counts.merge(type, n, Integer::sum));
        return user.mutate().text(result.maskedText()).build();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
