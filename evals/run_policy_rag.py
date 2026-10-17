"""Measure RAG over the credit policy against evals/policy-questions.json (POC 6).

    python evals/run_policy_rag.py --retrieval-only            # seconds: retrieval hit rate only, no LLM
    python evals/run_policy_rag.py                             # full: retrieval + LLM answer + citations
    python evals/run_policy_rag.py --only q02 --top-k 5        # one question, more clauses

Metrics
- hit@1 / hit@k: was the expected clause the top result / anywhere in the top k?  (retrieval quality)
- cited: does the answer cite the expected clause, with no unsupported citations?   (answer quality)
- out-of-scope (expected null): pass if the LLM wasn't called, or the answer cites nothing / says "not cover"

Re-ingest after changing chunking:  POST /api/policy/ingest?strategy=FIXED&chunkSize=500
Stdlib only. Full results go to evals/results/ (git-ignored).
"""

import argparse
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
NOT_COVERED = re.compile(r"not (cover|contain|include|have|specif|address|mention)|no (information|applicant data)", re.I)


def get(url, params, timeout):
    full = f"{url}?{urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})}"
    try:
        with urllib.request.urlopen(full, timeout=timeout) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} from {full}: {e.read().decode('utf-8', errors='replace')}")
    except urllib.error.URLError as e:
        raise SystemExit(f"Cannot reach {url} ({e.reason}). Is loan-orchestrator running?")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default="http://localhost:8080/api/policy")
    p.add_argument("--retrieval-only", action="store_true")
    p.add_argument("--only", help="run one question, e.g. q02")
    p.add_argument("--top-k", type=int)
    p.add_argument("--min-score", type=float)
    p.add_argument("--timeout", type=int, default=600)
    args = p.parse_args()

    questions = json.loads((HERE / "policy-questions.json").read_text(encoding="utf-8"))
    questions = [q for q in questions if not args.only or q["id"] == args.only]
    if not questions:
        raise SystemExit(f"No question with id {args.only}")

    rows, results = [], []
    for q in questions:
        start = time.time()
        params = {"q": q["question"], "topK": args.top_k, "minScore": args.min_score}
        if args.retrieval_only:
            retrieved = get(f"{args.base}/search", params, args.timeout)
            answer = None
        else:
            answer = get(f"{args.base}/ask", params, args.timeout)
            retrieved = answer["retrieved"]

        sections = [s for r in retrieved for s in r["sections"].split(",")]
        top = retrieved[0]["sections"].split(",") if retrieved else []
        exp = q["expected"]
        row = {"id": q["id"], "expected": exp, "trap": q["trap"],
               "retrieved": [f"§{r['section']}={r['score']}" for r in retrieved]}
        if exp is not None:
            row["hit1"] = exp in top
            row["hitk"] = exp in sections
        if answer is not None:
            cites = answer["citations"]
            if exp is not None:
                row["cited"] = exp in cites and not answer["unsupportedCitations"]
            else:
                row["cited"] = (not answer["llmCalled"]) or not cites or bool(NOT_COVERED.search(answer["answer"]))
            row["citations"] = cites
            row["llmCalled"] = answer["llmCalled"]

        ok = row.get("cited", row.get("hitk", True)) if exp is not None or answer is not None else True
        flag = "PASS" if ok else "FAIL"
        if args.retrieval_only and exp is None:
            flag = "INFO"
        detail = []
        if "hit1" in row:
            detail.append(f"hit@1={'Y' if row['hit1'] else 'n'} hit@k={'Y' if row['hitk'] else 'n'}")
        if "cited" in row:
            detail.append(f"cites={row['citations']}{'' if row['llmCalled'] else ' (no LLM call)'}")
        print(f"{flag}  {q['id']}  expect={('§' + exp) if exp else 'none':6}  {' '.join(detail)}  "
              f"{time.time() - start:5.1f}s  {' '.join(row['retrieved'][:3])}  ({q['trap']})", flush=True)
        rows.append(row)
        results.append({"question": q, "row": row, "response": answer if answer is not None else retrieved})

    scored = [r for r in rows if r["expected"] is not None]
    if scored:
        h1 = sum(r["hit1"] for r in scored)
        hk = sum(r["hitk"] for r in scored)
        print(f"\nRetrieval: hit@1 {h1}/{len(scored)} ({100 * h1 / len(scored):.0f}%)   "
              f"hit@k {hk}/{len(scored)} ({100 * hk / len(scored):.0f}%)")
    if not args.retrieval_only:
        c = sum(r["cited"] for r in rows)
        print(f"Answers:   correct citation / correct refusal {c}/{len(rows)} ({100 * c / len(rows):.0f}%)")

    RESULTS.mkdir(exist_ok=True)
    mode = "retrieval" if args.retrieval_only else "full"
    out = RESULTS / f"policy-rag-{mode}-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Details: {out}")


if __name__ == "__main__":
    main()
