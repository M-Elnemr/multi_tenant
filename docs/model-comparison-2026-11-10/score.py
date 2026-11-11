#!/usr/bin/env python3 -I
"""One scoring script for the benchmark's raw files. Every line shows its denominator.

usage: python3 -I score.py rerun-reasoning-on-trial1.json.gz rerun-reasoning-on-trial2.json.gz

Definitions (the same for every model, prompt and trial):
 * judged conversation = a scenario scored by the judge (not an order scenario: those have `orderCheck`, scored by code).
 * a judge failure = the judge gave no usable answer (note starts "The judge could not grade", or `judgeFailed`): not a score of 0, left out.
 * pooled % = sum(passed) / sum(of) over graded judged conversations.   mean % = average of passed/of per conversation (same set).
 * 'as scored before' counts a judge failure as 0 of N (what the old reports did).
 * 'all convs' pools every conversation of the cell including the order scenarios' judge lines (those are scored by code; their judge lines are mostly noise) - shown only to explain why some scripts disagreed.
"""
import gzip, json, sys, collections

def load(p):
    return json.load(gzip.open(p) if p.endswith(".gz") else open(p))

def judge_failed(x):
    return bool(x.get("judgeFailed")) or str(x.get("note", "")).startswith("The judge could not grade")

def rows(reports):
    for rep in reports:
        for c in rep["cells"]:
            for x in c["results"]:
                if "turns" in x or "passed" in x:
                    yield c["model"], c["mode"], x

def main(paths):
    reports = [load(p) for p in paths]
    cells = collections.defaultdict(list)
    for m, mode, x in rows(reports):
        cells[(m, mode)].append(x)
    print(f"files: {len(paths)}; cells: {len(cells)}\n")
    hdr = f"{'model':9}{'prompt':6} | {'convs':>5} {'judged':>6} {'failed':>6} {'graded':>6} | {'pooled passed/of':>17} {'pooled %':>8} {'mean %':>7} | {'as scored before %':>18} | {'all convs %':>11}"
    print(hdr); print("-" * len(hdr))
    for (m, mode) in sorted(cells):
        xs = cells[(m, mode)]
        judged = [x for x in xs if "orderCheck" not in x]
        graded = [x for x in judged if not judge_failed(x) and x.get("of", 0) > 0]
        failed = [x for x in judged if judge_failed(x)]
        p = sum(x["passed"] for x in graded); o = sum(x["of"] for x in graded)
        mean = sum(x["passed"] / x["of"] for x in graded) / max(1, len(graded))
        pb = sum(x["passed"] for x in judged); ob = sum(x["of"] for x in judged)
        pa = sum(x.get("passed", 0) for x in xs); oa = sum(x.get("of", 0) for x in xs)
        print(f"{m:9}{mode:6} | {len(xs):5} {len(judged):6} {len(failed):6} {len(graded):6} | {p:>8}/{o:<8} {100*p/max(1,o):8.1f} {100*mean:7.1f} | {100*pb/max(1,ob):18.1f} | {100*pa/max(1,oa):11.1f}")
    print("\nPer model (both prompts together, graded judged conversations only):")
    for m in sorted({k[0] for k in cells}):
        g = [x for k in cells if k[0] == m for x in cells[k] if "orderCheck" not in x and not judge_failed(x) and x.get("of", 0) > 0]
        p = sum(x["passed"] for x in g); o = sum(x["of"] for x in g)
        print(f"  {m:9} {p}/{o} = {100*p/max(1,o):.1f}%  ({len(g)} conversations)")

if __name__ == "__main__":
    main(sys.argv[1:])
