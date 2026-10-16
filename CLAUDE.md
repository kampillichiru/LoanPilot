# CLAUDE.md: LoanPilot

This is a **learning project**. The owner is a senior Java/microservices engineer learning AI architecture.

## How to help
- Explain the *why* before writing code. Prefer short explanations with a small example.
- When the owner writes code, review it like a senior AI architect: point out risks, not just style.
- Generate boilerplate (pom, config) freely; leave core learning pieces (prompts, tools, chunking, rules) for the owner unless asked.
- Work one POC at a time (see `docs/roadmap.md`). Don't build ahead.

## Architecture rules (never violate)
1. **LLM explains, code decides.** Credit decisions (eligibility, FOIR, limits, approve/reject) are deterministic Java. An LLM never makes or overrides the decision.
2. **Mask PII before any LLM call**: PAN, Aadhaar, account numbers, phone, email. Cloud LLMs only ever see masked data.
3. **Every LLM call is auditable**: record model, prompt version, input hash, output.
4. **Live data comes from tools/MCP, not RAG.** RAG is for stable documents (policy, guidelines).
5. **All test data is synthetic.** Never commit real customer data, real PANs, or API keys.

## Tech stack
- Java 21, Spring Boot 4.1, Spring AI 2.0, Maven wrapper (`mvnw.cmd`)
- Ollama locally (`qwen2.5:7b`, `nomic-embed-text`); Claude API optional via profile later
- Qdrant (POC 6), H2 for the POC (Postgres later)
- Python for data generation and evals
- Windows, **no Docker**

## Conventions
- Base package: `com.loanpilot.<module>`
- Prompts live in `src/main/resources/prompts/` as versioned files (`name-v1.st`), not inline strings.
- Build & test: `mvnw.cmd clean verify` inside a module.
