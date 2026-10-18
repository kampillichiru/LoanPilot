# ADR-0003: Loan applications are processed by a fixed workflow, not an agent

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
POC 3 and POC 5 built an agent: the LLM reads a question, decides which tools to call (calculateEmi,
getCreditReport) and fills in their arguments. That suits open questions ("can Priya afford 5 lakh?"), but in
POC 5 the model also invented an applicant name for a tool argument, and an agent may skip a tool it should
have called.

Processing an application is not an open question. Every application needs the same facts (bureau report,
verified income) in the same order, and the result must be reproducible for audit (ADR-0001).

## Decision
`POST /api/applications` runs a fixed sequence in code: BUREAU (MCP tool, called by code) → INCOME (LLM
extracts each salary slip, validation checks it) → DECIDE (`DecisionEngine`, pure Java) → POLICY (clause text
looked up by section, not searched) → MEMO (LLM explains the decision; code checks the memo states the
engine's outcome and cites only provided sections).

- The LLM never chooses the next step, never supplies a tool argument, and never sees the question
  "should we approve?".
- If facts can't be established (a slip fails validation, months repeat, names differ), the engine does not
  run: the outcome is REFER and code writes the memo.
- A bureau outage is a technical error (HTTP 503), never a credit outcome.

## Consequences
- ✅ Every application takes the same, auditable path; a step can't be skipped or hallucinated.
- ✅ The bureau is still called over MCP, so the data stays owned by the bureau service (POC 5).
- ✅ The agent (`/api/advise`) remains for exploratory questions, where flexibility is worth it.
- ⚠️ New steps need code changes, not prompt changes.
- ⚠️ The memo check only compares outcome and citations; it can't yet tell whether each sentence is supported
  (memo v1 described an unchecked rule as passed). A faithfulness check is POC 8.
