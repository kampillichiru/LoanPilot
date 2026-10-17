"""Send every synthetic salary slip to /api/extract/salary-slip and score it against the answer key (POC 2).

    python evals/run_salary_slip_extraction.py                    # all 10 slips
    python evals/run_salary_slip_extraction.py --only slip-09     # one slip
    python evals/run_salary_slip_extraction.py --native-schema    # also constrain Ollama with the JSON schema

Only fields present in BOTH expected.json and the extracted slip are scored; expected fields your
SalarySlip record doesn't have are reported as "missing". Stdlib only, no pip install needed.
Full results (incl. raw model output) are written to evals/results/ (git-ignored).
"""

import argparse
import json
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SLIPS = ROOT / "data-generator" / "out" / "salary-slips"
RESULTS = Path(__file__).resolve().parent / "results"


def same(expected, actual):
    if isinstance(expected, (int, float)) and not isinstance(expected, bool):
        try:
            return abs(float(actual) - expected) < 0.005
        except (TypeError, ValueError):
            return False
    if isinstance(actual, list) and len(actual) == 2:   # Jackson may serialise YearMonth as [2026, 3]
        actual = f"{actual[0]}-{actual[1]:02d}"
    return str(actual).strip().casefold() == str(expected).strip().casefold()


def extract(url, text, native_schema, timeout):
    req = urllib.request.Request(f"{url}?nativeSchema={str(native_schema).lower()}",
                                 data=text.encode("utf-8"),
                                 headers={"Content-Type": "text/plain; charset=utf-8"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        if e.code == 422:   # model answered, result invalid: body still has slip/errors/rawOutput
            return json.load(e)
        raise SystemExit(f"HTTP {e.code} from {url}: {e.read().decode(errors='replace')}")
    except urllib.error.URLError as e:
        raise SystemExit(f"Cannot reach {url} ({e.reason}). Is loan-orchestrator running? mvnw.cmd spring-boot:run")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://localhost:8080/api/extract/salary-slip")
    p.add_argument("--only", help="run one slip, e.g. slip-09")
    p.add_argument("--native-schema", action="store_true")
    p.add_argument("--timeout", type=int, default=600, help="seconds per slip (CPU inference is slow)")
    args = p.parse_args()

    key = json.loads((SLIPS / "expected.json").read_text(encoding="utf-8"))
    files = [f for f in key if not args.only or f.startswith(args.only)]
    if not files:
        raise SystemExit(f"No slip matches --only {args.only}")

    results, fields_ok, fields_total, slips_ok = [], 0, 0, 0
    for f in files:
        expected = {k: v for k, v in key[f].items() if k != "trap"}
        start = time.time()
        resp = extract(args.url, (SLIPS / f).read_text(encoding="utf-8"), args.native_schema, args.timeout)
        slip = resp.get("slip") or {}

        mismatches = {k: {"expected": v, "got": slip.get(k)} for k, v in expected.items()
                      if k in slip and not same(v, slip.get(k))}
        missing = [k for k in expected if k not in slip]
        scored = len(expected) - len(missing)
        ok = resp.get("valid") and not mismatches and not missing
        fields_ok += scored - len(mismatches)
        fields_total += len(expected)
        slips_ok += bool(ok)

        status = "PASS" if ok else "FAIL"
        print(f"{status}  {f}  {scored - len(mismatches)}/{len(expected)} fields  "
              f"{time.time() - start:5.1f}s  ({key[f]['trap']})", flush=True)   # live progress even when piped
        for k, m in mismatches.items():
            print(f"        {k}: expected {m['expected']!r}, got {m['got']!r}")
        if missing:
            print(f"        missing in SalarySlip: {', '.join(missing)}")
        for e in resp.get("errors", []):
            print(f"        error: {e}")

        results.append({"file": f, "trap": key[f]["trap"], "pass": bool(ok), "mismatches": mismatches,
                        "missing": missing, "response": resp})

    print(f"\nSlips passed: {slips_ok}/{len(files)}   Field accuracy: {fields_ok}/{fields_total} "
          f"({100 * fields_ok / fields_total:.0f}%)   nativeSchema={args.native_schema}")
    if results and results[0]["response"].get("promptVersion"):
        print(f"Prompt version: {results[0]['response']['promptVersion']}")

    RESULTS.mkdir(exist_ok=True)
    out = RESULTS / f"salary-slips-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Details (incl. raw model output): {out}")


if __name__ == "__main__":
    main()
