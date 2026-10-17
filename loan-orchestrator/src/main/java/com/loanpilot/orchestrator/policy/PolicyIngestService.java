package com.loanpilot.orchestrator.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Ingestion pipeline: policy markdown -> chunks -> embeddings (nomic-embed-text, via VectorStore.add) -> Qdrant.
 *
 * Re-runnable: the previous chunks of this docId are deleted first, so switching chunk strategy or editing the
 * policy never leaves stale or duplicate vectors behind. Point ids are derived from docId + chunk position, so
 * the same input always produces the same ids.
 */
@Service
public class PolicyIngestService {

    private static final Logger log = LoggerFactory.getLogger(PolicyIngestService.class);

    private final VectorStore vectorStore;
    private final VectorCollection collection;
    private final PolicyChunker chunker;
    private final Resource document;
    private final String docId;

    public PolicyIngestService(VectorStore vectorStore,
                               VectorCollection collection,
                               PolicyChunker chunker,
                               @Value("${loanpilot.policy.document}") Resource document,
                               @Value("${loanpilot.policy.doc-id}") String docId) {
        this.vectorStore = vectorStore;
        this.collection = collection;
        this.chunker = chunker;
        this.document = document;
        this.docId = docId;
    }

    public IngestResponse ingest(PolicyChunker.Strategy strategy, int chunkSize) {
        long start = System.currentTimeMillis();
        String markdown = read();
        List<PolicyChunk> chunks = chunker.chunk(markdown, strategy, chunkSize);
        log.info("[ingest {}] 1/3 Chunked with {}: {} chunk(s)", docId, strategy, chunks.size());

        List<Document> documents = IntStream.range(0, chunks.size())
                .mapToObj(i -> toDocument(chunks.get(i), i, strategy))
                .toList();

        try {
            collection.ensureExists();
            vectorStore.delete("docId == '" + docId + "'");
            log.info("[ingest {}] 2/3 Old chunks deleted. Embedding {} chunk(s) with the embedding model...",
                    docId, documents.size());
            vectorStore.add(documents);   // embeds every chunk's text, then writes vector + text + metadata
        } catch (PolicyIndexUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PolicyIndexUnavailableException("Writing to the vector store failed: " + e.getMessage(), e);
        }

        long latencyMs = System.currentTimeMillis() - start;
        log.info("[ingest {}] 3/3 Stored {} vector(s) in {} ms", docId, documents.size(), latencyMs);
        return new IngestResponse(docId, strategy, strategy == PolicyChunker.Strategy.FIXED ? chunkSize : null,
                documents.size(), chunks.stream().map(PolicyChunk::section).toList(), latencyMs);
    }

    private Document toDocument(PolicyChunk chunk, int index, PolicyChunker.Strategy strategy) {
        // Qdrant point ids must be UUIDs. Name-based UUID = same docId + position -> same id on every run.
        String id = UUID.nameUUIDFromBytes((docId + "#" + strategy + "#" + index).getBytes(StandardCharsets.UTF_8)).toString();
        return Document.builder()
                .id(id)
                .text(chunk.text())
                .metadata(Map.of(
                        "docId", docId,
                        "section", chunk.section(),
                        "sections", String.join(",", chunk.sections()),
                        "title", chunk.title(),
                        "strategy", strategy.name(),
                        "chunkIndex", index))
                .build();
    }

    private String read() {
        try {
            return document.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read policy document " + document.getDescription()
                    + ". Run the app from the loan-orchestrator folder, or fix loanpilot.policy.document", e);
        }
    }
}
