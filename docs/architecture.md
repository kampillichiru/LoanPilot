# LoanPilot: End-to-End Architecture

## Big picture

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

## Application lifecycle (state machine)

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

## Worked example

Priya, salaried, ₹85,000/month net, existing EMI ₹20,000, wants ₹5,00,000 for 36 months at 12%.

- New EMI = P·r·(1+r)^n / ((1+r)^n − 1), r = 0.01, n = 36 → **₹16,607**
- FOIR = (20,000 + 16,607) / 85,000 = **43.1%** ≤ 50% ✅
- CIBIL 762 ≥ 700 ✅ · Max loan 20 × 85,000 = 17L ≥ 5L ✅ · Bounces 0 ✅
- → **RECOMMEND APPROVE**, memo cites policy §4.2, §4.5, §4.7

## Data model (target)

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
