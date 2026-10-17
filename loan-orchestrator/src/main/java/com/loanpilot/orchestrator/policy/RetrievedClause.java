package com.loanpilot.orchestrator.policy;

/**
 * One chunk returned by similarity search.
 *
 * @param score     cosine similarity between the question and the chunk: 1.0 = same meaning, ~0 = unrelated
 * @param sections  every clause in the chunk, comma-separated (more than one only with FIXED chunking)
 * @param text      the chunk text the LLM reads. Null in /ask responses to keep them short; see /search
 */
public record RetrievedClause(
        String section,
        String sections,
        String title,
        double score,
        String text) {
}
