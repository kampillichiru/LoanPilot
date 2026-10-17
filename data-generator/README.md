# data-generator

Python scripts that generate synthetic test data. All data is fake: invented names and employers,
PANs like `ZZZPZ0001Z`, zeroed account numbers. Never put real customer data here (rule 5).

| Script | POC | Output |
|---|---|---|
| `salary_slips.py` | 2 | `out/salary-slips/slip-01..10.txt` + `expected.json` (answer key) |

```
python data-generator/salary_slips.py
```

Each slip has a different layout and one deliberate trap (see `trap` in `expected.json`):
Take Home instead of Net Pay, `Mar'26`, `Rs.` prefixes, Hindi labels, OCR noise, paise,
a missing Total Deductions line, and a YTD column next to the monthly figures.
Output is deterministic for a given `--seed`.
