"""Generate 10 synthetic Indian salary slips plus an answer key (POC 2).

Every slip is fake: invented names and employers, PANs of the form ZZZPZ0001Z, zeroed account numbers.
Each slip uses a different layout and most contain one deliberate trap that real extraction gets wrong.

    python data-generator/salary_slips.py            # writes data-generator/out/salary-slips/
    python data-generator/salary_slips.py --seed 7   # different numbers, same layouts

Output is deterministic for a given seed, so the committed files only change when this script does.
"""

import argparse
import json
import random
from decimal import Decimal
from pathlib import Path

MONTHS = ["January", "February", "March", "April", "May", "June",
          "July", "August", "September", "October", "November", "December"]

EMPLOYEES = ["Priya Sharma", "Rahul Verma", "Ananya Iyer", "Vikram Reddy", "Sneha Kulkarni",
             "Arjun Nair", "Kavya Menon", "Rohit Gupta", "Meera Joshi", "Karthik Rao"]

EMPLOYERS = ["Acme Infotech Pvt Ltd", "Bluewave Logistics Ltd", "Greenleaf Foods Pvt Ltd",
             "Northstar Analytics LLP", "Sunrise Textiles Ltd", "Pinecone Software Pvt Ltd",
             "Riverbend Pharma Ltd", "Skyline Constructions Pvt Ltd", "Lotus Retail Ltd",
             "Quantum Fintech Pvt Ltd"]


def inr(amount, symbol="", paise=False):
    """Indian digit grouping: 1234567 -> 12,34,567."""
    amount = Decimal(amount)
    whole = int(amount)
    s = str(whole)
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        s = ",".join(groups + [tail])
    if paise:
        s += f".{int((amount - whole) * 100):02d}"
    return symbol + s


def make_figures(rng, with_paise=False):
    basic = rng.randrange(20_000, 90_000, 500)
    earnings = {
        "Basic": basic,
        "HRA": basic * 40 // 100,
        "Special Allowance": rng.randrange(5_000, 40_000, 250),
        "Conveyance": 1_600,
    }
    deductions = {
        "Provident Fund": min(basic, 15_000) * 12 // 100,
        "Professional Tax": 200,
        "Income Tax (TDS)": rng.randrange(0, 15_000, 50),
    }
    if with_paise:
        earnings = {k: Decimal(v) for k, v in earnings.items()}
        earnings["Special Allowance"] += Decimal("0.50")
        deductions = {k: Decimal(v) for k, v in deductions.items()}
        deductions["Income Tax (TDS)"] += Decimal("0.25")
    gross = sum(earnings.values())
    total_ded = sum(deductions.values())
    return earnings, deductions, gross, total_ded, gross - total_ded


def two_column(earnings, deductions, fmt):
    rows = []
    e, d = list(earnings.items()), list(deductions.items())
    for i in range(max(len(e), len(d))):
        left = f"{e[i][0]:<20}{fmt(e[i][1]):>12}" if i < len(e) else " " * 32
        right = f"{d[i][0]:<20}{fmt(d[i][1]):>12}" if i < len(d) else ""
        rows.append(f"{left}    {right}")
    return "\n".join(rows)


def header(idx, name, employer, extra=""):
    return (f"{employer.upper()}\n"
            f"Employee Name : {name}\n"
            f"Employee ID   : EMP{1000 + idx}\n"
            f"PAN           : ZZZPZ{idx:04d}Z\n"
            f"Bank A/c No.  : 0000 0000 {idx:04d}\n{extra}")


