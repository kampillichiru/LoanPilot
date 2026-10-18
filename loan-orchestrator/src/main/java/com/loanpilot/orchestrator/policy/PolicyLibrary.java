package com.loanpilot.orchestrator.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Policy clauses looked up by section number, e.g. clause("4.2").
 *
 * POC 6 SEARCHED by meaning because the user's question didn't say which clause it was about. In POC 7 the code
 * already knows: the FOIR rule IS §4.2. When you know the id, look it up; don't run a similarity search and hope
 * it ranks first. This reads the policy document itself (the source of truth), so it needs neither Qdrant nor
 * an embedding model, and can't return a stale or near-miss clause.
 */
@Component
public class PolicyLibrary {

    private final Resource document;
    private final PolicyChunker chunker;
    private volatile Map<String, PolicyChunk> clauses;

    public PolicyLibrary(@Value("${loanpilot.policy.document}") Resource document, PolicyChunker chunker) {
        this.document = document;
        this.chunker = chunker;
    }

    public Optional<PolicyChunk> clause(String section) {
        return Optional.ofNullable(all().get(section));
    }

    private Map<String, PolicyChunk> all() {
        Map<String, PolicyChunk> loaded = clauses;
        if (loaded == null) {
            try {
                String markdown = document.getContentAsString(StandardCharsets.UTF_8);
                Map<String, PolicyChunk> map = new LinkedHashMap<>();
                List<PolicyChunk> chunks = chunker.chunk(markdown, PolicyChunker.Strategy.CLAUSE, 0);
                chunks.forEach(c -> map.put(c.section(), c));
                loaded = Map.copyOf(map);
                clauses = loaded;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read policy document " + document.getDescription(), e);
            }
        }
        return loaded;
    }
}
