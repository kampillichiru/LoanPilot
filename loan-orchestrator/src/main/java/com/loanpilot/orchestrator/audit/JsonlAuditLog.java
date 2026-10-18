package com.loanpilot.orchestrator.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only JSON Lines file: one LLM call per line. Append-only on purpose: an audit trail you can edit is not an
 * audit trail. The last 1,000 records are also kept in memory so a response can show its own calls.
 *
 * Fail closed: if the line can't be written, record() throws and the request fails. An LLM output we can't account
 * for is not used. (The trade-off: a full disk stops lending. For a bank that's the right way round.)
 */
@Component
public class JsonlAuditLog implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger(JsonlAuditLog.class);
    private static final int IN_MEMORY = 1000;

    private final Path file;
    private final JsonMapper json;
    private final Deque<LlmCallRecord> recent = new ArrayDeque<>();

    public JsonlAuditLog(@Value("${loanpilot.audit.file}") Path file, JsonMapper json) {
        this.file = file.toAbsolutePath();
        this.json = json;
        log.info("LLM audit log: {}", this.file);
    }

    @Override
    public synchronized void record(LlmCallRecord record) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(record) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Audit record could not be written to " + file + ": LLM output not used", e);
        }
        recent.addFirst(record);
        if (recent.size() > IN_MEMORY) {
            recent.removeLast();
        }
    }

    @Override
    public synchronized List<LlmCallRecord> find(String requestId) {
        List<LlmCallRecord> found = new ArrayList<>();
        recent.descendingIterator().forEachRemaining(r -> {
            if (Objects.equals(r.requestId(), requestId)) {
                found.add(r);
            }
        });
        return found;
    }

    @Override
    public synchronized List<LlmCallRecord> recent(int limit) {
        return recent.stream().limit(limit).toList();
    }
}
