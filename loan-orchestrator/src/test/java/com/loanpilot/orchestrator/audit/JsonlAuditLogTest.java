package com.loanpilot.orchestrator.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.loanpilot.orchestrator.privacy.PiiType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class JsonlAuditLogTest {

    @TempDir
    Path dir;

    private static LlmCallRecord call(String requestId, String purpose) {
        return new LlmCallRecord("id-" + purpose, Instant.parse("2026-10-18T10:00:00Z"), requestId, purpose,
                "qwen2.5:7b", "credit-memo-v3", "abc123", Map.of(PiiType.PAN, 1), "output", 100, 20, 1500, null);
    }

    @Test
    void appendsOneJsonLinePerCallAndFindsThemByRequest() throws Exception {
        Path file = dir.resolve("audit/llm-calls.jsonl");
        JsonlAuditLog log = new JsonlAuditLog(file, JsonMapper.builder().build());

        log.record(call("app1", "slip-extraction"));
        log.record(call("app1", "credit-memo"));
        log.record(call("app2", "ask"));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("\"purpose\":\"slip-extraction\"", "\"promptVersion\":\"credit-memo-v3\"",
                "\"inputSha256\":\"abc123\"", "\"PAN\":1");
        assertThat(log.find("app1")).extracting(LlmCallRecord::purpose).containsExactly("slip-extraction", "credit-memo");
        assertThat(log.recent(2)).extracting(LlmCallRecord::purpose).containsExactly("ask", "credit-memo");
    }

    @Test
    void failsClosedWhenTheRecordCannotBeWritten() throws Exception {
        Path blocker = Files.createFile(dir.resolve("not-a-directory"));
        JsonlAuditLog log = new JsonlAuditLog(blocker.resolve("llm-calls.jsonl"), JsonMapper.builder().build());

        assertThatThrownBy(() -> log.record(call("app1", "ask")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("LLM output not used");
        assertThat(log.find("app1")).isEmpty();
    }
}
