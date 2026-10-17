# loan-orchestrator

## POC 1: Talk to a local LLM from Java

### What's here

| File | Purpose |
|------|---------|
| `ask/AskController.java` | REST endpoint `GET/POST /api/ask` |
| `ask/AskService.java` | Calls the LLM through Spring AI `ChatClient`; returns answer + model, tokens, latency |
| `resources/prompts/banking-assistant-v1.st` | **Versioned system prompt**: the model's instructions |
| `resources/application.properties` | Ollama URL, model, default temperature |
| `ask/LlmErrorHandler.java` | Clear 503 when Ollama isn't running |
| `test/.../AskServiceTest.java` | Unit test with a fake model (no Ollama needed) |
| `requests.http` | Ready-made requests and experiments |

### Request flow

```
curl / browser ──▶ AskController ──▶ AskService ──▶ ChatClient (Spring AI)
                                                       │  system prompt + user question
                                                       │  + options (temperature, seed, topK)
                                                       ▼
                                                 Ollama :11434  (qwen2.5:7b)
                                                       │
   JSON { answer, model, promptVersion, tokens, latencyMs } ◀──┘
```

### Run it

1. Install Ollama: https://ollama.com/download, then in a new terminal:
   ```
   ollama pull qwen2.5:7b
   ollama run qwen2.5:7b "What is FOIR?"
   ```
2. Run the tests (no Ollama needed):
   ```
   mvnw.cmd test
   ```
3. Start the app:
   ```
   mvnw.cmd spring-boot:run
   ```
   (or run `LoanOrchestratorApplication` from IntelliJ)
4. Try it:
   ```
   curl "http://localhost:8080/api/ask?q=What%20is%20FOIR"
   ```
   Or open `requests.http` in IntelliJ and run the experiments.

### Experiments (this is where the learning is)

1. **Temperature:** run request #3 three times, then #4 three times. What changes?
2. **System prompt:** compare #5 and #6. What does the system prompt actually do?
3. **Maths:** check #2 by hand (answer should be ₹16,607). Is the LLM reliable at calculations?
   → this is why POC 3 adds a Java `calculateEmi` **tool**.
4. **Tokens & latency:** note `promptTokens`, `completionTokens`, `latencyMs`. How does a longer
   system prompt change them?
5. **Repeatability:** run #8 (temperature 0, seed 42, topK 1) three times, then #9 (no seed).
   Which one gives the identical answer every time? Same seed is only repeatable on the same
   model build, Ollama version and hardware.
6. **Prompt versioning:** copy the prompt to `banking-assistant-v2.st`, change it, switch
   `loanpilot.prompt.system` / `loanpilot.prompt.version`, and compare answers.

Write what you found in `../LEARNINGS.md`.

---

## POC 2: Structured extraction

Salary-slip text → `SalarySlip` record → Bean Validation. The LLM is a **parser** here: its output
feeds later rules (FOIR, max loan), so it must fail loudly, never silently.

### What's here

| File | Purpose | Who writes it |
|------|---------|---------------|
| `extract/SalarySlip.java` | The record the model must fill. Its fields become the JSON schema | **you** (stub now) |
| `resources/prompts/extract-salary-slip-v1.st` | Extraction instructions (system prompt) | **you** (TODO now) |
| `extract/ExtractService.java` | Builds prompt + schema, calls Ollama, parses, validates, keeps raw output | scaffold |
| `extract/ExtractController.java` | `POST /api/extract/salary-slip` (JSON or text/plain). 200 valid, 422 not | scaffold |
| `test/.../ExtractServiceTest.java` | Plumbing tests with a fake model | scaffold |
| `../data-generator/salary_slips.py` | 10 synthetic slips + `expected.json` answer key | scaffold |
| `../evals/run_salary_slip_extraction.py` | Scores every slip, field by field | scaffold |

### Request flow

```
slip text ──▶ ExtractService
                │ system: extract-salary-slip-v1.st
                │ user:   slip text + JSON schema (generated from SalarySlip)
                │ options: temperature 0, seed 42, [format = schema if nativeSchema]
                ▼
              Ollama ──▶ raw text ──▶ BeanOutputConverter ──▶ SalarySlip ──▶ Validator
                                          │ parse fails                         │ violations
                                          ▼                                     ▼
                              422 + rawOutput + errors          422 + slip + errors     else 200
```

### Your tasks

1. **Design `SalarySlip`.** Match the answer-key names (`employeeName`, `employerName`, `payPeriod`,
   `grossEarnings`, `totalDeductions`, `netPay`). Choose types (Long vs BigDecimal, String vs YearMonth)
   and add validation, including a cross-field `@AssertTrue` (gross − deductions = net).
