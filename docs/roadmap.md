# Roadmap: 8 mini-POCs

Each POC is about a weekend. Finish one, write `LEARNINGS.md`, then move on.

### POC 1: Talk to a local LLM from Java
- **Build:** Spring Boot + Spring AI + Ollama, endpoint `/api/ask`, system prompt from a versioned file.
- **Learn:** system vs user prompt, temperature, tokens, latency.
- **Experiments:** same question at temperature 0.0 vs 1.2; remove the system prompt; ask something off-topic.
- **Done when:** you can explain why answers change between calls.

### POC 2: Structured extraction
- **Build:** POST salary-slip text → `SalarySlip` Java record (via `.entity(SalarySlip.class)`), with validation.
- **Learn:** structured output, schema prompting, how/why extraction fails.
- **Done when:** 10 synthetic slips extract correctly; one failure found and fixed.

### POC 3: Tool calling
- **Build:** `@Tool calculateEmi(...)`, `@Tool getCibilScore(pan)` (mock).
- **Learn:** the agent loop: model asks for a tool → your code runs → model answers.
- **Done when:** "Can Priya afford 5L for 3 years?" triggers both tools.

### POC 4: First MCP server
- **Build:** `mcp-credit-bureau` (port 8082) exposing `getCreditReport`.
- **Learn:** MCP server, HTTP transport, tool schemas.
- **Done when:** MCP Inspector *and* Claude Code can call it.

### POC 5: MCP client
- **Build:** orchestrator consumes bureau tools over MCP instead of local `@Tool`.
- **Learn:** tool discovery, decoupling AI from services.
- **Done when:** POC 3's question works with tools coming over MCP.

### POC 6: RAG over credit policy
- **Build:** write a 3–4 page policy in `policy-docs/`, chunk by clause, embed with `nomic-embed-text`, store in Qdrant, search with citations.
- **Learn:** embeddings, chunking strategies, similarity search, grounding.
- **Done when:** "What is the max FOIR?" returns §4.2 with citation.

### POC 7: Decision engine + workflow
- **Build:** pure-Java rules (EMI, FOIR, CIBIL, max loan) with JUnit tests; agent gathers facts → rules decide → LLM writes memo.
- **Learn:** where AI belongs and where it doesn't.
- **Done when:** one API call takes Priya from application to credit memo.

### POC 8: Guardrails + evals
- **Build:** PII masker, audit log, 20 synthetic applicants with expected outcomes, Python eval report.
- **Learn:** AI testing, faithfulness, safety.
- **Done when:** you have a report with extraction %, decision %, memo faithfulness %.
