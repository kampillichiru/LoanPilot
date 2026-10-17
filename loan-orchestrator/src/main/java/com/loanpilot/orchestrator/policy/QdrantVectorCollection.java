package com.loanpilot.orchestrator.policy;

import java.util.concurrent.ExecutionException;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Creates the Qdrant collection on first ingest instead of at startup (spring.ai.vectorstore.qdrant.initialize-schema
 * is false). Same idea as the lazy MCP connection in POC 5: the orchestrator starts, and POC 1-5 keep working,
 * even when Qdrant isn't running.
 *
 * A collection is fixed at creation by two choices:
 * - vector size: must equal the embedding model's output (nomic-embed-text = 768 numbers). Switch to a model with
 *   a different size and you must delete the collection and re-ingest.
 * - distance: Cosine = compare the direction of two vectors, ignoring length. The standard for text embeddings.
 */
@Component
public class QdrantVectorCollection implements VectorCollection {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorCollection.class);

    private final QdrantClient qdrant;
    private final EmbeddingModel embeddingModel;
    private final String collection;

    public QdrantVectorCollection(QdrantClient qdrant, EmbeddingModel embeddingModel,
                                  @Value("${spring.ai.vectorstore.qdrant.collection-name}") String collection) {
        this.qdrant = qdrant;
        this.embeddingModel = embeddingModel;
        this.collection = collection;
    }

    @Override
    public void ensureExists() {
        try {
            if (Boolean.TRUE.equals(qdrant.collectionExistsAsync(collection).get())) {
                return;
            }
            int size = embeddingModel.dimensions();   // embeds a test string once to find out
            qdrant.createCollectionAsync(collection, VectorParams.newBuilder()
                    .setSize(size)
                    .setDistance(Distance.Cosine)
                    .build()).get();
            log.info("Created Qdrant collection '{}': {} dimensions, cosine distance", collection, size);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PolicyIndexUnavailableException("Interrupted while preparing Qdrant collection", e);
        } catch (ExecutionException e) {
            throw new PolicyIndexUnavailableException("Cannot reach Qdrant to prepare collection '" + collection + "'", e);
        }
    }
}
