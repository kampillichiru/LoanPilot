# LoanPilot

A **learning project**: an AI-assisted loan underwriting platform for a bank, built one mini-POC at a time.

> **Core architecture rule:** the LLM *understands and explains*; deterministic Java code *decides*.

## What it will do (end state)

An applicant applies for a personal loan and uploads salary slips and bank statements.
AI agents extract the data, pull mock bureau/bank data through **MCP**, check the credit policy
with **RAG (Qdrant)**, a **rules engine** decides, an LLM writes a **credit memo**, and a human
credit officer approves. Everything is audited, PII is masked, and quality is measured with **evals**.

See [docs/architecture.md](docs/architecture.md) for the full end-to-end design.

## Learning roadmap

| # | Mini-POC | Concept | Module | Status |
|---|----------|---------|--------|--------|
| 1 | Talk to a local LLM from Java | Prompts, system prompts, temperature, tokens | `loan-orchestrator` | ✅ scaffolded |
| 2 | Structured extraction from salary slips | Structured output, validation | `loan-orchestrator` | ⬜ |
| 3 | Tool calling (EMI calc, mock CIBIL) | Agent loop, `@Tool` | `loan-orchestrator` | ⬜ |
| 4 | First MCP server | MCP server, transports | `mcp-credit-bureau` | ⬜ |
| 5 | MCP client | Remote tool discovery | `loan-orchestrator` | ⬜ |
| 6 | RAG over credit policy | Embeddings, chunking, Qdrant | `policy-docs` + orchestrator | ⬜ |
| 7 | Decision engine + agent workflow | LLM explains, code decides | `loan-orchestrator` | ⬜ |
| 8 | Guardrails + evals | PII masking, audit, golden set | `evals` | ⬜ |

Details per POC: [docs/roadmap.md](docs/roadmap.md)

## Repository layout

```
LoanPilot/
├── README.md              ← you are here
├── CLAUDE.md              ← rules for Claude Code when working in this repo
├── LEARNINGS.md           ← your notes after each POC
├── docs/
│   ├── architecture.md    ← end-to-end design
│   ├── roadmap.md         ← the 8 POCs in detail
│   └── adr/               ← Architecture Decision Records
├── loan-orchestrator/     ← main Spring Boot app (POC 1, 2, 3, 5, 7)
├── mcp-credit-bureau/     ← mock CIBIL MCP server (POC 4)
├── policy-docs/           ← sample credit policy for RAG (POC 6)
├── data-generator/        ← Python: synthetic applicants & PDFs (POC 2+)
└── evals/                 ← Python: golden set & accuracy reports (POC 8)
```

## Prerequisites (Windows, no Docker)

| Tool | Needed from | Install |
|------|-------------|---------|
| JDK 21 | POC 1 | ✅ already installed |
| Maven | POC 1 | **Not needed**: use the included wrapper `mvnw.cmd` |
| Ollama | POC 1 | https://ollama.com/download (Windows installer) |
| Node.js 20+ | POC 4 | https://nodejs.org (for MCP Inspector) |
| Qdrant | POC 6 | GitHub Releases → `qdrant-x86_64-pc-windows-msvc.zip`, run `qdrant.exe` |
| Python 3.11+ | POC 2+ | ✅ already installed |

## Quick start: POC 1

See [loan-orchestrator/README.md](loan-orchestrator/README.md).
