package com.loanpilot.orchestrator.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of recent LLM calls. The full history is the JSONL file (loanpilot.audit.file). */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLog auditLog;

    public AuditController(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** GET /api/audit?requestId=ab12cd34 for one application, or GET /api/audit?limit=20 for the latest calls. */
    @GetMapping
    public List<LlmCallRecord> calls(@RequestParam(required = false) String requestId,
                                     @RequestParam(defaultValue = "20") int limit) {
        return requestId != null ? auditLog.find(requestId) : auditLog.recent(Math.min(limit, 200));
    }
}
