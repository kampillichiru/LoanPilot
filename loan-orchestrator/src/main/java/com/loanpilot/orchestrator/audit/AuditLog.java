package com.loanpilot.orchestrator.audit;

import java.util.List;

/** Where LLM calls are recorded. A file for the POC; a database table (docs/architecture.md, audit_log) later. */
public interface AuditLog {

    /** Must not lose records silently: throws when the record can't be written (fail closed). */
    void record(LlmCallRecord record);

    /** All calls for one request, oldest first. */
    List<LlmCallRecord> find(String requestId);

    /** The most recent calls, newest first. */
    List<LlmCallRecord> recent(int limit);
}