2. **Write `extract-salary-slip-v1.st`.** No `{ }` in it: the schema is appended by code.
3. Run `mvnw.cmd test` and fix `ExtractServiceTest` for your new record (it uses the stub's two fields).
4. Start the app, then `python ../evals/run_salary_slip_extraction.py`. Goal: 10/10.
5. **Find one failure and fix it** (roadmap "done when"). Copy the prompt to `-v2.st` rather than editing
   v1, so each eval result maps to one prompt version.

### Experiments

1. **Schema in prompt vs native:** run the eval with and without `--native-schema`. Which failures
   disappear? Which don't? (A schema guarantees *shape*, not *correct values*.)
2. **Field descriptions:** add `@JsonPropertyDescription` to a field and look at the schema in the
   prompt. Does slip-10 (YTD column) improve?
3. **Validation as a safety net:** remove the `@AssertTrue` and rerun. Which wrong extractions now
   come back as 200 "valid"?
4. **Arithmetic:** slip-09 has no Total Deductions line. Should the model add it up, or should Java
   compute it? (Hint: rule 1.)

---

## POC 3: Tool calling

`/api/advise` runs the agent loop: the LLM turns a free-text question into tool requests, Java runs the
tools, the LLM explains the results. It plans and explains; Java computes and (from POC 7) decides.

| File | Purpose |
|------|---------|
| `advise/LoanTools.java` | Local `@Tool` methods: `calculateEmi` (BigDecimal, validated) |
| `advise/AdviseService.java` | The agent loop: system prompt, local + MCP tools, ToolContext, empty-answer guard |
| `advise/RecordingToolCallback.java` | Wraps every tool so `toolCalls` in the response shows name, args, result, error, timing |
| `resources/prompts/loan-advisor-v*.st` | v1 baseline; v2 "affordability needs both tools"; v3 uses `getCreditReport` (POC 5) |

Try `requests.http` #14–20. Read `toolCalls` first: it shows which numbers came from Java and which from the model.

---

## POC 5: MCP client

The orchestrator no longer owns credit data. `getCreditReport` is discovered from `mcp-credit-bureau` over
MCP and offered to the model next to the local `calculateEmi`; the model can't tell local from remote.

```
question ─► LLM ─► calculateEmi        (local @Tool, in-process)
               └─► getCreditReport     (MCP tools/call ─► mcp-credit-bureau :8082)
```

**Run both:** start `mcp-credit-bureau` first (`..\mcp-credit-bureau\mvnw.cmd spring-boot:run`), then this app.
Config: `spring.ai.mcp.client.streamable-http.connections.credit-bureau.*` in `application.properties`.

| Behaviour | How |
|-----------|-----|
| Starts even if the bureau is down | `spring.ai.mcp.client.initialized=false`: connect on first use |
| Bureau down at discovery | `getCreditReport` left out, `remoteToolsError` explains, answer says data unavailable |
| Bureau dies after discovery | The tool call fails, the model is told, `toolCalls[].error` records it |
| Bureau comes back | Picked up on the next request, no restart |
| Code-supplied `applicantId` | Sent as MCP `_meta` (trusted, wins at the bureau) and mentioned to the model so it doesn't invent a name |
| One question across two services | The same `[advise <id>]` / `[request <id>]` id appears in both logs |

Try `requests.http` #21–23 (#23: stop the bureau first).

---

## POC 6: RAG over the credit policy

The model never saw LoanPilot Bank's policy in training (POC 1 invented a meaning for FOIR). RAG fixes that:
**find** the relevant clauses first, then let the model answer **only from them**, citing each one.

```
INGEST (once, and after every policy edit)
credit-policy-v1.md ─► PolicyChunker ─► 30 clauses ─► nomic-embed-text ─► Qdrant "credit-policy"
                       (one per §)      (+ breadcrumb)   (768 numbers each)  (vector + text + section)

ASK (every request)
question ─► embed ─► Qdrant: top 3 clauses with cosine ≥ 0.5 ─┬─ none? ─► "not covered" (code, no LLM)   guard 1
                                                                └─► LLM: clauses + question ─► answer + §citations
                                                                        └─► every § cited must be retrieved          guard 2
```

### What's here

| File | Purpose |
|------|---------|
| `../policy-docs/credit-policy-v1.md` | Synthetic credit policy, 30 numbered clauses |
| `policy/PolicyChunker.java` | Splits the policy: `CLAUSE` (one chunk per §, with a heading breadcrumb) or `FIXED` (every N chars) |
| `policy/PolicyIngestService.java` | Chunk → embed → store. Deletes the doc's old chunks first, stable UUIDs, so re-ingest never duplicates |
| `policy/QdrantVectorCollection.java` | Creates the collection on first ingest (768 dims, cosine), so the app starts without Qdrant |
| `policy/PolicyService.java` | Retrieve (explicitly, not via `QuestionAnswerAdvisor`), both guards, LLM call, citation check |
| `policy/PolicyController.java` | `POST /api/policy/ingest`, `GET /api/policy/search` (no LLM), `GET/POST /api/policy/ask` |
| `resources/prompts/policy-qa-v*.st` | Grounding rules: clauses only, cite §, say "not covered", no applicant data. v2 fixes citing a definition instead of the rule |
| `test/.../policy/*Test.java` | Chunker + pipeline tests with an in-memory vector store and a fake embedding model |
| `../evals/run_policy_rag.py` | 16 questions with expected § (incl. traps): hit@1, hit@k, citation accuracy |

### Run it

1. Embedding model: `ollama pull nomic-embed-text`
2. Qdrant (no Docker): download `qdrant-x86_64-pc-windows-msvc.zip` from
   https://github.com/qdrant/qdrant/releases (we use v1.19.1), unzip to `E:\Chiru\AIStuff\qdrant`, run `qdrant.exe`.
   Spring AI uses gRPC on 6334; the REST API is on 6333.
   **Dashboard (optional):** the Windows binary has no web UI (only the Docker image bundles it). Download
   `dist-qdrant.zip` from https://github.com/qdrant/qdrant-web-ui/releases (we use v0.2.18), unzip, rename the
   `dist` folder to `static` next to `qdrant.exe`, restart Qdrant, open http://localhost:6333/dashboard.
3. Start this app, then `requests.http` **#24** (ingest), **#25** (search), **#26** (ask).
4. Eval: `python ..\evals\run_policy_rag.py --retrieval-only`, then without the flag for full answers.

### Results (qwen2.5:7b + nomic-embed-text, top-k 3, min-score 0.5)

**Retrieval** (`--retrieval-only`):

| Chunking | Chunks | hit@1 | hit@3 |
|---|---|---|---|
| **CLAUSE** (default) | 30 | 77% | **100%** |
| FIXED 500 chars | 13 | 77% | 92% (lost §4.8), and each chunk mixes 3–4 clauses |
| FIXED 200 chars | 32 | 69% | 92% |

**Answers** (16 questions: 13 with an expected §, plus out-of-scope, live-data and off-topic traps):

| Prompt | Correct citation / correct refusal | Failure |
|---|---|---|
| `policy-qa-v1` | 15/16 (94%) | q07 "bounced EMI": right fact, but cited §2.4 (the *definition* of bounce) instead of §4.8 (the rule) |
| `policy-qa-v2` | **16/16 (100%)** | v2 adds: cite the clause whose own text you rely on; definitions explain terms, rules set limits |

Guard 2 could not catch q07: §2.4 *was* retrieved. It checks that a citation exists, not that it supports the claim.

**Without RAG** (`#32`, POC 1 endpoint, same question) the model still answers "Fixed Operational Interest Rate ...
typically 9% to 14%". With RAG: "50% for salaried (§4.2), 45% for self-employed (§4.3)".

Similarity scores are compressed: correct clauses score ~0.70–0.83, but off-topic "cryptocurrency" still scores
0.58 and only clearly unrelated text (a cricket poem, 0.46) falls under 0.5. **A threshold can't separate
in-scope from out-of-scope on its own**, so the prompt's "say not covered" rule is the second layer.

### Experiments

1. **Chunking:** `#31` (FIXED 500), rerun the retrieval eval, compare, re-ingest with `#24`.
2. **top-k:** `--top-k 1` vs `5`. q02/q07/q08 need k ≥ 2: the right clause is #2 or #3.
3. **Paraphrase:** `#27`. "salary towards EMIs" ranks §3.3 first and §4.2 third.
4. **nomic task prefixes** (`search_query:` / `search_document:`): measured outside the app. They moved §4.2 to #1
   for the paraphrase, but cut the §4.2 vs §4.3 margin for "maximum FOIR" to 0.001 and raised every score
   (cricket 0.52), so the threshold would need recalibrating. A trade-off, not a free win; not enabled.
5. **No RAG:** `#32` asks POC 1 the same question. Compare with `#26`.
6. **Live data:** `#29`. Rule 4: scores come from the bureau over MCP, never from documents.
