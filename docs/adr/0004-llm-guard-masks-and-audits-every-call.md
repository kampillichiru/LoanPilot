# ADR-0004: One guard masks PII and audits every LLM call

- **Status:** Accepted
- **Date:** 2026-10-18

## Context
Up to POC 7, every salary slip went to the LLM as-is, PAN and bank account included (rule 2 broken; acceptable only
because the model is local and the data synthetic). LLM calls were logged per service, in different shapes, and
nothing kept the model output for later (rule 3 only half met). There are five services that call an LLM, and more
will come.

## Decision
- **One Spring AI advisor, `LlmGuardAdvisor`, on every `ChatClient`.** `GuardConfig` registers it through a
  `ChatClientCustomizer`, so a service can't forget it and a new service gets it automatically. It runs first in the
  advisor chain, so even DEBUG logging only ever sees masked text.
- **Mask before the call (`PiiMasker`):** PAN, Aadhaar, bank account, Indian mobile, email become `[PAN]`,
  `[AADHAAR]`, ... One-way: the originals aren't kept, because nothing we ask the LLM needs them back.
  Over-mask rather than under-mask (an unlabelled 12-digit number is treated as an Aadhaar).
  Names are not masked yet: the workflow compares names across slips (consistent tokens would be needed).
- **Audit after the call:** one append-only JSON line per call: purpose, model, prompt version, SHA-256 of the masked
  input, output, tokens, latency, PII counts, error. The input itself is not stored, only its hash.
  **Fail closed:** if the record can't be written, the request fails and the output is not used.
- **Memo faithfulness is checked in code (`MemoFaithfulness`), not by a second LLM:** every number in the memo must
  come from its input, key figures must be present, unverified sections must be disclosed, no "meets all criteria".

## Consequences
- ✅ Rule 2 and rule 3 are enforced in one place, testable without Ollama (`LlmGuardAdvisorTest`).
- ✅ Each application response lists its LLM calls (purpose, prompt version, input hash, PII counts); the full
  record is at `GET /api/audit?requestId=...`.
- ✅ The faithfulness check is deterministic and free; it runs on every memo, not just in evals.
- ⚠️ Embedding calls (POC 6 questions) don't go through `ChatClient`, so they aren't masked or audited yet.
- ⚠️ Regex masking misses what it doesn't know (a PAN split across lines, a new ID format). A cloud model would need
  more: an NER-based detector, allow-lists per purpose, and a test corpus of real document layouts.
- ⚠️ The code check can't judge tone, emphasis, or a right number next to the wrong label. An LLM-as-judge could, at
  the cost of another slow, non-deterministic call that itself needs evaluating.
- ⚠️ The JSONL file is a POC store: production needs a database table with retention rules and access control (the
  audit log contains model outputs about applicants).
