package com.loanpilot.orchestrator.policy;

import java.util.List;

/**
 * @param chunks     how many vectors were written (the old chunks of this docId are deleted first)
 * @param sections   the clause each chunk starts in, in order. Duplicates are normal for FIXED chunking
 */
public record IngestResponse(
        String docId,
        PolicyChunker.Strategy strategy,
        Integer chunkSize,
        int chunks,
        List<String> sections,
        long latencyMs) {
}
