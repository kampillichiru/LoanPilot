# ADR-0001: The LLM explains, deterministic code decides

- **Status:** Accepted
- **Date:** 2026-10-16

## Context
Credit decisions are regulated. Regulators and auditors expect decisions to be explainable,
reproducible (same input → same output) and free of unjustified bias. LLM output is
probabilistic and can hallucinate.

## Decision
- LLMs are used for **understanding** (chat intake, document extraction, policy retrieval)
  and **explaining** (credit memo).
- Eligibility, FOIR, limits, scoring and approve/reject recommendations are computed by a
  **deterministic Java rules engine**, covered by unit tests.
- The memo agent may only use facts and reason codes produced by the engine.

## Consequences
- ✅ Decisions are reproducible and testable with JUnit.
- ✅ Audit can trace each decision to rules and source data.
- ⚠️ Rules must be maintained in code/config when policy changes.
- ⚠️ Need a faithfulness check so the memo doesn't contradict the decision (POC 8).
