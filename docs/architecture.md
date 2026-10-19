# LoanPilot: Architecture

Two views:
- **As built (POC 1–8):** what runs today, where each kind of AI is used, and why.
- **Target:** the production design the POCs are working towards.

## As built: POC 1–8

### System map

Colours: amber = LLM call, violet = embeddings / vector DB / RAG, teal = plain Java or MCP, dashed amber = the POC 8 guard, dashed grey = not built yet.

```mermaid
flowchart LR
  subgraph clients["Clients"]
    officer["Loan officer<br/>curl · requests.http"]
    evals["evals/ · Python<br/>golden sets, scores<br/>run by hand today"]
    datagen["data-generator/ · Python<br/>synthetic slips, applicants<br/>+ answer key"]
    cc["Claude Code · MCP Inspector<br/>POC 4"]
    ci["CI evals · not built<br/>GitHub Actions on every PR"]
    reports[("evals/reports/*.md<br/>scores per run")]
  end

  subgraph orch["loan-orchestrator :8080 · Spring Boot 4.1 + Spring AI 2.0"]
    ask["Ask · POC 1<br/>/api/ask"]
    extract["Extract · POC 2<br/>/api/extract<br/>LLM reads, Java validates"]
    rag["Policy RAG · POC 6<br/>/api/policy/ingest · search · ask"]
    advise["Advise agent · POC 3, 5<br/>/api/advise<br/>LLM picks the tools"]
    subgraph wf["Application workflow · POC 7 · /api/applications"]
      direction LR
      s1["1 Bureau<br/>code"] --> s2["2 Slips<br/>LLM"] --> s3["3 Decide<br/>DecisionEngine"] --> s4["4 § lookup<br/>PolicyLibrary"] --> s5["5 Memo<br/>LLM, code checks"]
    end
    guard["POC 8 guard · LlmGuardAdvisor<br/>mask PII · audit every call"]
    auditlog[("audit/llm-calls.jsonl<br/>append-only")]
  end

  subgraph ext["Separate processes and files"]
    ollama["Ollama :11434<br/>qwen2.5:7b · chat<br/>nomic-embed-text · 768-d embeddings"]
    qdrant[("Qdrant :6334<br/>vector DB · 30 clause vectors")]
    docs["policy-docs/credit-policy-v1.md<br/>30 clauses · source of truth"]
    bureau["mcp-credit-bureau :8082<br/>MCP · getCreditReport<br/>in-memory list · 19 synthetic applicants · no LLM"]
  end

  officer -- HTTP --> ask
  officer -- HTTP --> wf
  evals -- HTTP --> extract
  evals -- HTTP --> rag
  evals -- HTTP --> wf
  datagen -- "test files + expected.json" --> evals
  evals -- "scores" --> reports
  ci -. "would run a fast subset" .-> evals
  ask -- LLM --> guard
  extract -- LLM --> guard
  rag -- "answer" --> guard
  rag -- "embed question (not guarded)" --> ollama
  advise -- LLM --> guard
  s2 -- LLM --> guard
  s5 -- LLM --> guard
  guard -- "masked prompt" --> ollama
  guard -- "one record per call" --> auditlog
  rag -- "store vectors · top-3 search" --> qdrant
  docs -- "chunk by clause" --> rag
  s4 -- "read § by number" --> docs
  advise -- "MCP · LLM chooses" --> bureau
  s1 -- "MCP · code calls" --> bureau
  cc -- "MCP tools/call" --> bureau

  classDef ai fill:#fbefdc,stroke:#a35a00,color:#1b2230
  classDef rag fill:#ebe6fa,stroke:#5b44b0,color:#1b2230
  classDef code fill:#dcf0f1,stroke:#0d6a72,color:#1b2230
  classDef planned fill:#fbefdc,stroke:#a35a00,stroke-dasharray:5 5,color:#1b2230
  classDef future fill:#eeeeee,stroke:#777777,stroke-dasharray:5 5,color:#1b2230
  class ask,extract,advise,s2,s5,ollama ai
  class rag,qdrant rag
  class s1,s3,s4,bureau code
  class guard planned
  class auditlog,reports code
  class ci future
```

