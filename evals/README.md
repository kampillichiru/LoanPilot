# evals

Scripts that measure how well the AI parts work against synthetic answer keys. POC 8 grows this into
a full report (extraction, decision, memo faithfulness).

| Script | POC | Measures |
|---|---|---|
| `run_salary_slip_extraction.py` | 2 | Per-field accuracy of `/api/extract/salary-slip` against `data-generator/out/salary-slips/expected.json` |

```
python evals/run_salary_slip_extraction.py                  # all slips (app must be running)
python evals/run_salary_slip_extraction.py --only slip-09   # one slip
python evals/run_salary_slip_extraction.py --native-schema  # Ollama JSON-schema mode
```

Detailed results, including the raw model output, go to `evals/results/` (git-ignored).
