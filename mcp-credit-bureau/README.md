# mcp-credit-bureau

POC 4: a mock CIBIL credit bureau exposed as an **MCP server** (Spring AI 2.0, Streamable HTTP, port 8082).
No LLM runs here: this service only publishes tools. Clients (MCP Inspector, Claude Code, and in POC 5 the
orchestrator) bring their own LLM.

## MCP in five lines
- **Host**: the AI app the user talks to (Claude Code, later loan-orchestrator). It contains one **client** per server.
- **Server**: this app. It publishes **tools** (actions the model can call), and optionally resources and prompts.
- **Transport**: how messages travel. `stdio` = the host starts the server as a child process; **Streamable HTTP**
  = the server is a normal web service at `/mcp` (what we use: one server, many clients, deploy like any service).
- **Protocol**: JSON-RPC. `initialize` (handshake, returns server name and capabilities) → `tools/list`
  (names, descriptions, JSON schemas) → `tools/call` (run one tool with JSON arguments).
- **vs POC 3**: `@Tool` lived inside the orchestrator. `@McpTool` lives here and any MCP client can discover it.

## Run
```powershell
cd mcp-credit-bureau
.\mvnw.cmd spring-boot:run
```
Endpoint: `http://127.0.0.1:8082/mcp` (bound to localhost: there is no auth yet).

## Talk to it by hand (see the raw protocol)
```bash
H='-H Content-Type:application/json -H Accept:application/json,text/event-stream'
# 1. initialize: note the Mcp-Session-Id response header
curl -si $H http://127.0.0.1:8082/mcp -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
# 2. tell the server we're ready, then list tools
curl -s $H -H "Mcp-Session-Id: <id>" http://127.0.0.1:8082/mcp -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
curl -s $H -H "Mcp-Session-Id: <id>" http://127.0.0.1:8082/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

## Test with MCP Inspector
```powershell
npx @modelcontextprotocol/inspector
```
In the browser UI: Transport **Streamable HTTP**, URL `http://127.0.0.1:8082/mcp`, **Connect** → **Tools** →
**List Tools** → pick `getCreditReport` → fill the arguments → **Run Tool**. The History panel shows every JSON-RPC message.

## Connect Claude Code
```powershell
claude mcp add --transport http credit-bureau http://127.0.0.1:8082/mcp
claude mcp list            # should show credit-bureau as connected
```
Then in Claude Code: *"Use the credit-bureau tool to get Priya's credit report."* Remove it with
`claude mcp remove credit-bureau`.

**Rule 2 reminder:** Claude Code is a cloud LLM. Everything `getCreditReport` returns is sent to Anthropic.
That's fine for this synthetic data; design the output as if it were real (no PAN).

## Mock data (`BureauRepository`, all synthetic)
| Id | Name | CIBIL | Active accts | Existing EMI | 90+ DPD | Enquiries 6m | Path |
|---|---|---|---|---|---|---|---|
| APP-001 | Priya Sharma | 762 | 2 | 20,000 | 0 | 1 | Happy path |
| APP-002 | Rahul Verma | 641 | 4 | 18,000 | 1 | 5 | Low score, red flags |
| APP-003 | Arjun Nair | none | 0 | 0 | 0 | 0 | No credit history |
| APP-004 | Sneha Reddy | 805 | 1 | 12,500 | 0 | 0 | Excellent |
| APP-005 | Karan Mehta | 698 | 3 | 26,000 | 0 | 7 | Borderline, credit hungry |

Priya, Rahul and Arjun match loan-orchestrator's mock data, so POC 5 gets the same answers over MCP.