How to read it:
- **Every LLM chat call** (Ask, Extract, Advise, the RAG answer, slip reading, memo) goes through the POC 8 guard: PII is masked first, then one audit record is written per call (ADR-0004). Embedding calls don't use `ChatClient`, so they bypass it (a known gap).
- **Only Policy RAG** uses embeddings and the **vector database**. Nothing else needs to search by meaning.
- **The bureau is reached two ways over MCP:** the Advise agent calls it because the LLM chose to; the workflow calls it because the code always does. Claude Code uses the same tool directly (POC 4).
- **The workflow does not use RAG.** It already knows the rule is §4.2, so it reads §4.2 from the file (lookup, not search).
- **Evals are outside clients.** Python scripts call the running app over HTTP, like Postman would. Nothing in the Java code calls them. Today you run them by hand; CI evals (dashed grey) aren't built.
- **There is no database.** No H2, no Postgres. See "Where data lives today" below.

### Where data lives today (no database)

| Data | Today | Target |
|---|---|---|
| Bureau reports | In-memory `List` in `BureauRepository` (19 synthetic applicants) | Real bureau API behind the same MCP tool |
| Policy vectors | Qdrant (a vector DB, not a relational DB) | Qdrant, or pgvector on Postgres |
| LLM audit log | `audit/llm-calls.jsonl`, append-only, git-ignored | `audit_log` table with retention and access control |
| Applications, decisions, memos | Not stored: returned in the API response | `loan_applications`, `decisions`, `credit_memos` tables |
| Eval answer key | `data-generator/out/applicants/expected.json` | Same, versioned with the golden set |

A database is needed once an application has to be remembered between calls: the state machine and officer review in the target design.

### Evals: today vs CI (POC 8)

```mermaid
flowchart LR
  subgraph today["Today · by hand"]
    you["You<br/>python evals/run_applications.py"]
  end
  subgraph later["Not built · CI"]
    pr["Pull request<br/>e.g. edit credit-memo-v6.st"] --> gha["GitHub Actions<br/>mvnw verify, start services"]
  end
  key[("expected.json<br/>answer key, checked by a<br/>2nd Python policy")]
  app["loan-orchestrator :8080<br/>/api/extract · /api/policy · /api/applications"]
  report[("evals/reports/*.md<br/>extraction · decision ·<br/>faithfulness · PII · audit %")]
  gate{"score ≥ threshold?"}

  you -- HTTP --> app
  gha -. "fast subset, HTTP" .-> app
  app -- responses --> cmp["compare with answer key"]
  key --> cmp
  cmp --> report
  report -.-> gate
  gate -. no .-> block["block the merge"]
  gate -. yes .-> merge["allow the merge"]

  classDef code fill:#dcf0f1,stroke:#0d6a72,color:#1b2230
  classDef future fill:#eeeeee,stroke:#777777,stroke-dasharray:5 5,color:#1b2230
  class you,app,key,report,cmp code
  class pr,gha,gate,block,merge future
```

Same scripts either way. CI only adds the trigger (every pull request) and a pass/fail threshold. The blocker is speed: the full golden set takes about 3 hours on CPU, so CI would run a small `--fast` subset of boundary cases.

### RAG in detail (POC 6)

Two separate moments that share one embedding model, so the numbers are comparable:

```mermaid
sequenceDiagram
  participant U as Officer
  participant P as PolicyService
  participant E as nomic-embed-text
  participant Q as Qdrant
  participant L as qwen2.5:7b
  Note over P,Q: INGEST, once per policy version
  P->>P: chunk credit-policy-v1.md by clause (30 chunks)
  P->>E: embed each clause
  E-->>P: 768 numbers per clause
  P->>Q: upsert vector + § + text
  Note over U,L: ASK, every question
  U->>P: What is the maximum FOIR?
  P->>E: embed the question
  E-->>P: 768 numbers
  P->>Q: 3 nearest clauses, min score 0.5
  Q-->>P: §4.2, §4.3, one more
  P->>L: question + 3 clauses (prompt policy-qa-v2)
  L-->>P: 50% of net income (§4.2), 45% self-employed (§4.3)
  P->>P: check every cited § was retrieved
  P-->>U: answer + citations + scores
```

