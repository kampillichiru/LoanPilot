# evals

Scripts that measure how well the AI parts work against synthetic answer keys. The POC 8 report
(`run_applications.py`) scores the whole loan workflow: extraction, decision, memo consistency and faithfulness,
PII masking and audit coverage. Committed reports live in `evals/reports/`.

| Script | POC | Measures |
|---|---|---|
| `run_salary_slip_extraction.py` | 2 | Per-field accuracy of `/api/extract/salary-slip` against `data-generator/out/salary-slips/expected.json` |
| `run_applications.py` | 7, 8 | Whole workflow on the 20-applicant golden set: extraction %, decision % (must be 100%: it's code), memo consistency %, memo faithfulness %, PII masked %, audited %. Writes a Markdown report to `evals/reports/` |
| `run_policy_rag.py` | 6 | RAG over the credit policy: retrieval hit@1 / hit@k and citation accuracy against `policy-questions.json` |

```
python evals/run_salary_slip_extraction.py                  # all slips (app must be running)
python evals/run_salary_slip_extraction.py --only slip-09   # one slip
python evals/run_salary_slip_extraction.py --native-schema  # Ollama JSON-schema mode
```

Detailed results, including the raw model output, go to `evals/results/` (git-ignored).

```
python evals/run_policy_rag.py --retrieval-only          # seconds, no LLM: tune chunking / topK / minScore
python evals/run_policy_rag.py                           # full: retrieval + answers + citations (~25 min on CPU)
python evals/run_policy_rag.py --only q02 --top-k 5      # one question
```

```
python evals/run_applications.py                        # full golden set: 22 applications, ~3 h on CPU
python evals/run_applications.py --fast                 # declared income: rules + memo (~3 min each)
python evals/run_applications.py --only APP-001         # one application, full (~9 min on CPU)
```

The answer key (`data-generator/out/applicants/expected.json`) is checked at generation time against a second,
independent Python implementation of the policy, so the Java engine is never graded against its own output.
