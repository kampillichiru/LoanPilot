package com.loanpilot.orchestrator.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Test double: keeps records in a list. */
public class InMemoryAuditLog implements AuditLog {

    public final List<LlmCallRecord> records = new ArrayList<>();

    @Override
    public void record(LlmCallRecord record) {
        records.add(record);
    }

    @Override
    public List<LlmCallRecord> find(String requestId) {
        return records.stream().filter(r -> Objects.equals(r.requestId(), requestId)).toList();
    }

    @Override
    public List<LlmCallRecord> recent(int limit) {
        return records.reversed().stream().limit(limit).toList();
    }
}