def amount_in_words(n):
    """Small helper for slip 5. Indian system: crore, lakh, thousand."""
    ones = ["", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven",
            "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"]
    tens = ["", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"]

    def below_100(x):
        return ones[x] if x < 20 else (tens[x // 10] + (" " + ones[x % 10] if x % 10 else ""))

    def below_1000(x):
        h, r = divmod(x, 100)
        return ((ones[h] + " Hundred" + (" " if r else "")) if h else "") + (below_100(r) if r else "")

    parts = []
    for size, label in ((10_000_000, "Crore"), (100_000, "Lakh"), (1_000, "Thousand")):
        if n >= size:
            parts.append(f"{below_1000(n // size)} {label}")
            n %= size
    if n:
        parts.append(below_1000(n))
    return " ".join(parts)


def build(rng):
    slips = []
    for i in range(1, 11):
        name, employer = EMPLOYEES[i - 1], EMPLOYERS[i - 1]
        year = 2026
        month = rng.randrange(1, 10)
        mname = MONTHS[month - 1]
        e, d, gross, ded, net = make_figures(rng, with_paise=(i == 8))
        trap = ""

        if i == 1:
            trap = "none: classic two-column layout (baseline)"
            text = (header(i, name, employer) +
                    f"\nPAYSLIP FOR THE MONTH OF {mname.upper()} {year}\n\n"
                    f"{'EARNINGS':<32}    DEDUCTIONS\n" + two_column(e, d, inr) +
                    f"\n\n{'Gross Earnings':<20}{inr(gross):>12}    {'Total Deductions':<20}{inr(ded):>12}"
                    f"\nNet Pay: {inr(net)}\n")
        elif i == 2:
            trap = "key: value list, no table"
            lines = [f"{k}: {inr(v)}" for k, v in {**e, **d}.items()]
            text = (header(i, name, employer) + f"Pay Period: {mname} {year}\n\n" + "\n".join(lines) +
                    f"\nGross Salary: {inr(gross)}\nTotal Deductions: {inr(ded)}\nNet Salary: {inr(net)}\n")
        elif i == 3:
            trap = "net pay labelled 'Take Home'"
            text = (header(i, name, employer) + f"Salary Statement - {mname} {year}\n\n" +
                    two_column(e, d, inr) +
                    f"\n\nTotal Earnings {inr(gross)} | Total Deductions {inr(ded)}\nTake Home: {inr(net)}\n")
        elif i == 4:
            trap = "month written as Mar'26"
            text = (header(i, name, employer) + f"Payslip: {mname[:3]}'{str(year)[2:]}\n\n" +
                    two_column(e, d, inr) +
                    f"\n\nGross: {inr(gross)}   Deductions: {inr(ded)}   Net: {inr(net)}\n")
        elif i == 5:
            trap = "Rs. prefix everywhere, net pay also in words"
            text = (header(i, name, employer) + f"Month: {mname} {year}\n\n" +
                    two_column(e, d, lambda v: inr(v, "Rs. ")) +
                    f"\n\nGross Earnings: Rs. {inr(gross)}\nTotal Deductions: Rs. {inr(ded)}\n"
                    f"Net Pay: Rs. {inr(net)}\n(Rupees {amount_in_words(int(net))} Only)\n")
        elif i == 6:
            trap = "bilingual Hindi-English labels"
            text = (f"{employer}\nवेतन पर्ची / SALARY SLIP - {mname} {year}\n"
                    f"कर्मचारी का नाम / Employee Name: {name}\nPAN: ZZZPZ{i:04d}Z\n\n" +
                    two_column(e, d, inr) +
                    f"\n\nकुल आय / Gross Earnings: {inr(gross)}\nकुल कटौती / Total Deductions: {inr(ded)}\n"
                    f"शुद्ध वेतन / Net Pay: {inr(net)}\n")
        elif i == 7:
            trap = "OCR-style noise: pipes, broken spacing, stray line breaks"
            rows = "\n".join(f"| {k} |  {inr(v)} |" for k, v in {**e, **d}.items())
            text = (f"{employer.upper()}\n|Emp Name|{name}|\n|PAN| ZZZPZ{i:04d}Z |\n"
                    f"|Month| {mname}\n{year} |\n" + rows +
                    f"\n| Gross   Earnings | {inr(gross)} |\n| Total  Deductions| {inr(ded)} |\n"
                    f"| Net\nPay | {inr(net)} |\n")
        elif i == 8:
            trap = "amounts with paise (decimals)"
            fmt = lambda v: inr(v, paise=True)  # noqa: E731
            text = (header(i, name, employer) + f"Pay slip for {mname} {year}\n\n" + two_column(e, d, fmt) +
                    f"\n\nGross Earnings: {fmt(gross)}\nTotal Deductions: {fmt(ded)}\nNet Pay: {fmt(net)}\n")
        elif i == 9:
            trap = "no 'Total Deductions' line: the model must add the deductions itself"
            text = (header(i, name, employer) + f"Payslip - {mname} {year}\n\n" + two_column(e, d, inr) +
                    f"\n\nGross Earnings: {inr(gross)}\nNet Pay: {inr(net)}\n")
        else:
            trap = "year-to-date (YTD) column next to the monthly figures"
            ytd = month
            rows = "\n".join(f"{k:<22}{inr(v):>12}{inr(v * ytd):>14}" for k, v in {**e, **d}.items())
            text = (header(i, name, employer) + f"Payslip for {mname} {year}\n\n"
                    f"{'Component':<22}{'This Month':>12}{'YTD':>14}\n{rows}\n"
                    f"{'Gross Earnings':<22}{inr(gross):>12}{inr(gross * ytd):>14}\n"
                    f"{'Total Deductions':<22}{inr(ded):>12}{inr(ded * ytd):>14}\n"
                    f"{'Net Pay':<22}{inr(net):>12}{inr(net * ytd):>14}\n")

        slips.append({
            "file": f"slip-{i:02d}.txt",
            "trap": trap,
            "text": text,
            "expected": {
                "employeeName": name,
                "employerName": employer,
                "payPeriod": f"{year}-{month:02d}",
                "grossEarnings": float(gross) if isinstance(gross, Decimal) else gross,
                "totalDeductions": float(ded) if isinstance(ded, Decimal) else ded,
                "netPay": float(net) if isinstance(net, Decimal) else net,
            },
        })
    return slips


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "out" / "salary-slips")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    slips = build(random.Random(args.seed))
    for s in slips:
        (args.out / s["file"]).write_text(s["text"], encoding="utf-8", newline="\n")
    key = {s["file"]: {"trap": s["trap"], **s["expected"]} for s in slips}
    (args.out / "expected.json").write_text(json.dumps(key, indent=2, ensure_ascii=False) + "\n",
                                            encoding="utf-8", newline="\n")
    print(f"Wrote {len(slips)} slips + expected.json to {args.out}")


if __name__ == "__main__":
    main()
