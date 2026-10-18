# data-generator

Python scripts that generate synthetic test data. All data is fake: invented names and employers,
PANs like `ZZZPZ0001Z`, zeroed account numbers. Never put real customer data here (rule 5).

| Script | POC | Output |
|---|---|---|
| `salary_slips.py` | 2 | `out/salary-slips/slip-01..10.txt` + `expected.json` (answer key) |
| `applicants.py` | 7, 8 | The golden set: `out/applicants/APP-001..020.json` (POST /api/applications bodies, 3 slips each; APP-018 is self-employed with declared income), 2 bad-document cases (duplicate month, tampered net pay) + `expected.json`. Each applicant sits on one rule boundary (FOIR exactly 50%, CIBIL exactly 700, 4 vs 5 enquiries, thin-file caps, the preferential-rate edge). Expected outcomes are verified against an independent Python implementation of the policy |

```
python data-generator/salary_slips.py
python data-generator/applicants.py
```

Each slip has a different layout and one deliberate trap (see `trap` in `expected.json`):
Take Home instead of Net Pay, `Mar'26`, `Rs.` prefixes, Hindi labels, OCR noise, paise,
a missing Total Deductions line, and a YTD column next to the monthly figures.
Output is deterministic for a given `--seed`.
