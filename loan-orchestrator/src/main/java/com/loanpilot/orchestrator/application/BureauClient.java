package com.loanpilot.orchestrator.application;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls mcp-credit-bureau's getCreditReport from CODE, over the same MCP connection the POC 5 agent uses.
 *
 * In POC 5 the LLM decided whether to call the tool and filled its arguments (and once invented a name). In a
 * workflow every application needs the bureau, so code calls it, always, with the applicant id it already has.
 * Same server, same tool, no LLM in between. requestId and applicantId travel as MCP _meta, as in POC 5.
 */
@Component
public class BureauClient {

    static final String TOOL = "getCreditReport";

    private final List<ToolCallbackProvider> providers;
    private final JsonMapper json;

    public BureauClient(List<ToolCallbackProvider> providers, JsonMapper json) {
        this.providers = providers;
        this.json = json;
    }

    public BureauReport creditReport(String applicantId, String requestId) {
        ToolCallback tool = findTool();
        String result;
        try {
            result = tool.call(json.writeValueAsString(Map.of("applicantId", applicantId)),
                    new ToolContext(Map.of("requestId", requestId, "applicantId", applicantId)));
        } catch (RuntimeException e) {
            throw new BureauUnavailableException("Credit bureau call failed: " + rootMessage(e), e);
        }
        return parse(result);
    }

    private ToolCallback findTool() {
        try {
            for (ToolCallbackProvider provider : providers) {
                for (ToolCallback callback : provider.getToolCallbacks()) {   // first use connects (lazy MCP client)
                    if (callback.getToolDefinition().name().endsWith(TOOL)) {
                        return callback;
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new BureauUnavailableException("Credit bureau unreachable: " + rootMessage(e), e);
        }
        throw new BureauUnavailableException("No " + TOOL + " tool found on any MCP server", null);
    }

    /** An MCP tool result is a list of content blocks; the bureau returns one text block holding the report JSON. */
    BureauReport parse(String result) {
        JsonNode root = json.readTree(result);
        JsonNode report = root.isArray() && !root.isEmpty() && root.get(0).has("text")
                ? json.readTree(root.get(0).get("text").asString())
                : root;
        return json.treeToValue(report, BureauReport.class);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }
}
