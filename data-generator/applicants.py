"""Generate the golden set: 20 loan applications with 3 salary slips each, plus expected outcomes (POC 7, POC 8).

    python data-generator/applicants.py      # writes data-generator/out/applicants/

Applicant ids, names and PAN patterns match mcp-credit-bureau's synthetic data (APP-001 ... APP-020; APP-019 is
deliberately not at the bureau), so the bureau call and the salary slips describe the same (fake) person.
All data is synthetic. Each file is a ready-to-send POST /api/applications body; expected.json is the answer key.

The expected outcomes are written by hand from the policy, then checked against reference_decision() below: a second,
independent implementation of credit-policy-v1 in Python. If the two disagree, generation fails. (Testing the Java
engine against an answer key the Java engine produced would prove nothing.)
"""

import json
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

from salary_slips import header, inr, two_column   # same slip layout as POC 2

OUT = Path(__file__).parent / "out" / "applicants"
MONTHS = [("July", 7), ("August", 8), ("September", 9)]

# Bureau facts per applicant: (CIBIL score or None for no history, existing EMIs, 90+ DPD accounts, enquiries in 6m).
# Must match mcp-credit-bureau BureauRepository. APP-019 has no entry: NOT_FOUND.
BUREAU = {
    "APP-001": (762, 20000, 0, 1), "APP-002": (641, 18000, 1, 5), "APP-003": (None, 0, 0, 0),
    "APP-004": (805, 12500, 0, 0), "APP-005": (698, 26000, 0, 7), "APP-006": (735, 13393, 0, 1),
    "APP-007": (735, 13394, 0, 1), "APP-008": (700, 8000, 0, 2), "APP-009": (720, 10000, 0, 4),
    "APP-010": (730, 10000, 0, 5), "APP-011": (745, 0, 0, 1), "APP-012": (760, 0, 0, 0),
    "APP-013": (None, 0, 0, 0), "APP-014": (None, 0, 0, 0), "APP-015": (750, 10000, 0, 1),
    "APP-016": (790, 18000, 0, 2), "APP-017": (770, 5000, 0, 1), "APP-018": (715, 8000, 0, 2),
    "APP-020": (728, 9000, 0, 1),
}

