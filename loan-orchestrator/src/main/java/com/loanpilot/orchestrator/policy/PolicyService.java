package com.loanpilot.orchestrator.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import static org.springframework.util.StringUtils.hasText;

/**
 * RAG over the credit policy: question -> embed -> nearest clauses in Qdrant -> LLM answers from those clauses only.
 *
 * Retrieval is done explicitly here rather than with Spring AI's QuestionAnswerAdvisor (which does the same thing
 * inside the ChatClient call), so that:
 * - the retrieved clauses and their scores are returned and audited (rule 3),
 * - code can refuse BEFORE calling the LLM when nothing is similar enough (guard 1),
 * - code can check that every cited section was actually retrieved (guard 2).
 *
 * The policy text is not PII, so retrieved sections and scores are logged at INFO. The question is not.
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    static final String NOT_COVERED = "This is not covered by the credit policy: no clause matched the question "
            + "closely enough. Ask a credit officer.";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final Resource systemPrompt;
    private final String promptVersion;
    private final String defaultModel;
    private final String docId;
    private final int defaultTopK;
    private final double defaultMinScore;
    private final double temperature;
    private final int seed;

    public PolicyService(ChatClient.Builder builder,
                         VectorStore vectorStore,
                         @Value("${loanpilot.policy.prompt}") Resource systemPrompt,
                         @Value("${loanpilot.policy.prompt-version}") String promptVersion,
                         @Value("${spring.ai.ollama.chat.model}") String defaultModel,
                         @Value("${loanpilot.policy.doc-id}") String docId,
                         @Value("${loanpilot.policy.top-k}") int defaultTopK,
                         @Value("${loanpilot.policy.min-score}") double defaultMinScore,
                         @Value("${loanpilot.policy.temperature}") double temperature,
                         @Value("${loanpilot.policy.seed}") int seed) {
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
        this.vectorStore = vectorStore;
        this.systemPrompt = systemPrompt;
        this.promptVersion = promptVersion;
        this.defaultModel = defaultModel;
        this.docId = docId;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
        this.temperature = temperature;
        this.seed = seed;
    }

    /** Retrieval only, no LLM: fast, for tuning chunking / topK / minScore. */
    public List<RetrievedClause> search(PolicyAskRequest request) {
        return retrieve(request, newId());
    }

    public PolicyAnswer ask(PolicyAskRequest request) {
        String id = newId();
        long start = System.currentTimeMillis();
        List<RetrievedClause> retrieved = retrieve(request, id);
        long retrievalMs = System.currentTimeMillis() - start;
        String questionHash = sha256(request.question());

        // Guard 1: nothing similar enough. Code answers; the LLM is never asked, so it can't improvise.
        if (retrieved.isEmpty()) {
            log.info("[policy {}] No clause above the similarity threshold: answering 'not covered' without the LLM", id);
            return new PolicyAnswer(NOT_COVERED, List.of(), List.of(), "NO_CITATION", List.of(), false,
                    null, promptVersion, questionHash, null, null, retrievalMs, 0);
        }

        OllamaChatOptions.Builder options = OllamaChatOptions.builder().temperature(temperature).seed(seed);
        log.info("[policy {}] Calling LLM with {} clause(s): model={}, prompt={}. Waiting...",
                id, retrieved.size(), defaultModel, promptVersion);
        long llmStart = System.currentTimeMillis();
        ChatResponse response = chatClient.prompt()
                .options(options)
                .system(systemPrompt)
                .user(userMessage(request.question(), retrieved))
                .call()
                .chatResponse();
        long llmMs = System.currentTimeMillis() - llmStart;

        String answer = response.getResult().getOutput().getText();
        if (!hasText(answer)) {
            answer = "No answer: the model returned an empty reply. The retrieved clauses are listed below.";
        }
        String model = hasText(response.getMetadata().getModel()) ? response.getMetadata().getModel() : defaultModel;
        Usage usage = response.getMetadata().getUsage();

        // Guard 2: every cited section must be one the model was actually shown.
        List<String> citations = citations(answer);
        Set<String> shown = new LinkedHashSet<>();
        retrieved.forEach(r -> shown.addAll(List.of(r.sections().split(","))));
        List<String> unsupported = citations.stream().filter(c -> !shown.contains(c)).toList();
        String check = !unsupported.isEmpty() ? "UNSUPPORTED_CITATION" : citations.isEmpty() ? "NO_CITATION" : "OK";
        if (!unsupported.isEmpty()) {
            log.warn("[policy {}] Answer cites {} which was not retrieved: possible invented citation", id, unsupported);
        }
        log.info("[policy {}] LLM replied in {} ms: tokens in/out={}/{}, citations={}, check={}", id, llmMs,
                usage != null ? usage.getPromptTokens() : null, usage != null ? usage.getCompletionTokens() : null,
                citations, check);

        List<RetrievedClause> withoutText = retrieved.stream()
                .map(r -> new RetrievedClause(r.section(), r.sections(), r.title(), r.score(), null))
                .toList();
        return new PolicyAnswer(answer, citations, unsupported, check, withoutText, true, model, promptVersion,
                questionHash, usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null, retrievalMs, llmMs);
    }

    private List<RetrievedClause> retrieve(PolicyAskRequest request, String id) {
        int topK = request.topK() != null ? request.topK() : defaultTopK;
        double minScore = request.minScore() != null ? request.minScore() : defaultMinScore;
        List<Document> docs;
        try {
            docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(request.question())          // embedded with the same model as the chunks
                    .topK(topK)
                    .similarityThreshold(minScore)
                    .filterExpression("docId == '" + docId + "'")
                    .build());
        } catch (RuntimeException e) {
            throw new PolicyIndexUnavailableException("Policy search failed: " + e.getMessage(), e);
        }
        List<RetrievedClause> clauses = docs.stream()
                .map(d -> new RetrievedClause(
                        String.valueOf(d.getMetadata().get("section")),
                        String.valueOf(d.getMetadata().getOrDefault("sections", d.getMetadata().get("section"))),
                        String.valueOf(d.getMetadata().get("title")),
                        d.getScore() != null ? Math.round(d.getScore() * 1000) / 1000.0 : 0.0,
                        d.getText()))
                .toList();
        log.info("[policy {}] Retrieved {} clause(s) (topK={}, minScore={}): {}", id, clauses.size(), topK, minScore,
                clauses.stream().map(c -> "§" + c.section() + "=" + c.score()).toList());
        return clauses;
    }

    /** The clauses go in the user message, each labelled with its section so the model can cite it. */
    static String userMessage(String question, List<RetrievedClause> clauses) {
        StringBuilder sb = new StringBuilder("Policy clauses:\n\n");
        for (RetrievedClause c : clauses) {
            sb.append("[§").append(c.sections().replace(",", ", §")).append("]\n").append(c.text()).append("\n\n");
        }
        return sb.append("Question: ").append(question).toString();
    }

    static List<String> citations(String answer) {
        return Citations.find(answer);
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
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
