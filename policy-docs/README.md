# policy-docs

Stable documents for RAG (POC 6). All synthetic: LoanPilot Bank is fictional and these are not RBI rules.

| File | What |
|---|---|
| `credit-policy-v1.md` | Personal-loan credit policy: 8 chapters, 30 numbered clauses (§1.1 … §8.1) |

## Format rules (the chunker depends on them)
- Chapters: `## 4. Credit and affordability rules`
- Clauses: `### §4.2 Maximum FOIR for salaried applicants`, then the clause text
- One rule per clause. Text before the first chapter (title, disclaimer) is not indexed.

`loan-orchestrator` splits the file into one chunk per clause, embeds each with `nomic-embed-text` and stores
them in Qdrant (`POST /api/policy/ingest`). Answers cite clauses as §4.2.

The numbers match the worked example in `docs/architecture.md`: FOIR ≤ 50% (§4.2), CIBIL ≥ 700 (§4.5),
max loan 20 × net monthly income (§4.7), no bounces (§4.8). POC 7's rules engine will implement these clauses.

**Live data never goes here** (rule 4): an applicant's score or salary comes from MCP tools, not from RAG.
After editing the policy, re-run the ingest.