# id, name, employer, employment, net pay Jul/Aug/Sep (or declared income), request (amount, months, purpose),
# expected outcome, expected reason codes, what the case tests
APPLICANTS = [
    ("APP-001", "Priya Sharma", "Acme Infotech Pvt Ltd", "SALARIED", [84000, 85000, 86000], (500000, 36, "Home renovation"),
     "APPROVE", [], "the worked example: FOIR 43.1%"),
    ("APP-002", "Rahul Verma", "Bluewave Logistics Ltd", "SALARIED", [70000, 70000, 70000], (300000, 24, "Wedding"),
     "REJECT", ["CIBIL_BELOW_MINIMUM", "DPD90_IN_LAST_24M", "CREDIT_HUNGER"], "every reason listed, not just the first"),
    ("APP-003", "Arjun Nair", "Greenleaf Foods Pvt Ltd", "SALARIED", [61500, 62000, 62500], (250000, 24, "Medical expenses"),
     "APPROVE", [], "no credit history, approved under §4.4"),
    ("APP-004", "Sneha Reddy", "Northstar Analytics LLP", "SALARIED", [109500, 110000, 110500], (800000, 48, "Education"),
     "APPROVE", [], "preferential rate 11% (§6.2)"),
    ("APP-005", "Karan Mehta", "Sunrise Textiles Ltd", "SALARIED", [95000, 95000, 95000], (600000, 36, "Travel"),
     "REJECT", ["CIBIL_BELOW_MINIMUM", "CREDIT_HUNGER"], "CIBIL 698, one under 700"),
    ("APP-006", "Ananya Iyer", "Pinecone Software Pvt Ltd", "SALARIED", [59500, 60000, 60500], (500000, 36, "Home repair"),
     "APPROVE", [], "FOIR exactly 50.000%: the limit is <= 50%, so it passes"),
    ("APP-007", "Aditya Kulkarni", "Riverbend Pharma Ltd", "SALARIED", [59500, 60000, 60500], (500000, 36, "Car"),
     "REJECT", ["FOIR_EXCEEDED"], "FOIR 50.002%: displays as 50.0% but fails"),
    ("APP-008", "Kavya Menon", "Skyline Constructions Pvt Ltd", "SALARIED", [54500, 55000, 55500], (300000, 24, "Furniture"),
     "APPROVE", [], "CIBIL exactly 700: the minimum, so it passes"),
    ("APP-009", "Meera Joshi", "Lotus Retail Ltd", "SALARIED", [65000, 65000, 65000], (400000, 36, "Wedding"),
     "APPROVE", [], "4 enquiries: the maximum allowed"),
    ("APP-010", "Karthik Rao", "Quantum Fintech Pvt Ltd", "SALARIED", [64500, 65000, 65500], (400000, 36, "Travel"),
     "REFER", ["CREDIT_HUNGER"], "5 enquiries: one too many, refer to an officer"),
    ("APP-011", "Deepa Pillai", "Acme Infotech Pvt Ltd", "SALARIED", [24000, 24000, 24000], (60000, 12, "Laptop"),
     "REJECT", ["INCOME_BELOW_MINIMUM"], "income 24,000, under the 25,000 minimum"),
    ("APP-012", "Suresh Patil", "Bluewave Logistics Ltd", "SALARIED", [45000, 45000, 45000], (950000, 60, "Home renovation"),
     "REJECT", ["LOAN_ABOVE_MAXIMUM"], "asks 9.5L, maximum is 20 x 45,000 = 9L"),
    ("APP-013", "Farhan Qureshi", "Greenleaf Foods Pvt Ltd", "SALARIED", [69500, 70000, 70500], (400000, 36, "Business setup"),
     "REJECT", ["NO_CREDIT_HISTORY_NOT_ELIGIBLE", "LOAN_ABOVE_MAXIMUM"], "no history and asks 4L: over the 3L cap, two rules fail"),
    ("APP-014", "Lakshmi Das", "Northstar Analytics LLP", "SALARIED", [45000, 45000, 45000], (200000, 24, "Medical expenses"),
     "REJECT", ["NO_CREDIT_HISTORY_NOT_ELIGIBLE"], "no history and income 45,000, under the 50,000 needed"),
    ("APP-015", "Nikhil Bansal", "Sunrise Textiles Ltd", "SALARIED", [89500, 90000, 90500], (700000, 48, "Education"),
     "APPROVE", [], "CIBIL exactly 750 and FOIR under 40%: preferential 11%"),
    ("APP-016", "Pooja Kapoor", "Pinecone Software Pvt Ltd", "SALARIED", [90000, 90000, 90000], (700000, 48, "Home renovation"),
     "APPROVE", [], "CIBIL 790 but FOIR 40.5% at 12%: standard rate"),
    ("APP-017", "Imran Sheikh", "Riverbend Pharma Ltd", "SALARIED", [79500, 80000, 80500], (500000, 72, "Car"),
     "REJECT", ["TENURE_OUT_OF_RANGE"], "72 months, the maximum is 60"),
    ("APP-018", "Harish Chandra", "Self-employed (consultant)", "SELF_EMPLOYED", 60000, (600000, 36, "Business equipment"),
     "REJECT", ["FOIR_EXCEEDED"], "self-employed limit is 45% (§4.3): FOIR 46.5% would pass for salaried"),
    ("APP-019", "Divya Menon", "Skyline Constructions Pvt Ltd", "SALARIED", [70000, 70000, 70000], (300000, 36, "Wedding"),
     "REFER", ["BUREAU_NOT_FOUND"], "not at the bureau: can't assess creditworthiness"),
    ("APP-020", "Rohit Gupta", "Lotus Retail Ltd", "SALARIED", [71500, 72000, 72500], (350000, 36, "Travel"),
     "REFER", ["NAME_MISMATCH_ACROSS_SLIPS"], "September slip says Rohan Gupta: facts not established"),
]


# ---------- an independent reference implementation of credit-policy-v1 ----------

def emi(principal, rate, months):
    r = Decimal(rate) / Decimal(1200)
    f = (1 + r) ** months
    return (Decimal(principal) * r * f / (f - 1)).quantize(Decimal(1), ROUND_HALF_UP)


def reference_decision(app_id, employment, income, amount, months):
    """Returns (outcome, reasons, rate, foir rounded to 0.1). Mirrors policy clauses, not the Java code."""
    salaried = employment == "SALARIED"
    found = app_id in BUREAU and BUREAU[app_id][0] is not None
    no_history = app_id in BUREAU and BUREAU[app_id][0] is None
    score, existing, dpd, enq = BUREAU.get(app_id, (None, 0, 0, 0))
    fails, refers, reasons = [], [], []

    def add(code, kind):
        reasons.append(code)
        (fails if kind == "FAIL" else refers).append(code)

    if not found and not no_history:
        add("BUREAU_NOT_FOUND", "REFER")                                     # §4.5
    if not 50000 <= amount <= 2500000:
        add("AMOUNT_OUT_OF_RANGE", "FAIL")                                   # §5.1
    if not 12 <= months <= 60:
        add("TENURE_OUT_OF_RANGE", "FAIL")                                   # §5.2
    if income < (25000 if salaried else 40000):
        add("INCOME_BELOW_MINIMUM", "FAIL")                                  # §3.3
    if found and score < 700:
        add("CIBIL_BELOW_MINIMUM", "FAIL")                                   # §4.5
    if no_history and not (income >= 50000 and amount <= 300000):
        add("NO_CREDIT_HISTORY_NOT_ELIGIBLE", "FAIL")                        # §4.4
    if dpd > 0:
        add("DPD90_IN_LAST_24M", "FAIL")                                     # §4.8
    foir12 = (Decimal(existing) + emi(amount, 12, months)) * 100 / Decimal(income)
    rate = 11 if found and score >= 750 and foir12 <= 40 else 12            # §6.1 / §6.2
    foir = (Decimal(existing) + emi(amount, rate, months)) * 100 / Decimal(income)
    if foir > (50 if salaried else 45):
        add("FOIR_EXCEEDED", "FAIL")                                         # §4.2 / §4.3
    max_loan = min(income * (20 if salaried else 15), 2500000, 300000 if no_history else 10**12)
    if amount > max_loan:
        add("LOAN_ABOVE_MAXIMUM", "FAIL")                                    # §4.7
    if enq > 4:
        add("CREDIT_HUNGER", "REFER")                                        # §4.6
    outcome = "REJECT" if fails else "REFER" if refers else "APPROVE"
    return outcome, reasons, rate, float(foir.quantize(Decimal("0.1"), ROUND_HALF_UP))


