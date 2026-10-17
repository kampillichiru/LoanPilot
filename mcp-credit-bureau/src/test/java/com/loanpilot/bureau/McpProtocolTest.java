package com.loanpilot.bureau;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End to end over the wire: a real MCP client talks Streamable HTTP to the running server, exactly like
 * MCP Inspector or Claude Code would (initialize → tools/list → tools/call).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class McpProtocolTest {

    @LocalServerPort
    int port;

    McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/mcp")
                        .build())
                .build();
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    @Test
    void handshakeIdentifiesTheServer() {
        assertThat(client.getServerInfo().name()).isEqualTo("credit-bureau");
        assertThat(client.getServerInstructions()).contains("does not make lending decisions");
    }

    @Test
    void publishesGetCreditReportWithSchemaAndNoPanParameter() {
        McpSchema.Tool tool = client.listTools().tools().stream()
                .filter(t -> t.name().equals("getCreditReport"))
                .findFirst()
                .orElseThrow();

        assertThat(tool.description()).contains("CIBIL score");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        assertThat(properties).containsOnlyKeys("applicantId", "applicantName");
        assertThat(tool.annotations().readOnlyHint()).isTrue();
    }

    @Test
    void callReturnsPriyasReport() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("getCreditReport", Map.of("applicantName", "Priya")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"cibilScore\":762").contains("\"status\":\"FOUND\"").doesNotContain("ZZZPZ");
    }

    @Test
    void metaApplicantIdTravelsOverTheWireAndWins() {
        // What Spring AI's MCP client does with the orchestrator's ToolContext: it becomes the request's _meta.
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("getCreditReport",
                Map.of("applicantName", "Priya"), Map.of("applicantId", "APP-002", "requestId", "test-1")));

        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("\"applicantId\":\"APP-002\"");
    }

    @Test
    void callForUnknownApplicantIsNotFoundNotAnError() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("getCreditReport", Map.of("applicantId", "APP-999")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("NOT_FOUND");
    }
}
