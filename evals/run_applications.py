"""Run every synthetic loan application through POST /api/applications and check it (POC 7).

    python evals/run_applications.py --fast            # declared income, skips slip extraction (~1 min each)
    python evals/run_applications.py                   # full: 3 slips extracted per applicant (~6 min each on CPU)
    python evals/run_applications.py --only APP-001    # one applicant

Checks, per application:
- outcome and reason codes equal the answer key (the engine is code, so anything below 100% is a bug)
- income equals the average of the 3 slips (full mode)
- memoCheck is OK: the memo states the engine's recommendation and cites only sections it was given

Needs: loan-orchestrator, mcp-credit-bureau and Ollama running. Stdlib only. Details go to evals/results/.
"""

import argparse
import json
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APPS = ROOT / "data-generator" / "out" / "applicants"
RESULTS = Path(__file__).resolve().parent / "results"


def post(url, body, timeout):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} from {url}: {e.read().decode('utf-8', errors='replace')}")
    except urllib.error.URLError as e:
        raise SystemExit(f"Cannot reach {url} ({e.reason}). Is loan-orchestrator running?")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://localhost:8080/api/applications")
    p.add_argument("--fast", action="store_true", help="send declaredNetMonthlyIncome instead of salary slips")
    p.add_argument("--only", help="file prefix, e.g. APP-001")
    p.add_argument("--timeout", type=int, default=1800)
    args = p.parse_args()

    key = json.loads((APPS / "expected.json").read_text(encoding="utf-8"))
    files = [f for f in key if (not args.only or f.startswith(args.only)) and not (args.fast and key[f].get("slipsOnly"))]

    results, decided_ok, memo_ok = [], 0, 0
    for f in files:
        exp = key[f]
        body = json.loads((APPS / f).read_text(encoding="utf-8"))
        if args.fast:
            body["salarySlips"] = []
            body["declaredNetMonthlyIncome"] = exp["netMonthlyIncome"]
        start = time.time()
        r = post(args.url, body, args.timeout)

        problems = []
        if r["outcome"] != exp["outcome"]:
            problems.append(f"outcome {r['outcome']} != {exp['outcome']}")
        if r["reasonCodes"] != exp["reasonCodes"]:
            problems.append(f"reasons {r['reasonCodes']} != {exp['reasonCodes']}")
        income = (r.get("income") or {}).get("netMonthlyIncome")
        if exp["netMonthlyIncome"] is not None and (income is None or abs(float(income) - exp["netMonthlyIncome"]) > 0.5):
            problems.append(f"income {income} != {exp['netMonthlyIncome']}")
        decision_ok = not problems
        memo = r["memoCheck"]["status"]
        memo_good = memo in ("OK", "NOT_GENERATED")
        decided_ok += decision_ok
        memo_ok += memo_good

        d = r.get("decision") or {}
        print(f"{'PASS' if decision_ok and memo_good else 'FAIL'}  {f:30} {r['outcome']:7} {str(r['reasonCodes']):55} "
              f"FOIR {d.get('foirPercent', '-'):>5}  rate {d.get('interestRate', '-'):>3}  memo={memo:22} "
              f"{time.time() - start:6.1f}s", flush=True)
        for prob in problems:
            print(f"        {prob}")
        if not memo_good:
            print(f"        memo says {r['memoCheck']['memoRecommendation']}, unsupported {r['memoCheck']['unsupportedCitations']}")
        results.append({"file": f, "expected": exp, "problems": problems, "response": r})

    n = len(files)
    print(f"\nDecisions correct: {decided_ok}/{n}   Memos consistent with the decision: {memo_ok}/{n}   "
          f"mode={'fast (declared income)' if args.fast else 'full (salary slips)'}")
    RESULTS.mkdir(exist_ok=True)
    out = RESULTS / f"applications-{'fast' if args.fast else 'full'}-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Details (incl. memos): {out}")


if __name__ == "__main__":
    main()
