"""Run the golden set through POST /api/applications and score it (POC 7, POC 8).

    python evals/run_applications.py                   # full: 3 slips extracted per applicant (~9 min each on CPU)
    python evals/run_applications.py --fast            # declared income, skips slip extraction (~3 min each)
    python evals/run_applications.py --only APP-001    # one applicant (file prefix)

Scores (the POC 8 report):
- Extraction:   each slip's pay period and net pay equal the answer key (the LLM read the slip right)
- Decision:     outcome, reason codes, income, rate and FOIR equal the answer key (code: anything < 100% is a bug)
- Consistency:  the memo states the engine's recommendation and cites only sections it was given (POC 7 check)
- Faithfulness: the memo states only figures from its input, all the key ones, and claims nothing unverified
- PII masked:   every slip-extraction call had its PAN and account number masked before the LLM saw it
- Audited:      every LLM call has an audit record (3 per slip set + 1 per memo)

Needs loan-orchestrator, mcp-credit-bureau and Ollama running. Stdlib only.
Raw results go to evals/results/ (git-ignored, contains model output); the report to evals/reports/.
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
REPORTS = Path(__file__).resolve().parent / "reports"


def post(url, body, timeout):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        return {"httpError": e.code, "body": e.read().decode("utf-8", errors="replace")}
    except urllib.error.URLError as e:
        raise SystemExit(f"Cannot reach {url} ({e.reason}). Is loan-orchestrator running?")


def pct(n, d):
    return f"{100 * n / d:.0f}% ({n}/{d})" if d else "n/a"


def score_case(exp, r, fast):
    """Returns a dict of per-case scores and problems."""
    s = {"problems": [], "slipsTotal": 0, "slipsOk": 0, "slipCalls": 0, "slipCallsMasked": 0,
         "llmCallsExpected": 0, "llmCalls": 0, "faithful": None, "faithIssues": []}
    if "httpError" in r:
        s["problems"].append(f"HTTP {r['httpError']}: {r['body'][:200]}")
        s["decisionOk"] = s["memoConsistent"] = False
        return s

    # Decision
    if r["outcome"] != exp["outcome"]:
        s["problems"].append(f"outcome {r['outcome']} != {exp['outcome']}")
    if r["reasonCodes"] != exp["reasonCodes"]:
        s["problems"].append(f"reasons {r['reasonCodes']} != {exp['reasonCodes']}")
    income = (r.get("income") or {}).get("netMonthlyIncome")
    if exp.get("netMonthlyIncome") is not None and (income is None or abs(float(income) - exp["netMonthlyIncome"]) > 0.5):
        s["problems"].append(f"income {income} != {exp['netMonthlyIncome']}")
    d = r.get("decision") or {}
    if "interestRate" in exp and d and float(d["interestRate"]) != exp["interestRate"]:
        s["problems"].append(f"rate {d['interestRate']} != {exp['interestRate']}")
    if "foirPercent" in exp and d and float(d["foirPercent"]) != exp["foirPercent"]:
        s["problems"].append(f"FOIR {d['foirPercent']} != {exp['foirPercent']}")
    s["decisionOk"] = not s["problems"]

    # Extraction (full mode only, and only where the key lists slips)
    got = (r.get("income") or {}).get("slips") or []
    if not fast and exp.get("slips"):
        for want, slip in zip(exp["slips"], got):
            s["slipsTotal"] += 1
            ok = slip.get("valid") and slip.get("payPeriod") == want["payPeriod"] \
                and slip.get("netPay") is not None and abs(float(slip["netPay"]) - want["netPay"]) < 0.5
            s["slipsOk"] += bool(ok)
            if not ok:
                s["problems"].append(f"slip {want['payPeriod']}: got {slip.get('payPeriod')} {slip.get('netPay')} "
                                     f"valid={slip.get('valid')}")

    # Memo
    mc = r["memoCheck"]
    s["memoConsistent"] = mc["status"] in ("OK", "NOT_GENERATED")
    s["faithful"] = mc.get("faithful")
    s["faithIssues"] = mc.get("faithfulnessIssues") or []

    # Guard: masking and audit
    calls = r.get("llmCalls") or []
    s["llmCalls"] = len(calls)
    slip_calls = [c for c in calls if c["purpose"] == "slip-extraction"]
    s["slipCalls"] = len(slip_calls)
    s["slipCallsMasked"] = sum(1 for c in slip_calls
                               if (c.get("piiMasked") or {}).get("PAN", 0) >= 1
                               and (c.get("piiMasked") or {}).get("ACCOUNT_NUMBER", 0) >= 1)
    s["llmCallsExpected"] = len(got) + (1 if mc.get("faithful") is not None else 0)
    return s


def report_md(rows, fast, started, url):
    tot = lambda k: sum(r["s"][k] for r in rows)
    n = len(rows)
    memos = [r for r in rows if r["s"]["faithful"] is not None]
    lines = [
        f"# LoanPilot eval report: {'fast (declared income)' if fast else 'full (salary slips)'}",
        "",
        f"Run {started:%Y-%m-%d %H:%M} · {n} applications · qwen2.5:7b on CPU · {url}",
        "",
        "| Metric | Score | What it measures |",
        "|---|---|---|",
        f"| **Extraction** | {pct(tot('slipsOk'), tot('slipsTotal'))} | slips read with the right month and net pay |",
        f"| **Decision** | {pct(sum(r['s']['decisionOk'] for r in rows), n)} | outcome, reasons, income, rate, FOIR match the answer key |",
        f"| **Memo consistency** | {pct(sum(r['s']['memoConsistent'] for r in rows), n)} | memo states the engine's outcome, cites only given sections |",
        f"| **Memo faithfulness** | {pct(sum(1 for r in memos if r['s']['faithful']), len(memos))} | memo states only given figures, all key ones, no overclaim |",
        f"| **PII masked** | {pct(tot('slipCallsMasked'), tot('slipCalls'))} | slip calls with PAN and account number masked before the LLM |",
        f"| **Audited** | {pct(tot('llmCalls'), tot('llmCallsExpected'))} | LLM calls with an audit record |",
        "",
        "## Per application",
        "",
        "| Application | Expected | Got | Decision | Slips | Memo | Faithful | Time |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        s, e, resp = r["s"], r["expected"], r["response"]
        got = resp.get("outcome", "HTTP " + str(resp.get("httpError")))
        slips = f"{s['slipsOk']}/{s['slipsTotal']}" if s["slipsTotal"] else "-"
        faithful = "-" if s["faithful"] is None else ("yes" if s["faithful"] else "**no**")
        lines.append(f"| {r['file'].removesuffix('.json')} | {e['outcome']} {', '.join(e['reasonCodes'])} | {got} "
                     f"{', '.join(resp.get('reasonCodes') or [])} | {'ok' if s['decisionOk'] else '**WRONG**'} | {slips} | "
                     f"{(resp.get('memoCheck') or {}).get('status', '-')} | {faithful} | {r['seconds']:.0f}s |")
    problems = [(r["file"], p) for r in rows for p in r["s"]["problems"]]
    issues = [(r["file"], i) for r in rows for i in r["s"]["faithIssues"]]
    lines += ["", "## Problems", ""]
    lines += [f"- `{f}`: {p}" for f, p in problems] or ["None."]
    lines += ["", "## Faithfulness issues", ""]
    lines += [f"- `{f}`: {i}" for f, i in issues] or ["None."]
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://localhost:8080/api/applications")
    p.add_argument("--fast", action="store_true", help="send declaredNetMonthlyIncome instead of salary slips")
    p.add_argument("--only", help="file prefix, e.g. APP-001")
    p.add_argument("--timeout", type=int, default=2400)
    args = p.parse_args()

    key = json.loads((APPS / "expected.json").read_text(encoding="utf-8"))
    files = [f for f in key if (not args.only or f.startswith(args.only)) and not (args.fast and key[f].get("slipsOnly"))]
    started = datetime.now()
    RESULTS.mkdir(exist_ok=True)
    REPORTS.mkdir(exist_ok=True)
    mode = "fast" if args.fast else "full"
    raw_out = RESULTS / f"applications-{mode}-{started:%Y%m%d-%H%M%S}.json"
    report_out = REPORTS / f"report-{mode}-{started:%Y%m%d-%H%M%S}.md"

    rows = []
    for i, f in enumerate(files, 1):
        exp = key[f]
        body = json.loads((APPS / f).read_text(encoding="utf-8"))
        if args.fast and not exp.get("declaredOnly"):
            body["salarySlips"] = []
            body["declaredNetMonthlyIncome"] = exp["netMonthlyIncome"]
        start = time.time()
        r = post(args.url, body, args.timeout)
        s = score_case(exp, r, args.fast)
        secs = time.time() - start
        rows.append({"file": f, "expected": exp, "s": s, "seconds": secs, "response": r})

        ok = s["decisionOk"] and s["memoConsistent"] and s["faithful"] is not False and s["slipsOk"] == s["slipsTotal"]
        print(f"[{i:2}/{len(files)}] {'PASS' if ok else 'FAIL'}  {f:30} {r.get('outcome', '?'):7} "
              f"{str(r.get('reasonCodes')):48} slips {s['slipsOk']}/{s['slipsTotal']}  "
              f"memo={(r.get('memoCheck') or {}).get('status', '-'):14} faithful={s['faithful']}  "
              f"masked {s['slipCallsMasked']}/{s['slipCalls']}  {secs:6.1f}s", flush=True)
        for prob in s["problems"] + s["faithIssues"]:
            print(f"        {prob}", flush=True)
        # Write after every case: a 3-hour run that dies at case 19 still leaves 18 results.
        raw_out.write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")
        report_out.write_text(report_md(rows, args.fast, started, args.url), encoding="utf-8")

    print("\n" + report_md(rows, args.fast, started, args.url).split("## Per application")[0])
    print(f"Report: {report_out}\nRaw results (incl. memos): {raw_out}")


if __name__ == "__main__":
    main()
