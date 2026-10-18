"""Generate loan applications for the 5 bureau applicants, each with 3 salary slips, plus expected outcomes (POC 7).

    python data-generator/applicants.py      # writes data-generator/out/applicants/

Applicant ids, names and PAN patterns match mcp-credit-bureau's synthetic data (APP-001 Priya ... APP-005 Karan),
so the workflow's bureau call and its salary slips describe the same (fake) person. All data is synthetic.
Each file is a ready-to-send POST /api/applications body. expected.json is the answer key for evals.
"""

import json
from pathlib import Path

from salary_slips import header, inr, two_column   # same slip layout as POC 2

OUT = Path(__file__).parent / "out" / "applicants"
MONTHS = [("July", 7), ("August", 8), ("September", 9)]

# id, name, employer, net pay for Jul/Aug/Sep, request (amount, months, purpose), expected outcome
APPLICANTS = [
    ("APP-001", "Priya Sharma", "Acme Infotech Pvt Ltd", [84000, 85000, 86000], (500000, 36, "Home renovation"),
     "APPROVE", [], "the worked example in docs/architecture.md: FOIR 43.1%"),
    ("APP-002", "Rahul Verma", "Bluewave Logistics Ltd", [70000, 70000, 70000], (300000, 24, "Wedding"),
     "REJECT", ["CIBIL_BELOW_MINIMUM", "DPD90_IN_LAST_24M", "CREDIT_HUNGER"], "score 641, a 90+ DPD account, 5 enquiries"),
    ("APP-003", "Arjun Nair", "Greenleaf Foods Pvt Ltd", [61500, 62000, 62500], (250000, 24, "Medical expenses"),
     "APPROVE", [], "no credit history: approved under §4.4 (income >= 50k, amount <= 3L)"),
    ("APP-004", "Sneha Reddy", "Northstar Analytics LLP", [109500, 110000, 110500], (800000, 48, "Education"),
     "APPROVE", [], "score 805 and FOIR <= 40%: preferential rate 11% (§6.2)"),
    ("APP-005", "Karan Mehta", "Sunrise Textiles Ltd", [95000, 95000, 95000], (600000, 36, "Travel"),
     "REJECT", ["CIBIL_BELOW_MINIMUM", "CREDIT_HUNGER"], "score 698, one point under 700; 7 enquiries"),
]


def slip_text(idx, name, employer, month_name, month, net):
    tds = round(net * 0.08, -1)
    deductions = {"Provident Fund": 1800, "Professional Tax": 200, "Income Tax (TDS)": int(tds)}
    gross = net + sum(deductions.values())
    basic = int(round(gross * 0.4, -2))
    hra = int(round(basic * 0.4, -2))
    earnings = {"Basic": basic, "HRA": hra, "Conveyance": 1600}
    earnings["Special Allowance"] = gross - sum(earnings.values())
    assert earnings["Special Allowance"] > 0
    total_ded = sum(deductions.values())
    return (header(idx, name, employer) +
            f"\nPAYSLIP FOR THE MONTH OF {month_name.upper()} 2026\n\n"
            f"{'EARNINGS':<32}    DEDUCTIONS\n" + two_column(earnings, deductions, inr) +
            f"\n\n{'Gross Earnings':<20}{inr(gross):>12}    {'Total Deductions':<20}{inr(total_ded):>12}"
            f"\nNet Pay: {inr(net)}\n")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    expected = {}
    for app_id, name, employer, nets, (amount, months, purpose), outcome, reasons, why in APPLICANTS:
        idx = int(app_id.split("-")[1])
        slips = [slip_text(idx, name, employer, m, n, net) for (m, n), net in zip(MONTHS, nets)]
        body = {"applicantId": app_id, "employmentType": "SALARIED", "amount": amount, "tenureMonths": months,
                "purpose": purpose, "salarySlips": slips}
        fname = f"{app_id}.json"
        (OUT / fname).write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
        expected[fname] = {"outcome": outcome, "reasonCodes": reasons, "netMonthlyIncome": round(sum(nets) / 3),
                           "why": why}

    # Bad facts: Priya sends August twice. The workflow must REFER without running the rules on bad data.
    jul, aug = (slip_text(1, "Priya Sharma", "Acme Infotech Pvt Ltd", m, n, net)
                for (m, n), net in zip(MONTHS[:2], [84000, 85000]))
    body = {"applicantId": "APP-001", "employmentType": "SALARIED", "amount": 500000, "tenureMonths": 36,
            "purpose": "Home renovation", "salarySlips": [jul, aug, aug]}
    (OUT / "APP-001-duplicate-month.json").write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n",
                                                      encoding="utf-8", newline="\n")
    expected["APP-001-duplicate-month.json"] = {"outcome": "REFER", "reasonCodes": ["DUPLICATE_PAY_PERIOD"],
                                                "netMonthlyIncome": None, "slipsOnly": True,
                                                "why": "the same month sent twice: facts not established, rules not run"}

    (OUT / "expected.json").write_text(json.dumps(expected, indent=2, ensure_ascii=False) + "\n",
                                       encoding="utf-8", newline="\n")
    print(f"Wrote {len(expected)} applications + expected.json to {OUT}")


if __name__ == "__main__":
    main()
