package com.loanpilot.orchestrator.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ByteArrayResource;

/**
 * Ingest + retrieve + guards, with an in-memory vector store and a fake embedding model: no Qdrant, no Ollama.
 * Retrieval QUALITY with the real embedding model is measured by evals/run_policy_rag.py, not here.
 */
class PolicyServiceTest {

    /**
     * Bag-of-words embedding: each word adds 1 to one of 64 slots. Texts sharing words point the same way, so
     * cosine similarity behaves like a (crude) relevance score. Enough to test the plumbing deterministically.
     */
    static class WordHashEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> out = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                out.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        static float[] vector(String text) {
            float[] v = new float[64];
            for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (w.length() > 2) {
                    v[Math.floorMod(w.hashCode(), 64)] += 1;
                }
            }
            return v;
        }
    }

    static class FakeChatModel implements ChatModel {
        String reply = "For salaried applicants FOIR must not exceed 50% (§4.2).";
        Prompt lastPrompt;
        int calls;

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            lastPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().build();
        }
    }

    private final SimpleVectorStore store = SimpleVectorStore.builder(new WordHashEmbeddingModel()).build();
    private final FakeChatModel chat = new FakeChatModel();
    private final PolicyIngestService ingest = new PolicyIngestService(store, () -> { }, new PolicyChunker(),
            new ByteArrayResource(PolicyChunkerTest.POLICY.getBytes()), "test-policy");
    private final PolicyService service = new PolicyService(ChatClient.builder(chat), store,
            new ByteArrayResource("Answer from the clauses only.".getBytes()), "policy-qa-test", "qwen2.5:7b",
            "test-policy", 2, 0.3, 0.0, 42);

    @Test
    void reIngestReplacesChunksInsteadOfDuplicatingThem() {
        ingest.ingest(PolicyChunker.Strategy.CLAUSE, 0);
        ingest.ingest(PolicyChunker.Strategy.CLAUSE, 0);

        List<RetrievedClause> all = service.search(new PolicyAskRequest("FOIR salaried interest rate", 20, 0.0));
        assertThat(all).extracting(RetrievedClause::section).containsExactlyInAnyOrder("4.2", "4.3", "6.1");
    }

    @Test
    void switchingStrategyRemovesTheOldStrategysChunks() {
        ingest.ingest(PolicyChunker.Strategy.FIXED, 100);
        IngestResponse clause = ingest.ingest(PolicyChunker.Strategy.CLAUSE, 0);

        List<RetrievedClause> all = service.search(new PolicyAskRequest("FOIR salaried interest rate", 50, 0.0));
        assertThat(all).hasSize(clause.chunks());
    }

    @Test
    void asksTheModelWithRetrievedClausesAndChecksCitations() {
        ingest.ingest(PolicyChunker.Strategy.CLAUSE, 0);

        PolicyAnswer answer = service.ask(new PolicyAskRequest("Maximum FOIR for salaried applicants?", null, null));

        assertThat(answer.llmCalled()).isTrue();
        assertThat(answer.retrieved().getFirst().section()).isEqualTo("4.2");
        assertThat(answer.citations()).containsExactly("4.2");
        assertThat(answer.citationCheck()).isEqualTo("OK");
        assertThat(answer.promptVersion()).isEqualTo("policy-qa-test");
        // the clause text, labelled with its section, is in the prompt; the system prompt is the versioned file
        assertThat(chat.lastPrompt.getUserMessage().getText())
                .contains("[§4.2]")
                .contains("FOIR must not exceed 50%")
                .endsWith("Question: Maximum FOIR for salaried applicants?");
        assertThat(chat.lastPrompt.getSystemMessage().getText()).isEqualTo("Answer from the clauses only.");
    }

    @Test
    void citationOfAClauseThatWasNotRetrievedIsFlagged() {
        ingest.ingest(PolicyChunker.Strategy.CLAUSE, 0);
        chat.reply = "FOIR is capped at 50% (§4.2), see also §9.9.";

        PolicyAnswer answer = service.ask(new PolicyAskRequest("Maximum FOIR for salaried applicants?", null, null));

        assertThat(answer.unsupportedCitations()).containsExactly("9.9");
        assertThat(answer.citationCheck()).isEqualTo("UNSUPPORTED_CITATION");
    }

    @Test
    void nothingSimilarEnoughMeansCodeAnswersWithoutCallingTheModel() {
        ingest.ingest(PolicyChunker.Strategy.CLAUSE, 0);

        PolicyAnswer answer = service.ask(new PolicyAskRequest("Write a poem about cricket", null, 0.9));

        assertThat(chat.calls).isZero();
        assertThat(answer.llmCalled()).isFalse();
        assertThat(answer.answer()).isEqualTo(PolicyService.NOT_COVERED);
        assertThat(answer.retrieved()).isEmpty();
    }

    @Test
    void citationParsingAcceptsCommonForms() {
        assertThat(PolicyService.citations("See §4.2, § 4.5, Section 4.7 and clause 6.1; not 50.5% or v1.2"))
                .containsExactly("4.2", "4.5", "4.7", "6.1");
    }
}