# ---------- slips ----------

def slip_text(idx, name, employer, month_name, net, net_printed=None):
    """net_printed lets a test print a Net Pay that doesn't match gross - deductions (a tampered slip)."""
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
            f"\nNet Pay: {inr(net_printed if net_printed is not None else net)}\n")


def write(name, body):
    (OUT / name).write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    expected = {}
    for app_id, name, employer, employment, nets, (amount, months, purpose), outcome, reasons, why in APPLICANTS:
        idx = int(app_id.split("-")[1])
        declared_only = not isinstance(nets, list)
        income = nets if declared_only else round(sum(nets) / 3)
        body = {"applicantId": app_id, "employmentType": employment, "amount": amount, "tenureMonths": months,
                "purpose": purpose}
        slips_expected = []
        if declared_only:
            # Self-employed applicants have no salary slips: declared income (ITR verification is a later step).
            body["salarySlips"] = []
            body["declaredNetMonthlyIncome"] = income
        else:
            names = [name, name, "Rohan Gupta" if app_id == "APP-020" else name]
            body["salarySlips"] = [slip_text(idx, n, employer, m, net) for (m, _), net, n in zip(MONTHS, nets, names)]
            slips_expected = [{"payPeriod": f"2026-{num:02d}", "netPay": net, "employeeName": n}
                              for (_, num), net, n in zip(MONTHS, nets, names)]
        write(f"{app_id}.json", body)

        gate = reasons and reasons[0] in ("NAME_MISMATCH_ACROSS_SLIPS",)
        entry = {"outcome": outcome, "reasonCodes": reasons, "netMonthlyIncome": None if gate else income,
                 "slips": slips_expected, "why": why}
        if gate:
            entry["slipsOnly"] = True          # in fast mode there are no slips to disagree
        if declared_only:
            entry["declaredOnly"] = True
        if not gate:
            ref_outcome, ref_reasons, rate, foir = reference_decision(app_id, employment, income, amount, months)
            assert (ref_outcome, ref_reasons) == (outcome, reasons), \
                f"{app_id}: answer key says {outcome} {reasons}, reference rules say {ref_outcome} {ref_reasons}"
            entry["interestRate"] = rate
            entry["foirPercent"] = foir
        expected[f"{app_id}.json"] = entry

    # Bad documents: the workflow must REFER without running the rules on facts it couldn't establish.
    priya = dict(idx=1, name="Priya Sharma", employer="Acme Infotech Pvt Ltd")
    jul, aug = (slip_text(month_name=m, net=net, **priya) for (m, _), net in zip(MONTHS[:2], [84000, 85000]))
    write("APP-001-duplicate-month.json", {"applicantId": "APP-001", "employmentType": "SALARIED", "amount": 500000,
                                           "tenureMonths": 36, "purpose": "Home renovation",
                                           "salarySlips": [jul, aug, aug]})
    expected["APP-001-duplicate-month.json"] = {
        "outcome": "REFER", "reasonCodes": ["DUPLICATE_PAY_PERIOD"], "netMonthlyIncome": None, "slipsOnly": True,
        "slips": [{"payPeriod": "2026-07", "netPay": 84000, "employeeName": "Priya Sharma"},
                  {"payPeriod": "2026-08", "netPay": 85000, "employeeName": "Priya Sharma"},
                  {"payPeriod": "2026-08", "netPay": 85000, "employeeName": "Priya Sharma"}],
        "why": "the same month sent twice"}

    meera = dict(idx=9, name="Meera Joshi", employer="Lotus Retail Ltd")
    slips = [slip_text(month_name=m, net=65000, net_printed=66000 if num == 8 else None, **meera) for m, num in MONTHS]
    write("APP-009-tampered-slip.json", {"applicantId": "APP-009", "employmentType": "SALARIED", "amount": 400000,
                                         "tenureMonths": 36, "purpose": "Wedding", "salarySlips": slips})
    expected["APP-009-tampered-slip.json"] = {
        "outcome": "REFER", "reasonCodes": ["SLIP_EXTRACTION_FAILED"], "netMonthlyIncome": None, "slipsOnly": True,
        "slips": [], "why": "August prints Net Pay 66,000 but gross - deductions = 65,000: validation must catch it"}

    (OUT / "expected.json").write_text(json.dumps(expected, indent=2, ensure_ascii=False) + "\n",
                                       encoding="utf-8", newline="\n")
    print(f"Wrote {len(expected)} applications + expected.json to {OUT} (answer key agrees with the reference rules)")


if __name__ == "__main__":
    main()