Without RAG, the same model said FOIR means "Fixed Operational Interest Rate".

### Application workflow in detail (POC 7)

```mermaid
sequenceDiagram
  participant O as Officer
  participant W as ApplicationService
  participant B as mcp-credit-bureau
  participant L as qwen2.5:7b
  participant D as DecisionEngine
  participant F as PolicyLibrary
  O->>W: POST /api/applications (Priya, 5L, 36 months, 3 slips)
  W->>B: getCreditReport APP-001 (MCP, called by code)
  B-->>W: CIBIL 762, existing EMIs 20,000
  loop each of 3 slips
    W->>L: extract slip (prompt extract-salary-slip-v2)
    L-->>W: JSON fields
    W->>W: validate: gross minus deductions equals net
  end
  W->>W: fact gate (3 months, same name), average income 85,000
  W->>D: facts
  D-->>W: APPROVE, FOIR 43.1%, every check with its § (2 ms)
  W->>F: clause text for failed or referred rules
  W->>L: decision + clauses (prompt credit-memo-v2), never "should we approve?"
  L-->>W: memo
  W->>W: memo says APPROVE? cites only real §?
  W-->>O: outcome, checks, memo, memo check, step trace
```

### Which technique each POC uses

| POC | What | LLM | Embeddings | Vector DB | RAG | MCP | Java decides |
|---|---|---|---|---|---|---|---|
| 1 | Talk to a local LLM | chat | – | – | – | – | – |
| 2 | Read salary slips | extract | – | – | – | – | validation, sums |
| 3 | Tool calling | picks tools | – | – | – | – | EMI maths |
| 4 | MCP server | – | – | – | – | server | – |
| 5 | MCP client | picks tools | – | – | – | client | – |
| 6 | RAG over policy | answer | nomic | Qdrant | yes | – | § check |
| 7 | Decision + workflow | slips, memo | – | – | no (lookup) | client | **the decision** |
| 8 | Guardrails + evals | masked + audited | – | – | – | – | masking, audit, faithfulness check |

### Why each component

| Component | POC | Why we chose it | Trade-off / later |
|---|---|---|---|
| Spring AI 2.0 | 1+ | One Java API for chat, structured output, tools, MCP and vector stores | Swap Ollama for Claude with config, not code |
| Ollama + qwen2.5:7b | 1+ | Runs on the laptop: free, and no data leaves the machine | Slow on CPU (2–4 min per call); GPU or hosted model in production |
| nomic-embed-text | 6 | Small local embedding model, 768 numbers per text | Changing the model means re-embedding every clause |
| Qdrant | 6 | Purpose-built vector DB; Windows binary, no Docker; stores § and text with each vector | pgvector once we move to Postgres |
| MCP (mcp-credit-bureau) | 4, 5, 7 | The bureau owns its data; any AI client uses the same tool through one contract | Needs auth (OAuth) and audit before real use |
| DecisionEngine (plain Java) | 7 | Same answer every time, unit-tested, explainable rule by rule (ADR-0001) | Limits move to a versioned policy config |
| Workflow, not agent | 7 | Every application needs the same facts in the same order (ADR-0003) | New steps need code changes |
| Python (data-generator, evals) | 2+ | Fast scripts outside the product; all data synthetic | Evals run in CI on every prompt change |
| PII guard + audit log (`LlmGuardAdvisor`) | 8 | One advisor on every `ChatClient`: mask before, audit after; can't be forgotten (ADR-0004) | Embeddings not guarded yet; JSONL file becomes a DB table |
| Memo faithfulness check (code) | 8 | Every memo number must come from its input; key figures, disclosures, no overclaim | Can't judge tone or a right number with the wrong label |

### Where AI is deliberately not used
- **Maths** (EMI, FOIR, totals, averages): Java. The LLM once "added" deductions to 19,700 instead of 11,150.
- **The decision**: `DecisionEngine`, 2 ms, 18 unit tests. An LLM's output can't be unit-tested or replayed exactly for an audit.
- **Live data** (credit score, EMIs): MCP tools, never RAG. RAG is for stable documents.
- **Choosing the steps of an application**: a fixed workflow. The agent once invented an applicant name.

## Target architecture

The production design the POCs are working towards. Some of it isn't built yet (core banking and account aggregator MCP servers, officer screen, state machine, database).

### Big picture (target)

```
 Applicant (browser)
      │  apply + upload salary slips, bank statement, KYC
      ▼
┌─────────────────────────────────────────────────────────────┐
│  loan-orchestrator  (Spring Boot, :8080)                    │
│                                                             │
│  Intake Agent → Document Extraction → Data Agent → Policy   │
│   (chat)         (PDFBox + LLM)       (MCP tools)  Agent    │
│                                                    (RAG)    │
│                         │                                   │
│                         ▼                                   │
│        Decision Engine: RULES + SCORECARD (pure Java)       │
│                         │                                   │
│                         ▼                                   │
│        Credit Memo Agent → Credit Officer screen (human)    │
│                                                             │
│  Guardrails: PII masking · audit log · prompt versions      │
└──────┬──────────────┬───────────────┬───────────────┬───────┘
       │ MCP          │ MCP           │ MCP           │
       ▼              ▼               ▼               ▼
 mcp-core-banking  mcp-credit-bureau  mcp-account-   Qdrant :6333
   :8081             :8082            aggregator     credit policy
                                       :8083         vectors
                              │
                   H2 / Postgres  ←  Ollama :11434
```

### Application lifecycle (target state machine)

| Status | What happens | Who | AI? |
|---|---|---|---|
| `SUBMITTED` | Applicant chats; intake agent collects details | Intake Agent | LLM |
| `DOCS_RECEIVED` | PDFs saved to `storage/{appId}/` | Java | – |
| `EXTRACTED` | PDF text → **PII masked** → JSON (salary, employer, month) | Local LLM + validation | LLM |
| `VERIFIED` | Slip salary vs bank credits, name vs KYC | Java rules | **No** |
| `DATA_PULLED` | Bureau score, existing EMIs, transactions via MCP | Data Agent | LLM + MCP |
| `POLICY_CHECKED` | Relevant policy clauses with citations | Policy Agent | RAG |
| `DECIDED` | EMI, FOIR, limits, scorecard → recommendation | Decision Engine | **No** |
| `MEMO_READY` | Credit memo explaining the decision, with sources | Memo Agent | LLM |
| `OFFICER_REVIEW` | Human reviews & approves/rejects | Credit officer | – |
| `APPROVED` / `REJECTED` | Notify, audit trail complete | Java | – |

Locally there is **no Kafka**: a DB-backed state machine plus Spring events. See ADR-0002.

### Worked example

Priya, salaried, ₹85,000/month net, existing EMI ₹20,000, wants ₹5,00,000 for 36 months at 12%.

- New EMI = P·r·(1+r)^n / ((1+r)^n − 1), r = 0.01, n = 36 → **₹16,607**
- FOIR = (20,000 + 16,607) / 85,000 = **43.1%** ≤ 50% ✅
- CIBIL 762 ≥ 700 ✅ · Max loan 20 × 85,000 = 17L ≥ 5L ✅ · Bounces 0 ✅
- → **RECOMMEND APPROVE**, memo cites policy §4.2, §4.5, §4.7

### Data model (target)

```
customers(id, name, pan_masked, dob, employment_type, employer)
existing_loans(id, customer_id, type, emi, outstanding)
bureau_reports(pan_hash, score, active_accounts, dpd_90, enquiries_6m)
bank_transactions(id, account_id, date, amount, type, narration)
loan_applications(id, customer_id, amount, tenure, purpose, status, created_at)
documents(id, application_id, type, file_path, uploaded_at)
extracted_facts(id, application_id, doc_id, field, value, confidence)
decisions(id, application_id, outcome, score, foir, reason_codes_json)
credit_memos(id, application_id, content, model, prompt_version)
audit_log(id, application_id, step, actor, model, prompt_version, input_hash, output, ts)
```
