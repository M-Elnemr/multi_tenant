#!/usr/bin/env python3 -I
"""Summary of one or more latency benchmark reports (AiEvalBatch report.json), the same for the 'before' and the 'after' run.

usage: python3 -I lat_report.py report1.json [report2.json ...]     (several trials are pooled)
"""
import json, re, statistics, sys, collections

TYPES = {"1 greeting / identity": {"are_you_ai"}, "2 product inquiry": {"need_first", "hours", "delivery"}, "3 comparison / suitability": {"who_is_it_for", "size_advice", "product_knowledge"},
         "4 several variants": {"options", "item_note"}, "5 out of stock": {"out_of_stock"}, "6 multi-product": {"full_outfit", "budget_family", "sale"},
         "7 RAG / policy": {"returns", "trust", "no_returns", "cod_fear", "vegetarian"}, "8 negotiation": {"negotiation", "price_objection", "coupon"},
         "9 order preparation": {"order_flow"}, "10 ambiguous / other": {"unknown_question", "human_request", "injection"}}

def pct(xs, p):
    xs = sorted(xs); return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else 0
def typ(cat):
    for k, v in TYPES.items():
        if cat in v: return k
    return "other"
def judge_failed(x): return bool(x.get("judgeFailed")) or str(x.get("note", "")).startswith("The judge could not grade")

def main(paths):
    convs = []
    for p in paths:
        for c in json.load(open(p))["cells"]:
            for x in c["results"]:
                if "turns" in x: convs.append(dict(x, _store=c["store"]))
    turns = []
    for x in convs:
        for t in x["turns"]: turns.append(dict(t, _cat=x.get("category"), _type=typ(x.get("category")), _id=x["id"], _store=x["_store"]))
    model = [t for t in turns if t["path"] != "CODE"]
    print(f"conversations {len(convs)}; customer messages {len(turns)}; answered by the model {len(model)}; by code {len(turns) - len(model)}")
    ms = [t["ms"] / 1000 for t in model]
    print(f"model replies: median {statistics.median(ms):.1f}s  p90 {pct(ms, 90):.1f}s  p95 {pct(ms, 95):.1f}s  mean {statistics.mean(ms):.1f}s")
    allms = [t["ms"] / 1000 for t in turns]
    print(f"all messages:  median {statistics.median(allms):.1f}s  p90 {pct(allms, 90):.1f}s  p95 {pct(allms, 95):.1f}s")
    calls = [t["modelCalls"] for t in model]
    print(f"model calls per model reply: mean {statistics.mean(calls):.2f}; distribution {dict(sorted(collections.Counter(calls).items()))}")
    print(f"tokens per model reply: input {statistics.mean(t['inputTokens'] for t in model):.0f} (cached {100*sum(t['cachedTokens'] for t in model)/max(1,sum(t['inputTokens'] for t in model)):.0f}%), output {statistics.mean(t['outputTokens'] for t in model):.0f}")
    print(f"tool calls per model reply {statistics.mean(t['toolCalls'] for t in model):.2f}; tool errors {sum(t['toolErrors'] for t in model)}; fallbacks {sum(t['fallbacks'] for t in model)}; model failures {sum(t['modelFailures'] for t in model)}; second opinions {sum(t['secondOpinions'] for t in model)}")
    print("\nby scenario type: replies by model | median s | p90 s | calls | tool calls")
    by = collections.defaultdict(list)
    for t in model: by[t["_type"]].append(t)
    for k in sorted(by):
        v = by[k]; m = [t["ms"] / 1000 for t in v]
        print(f"  {k:28} {len(v):3} | {statistics.median(m):5.1f} | {pct(m, 90):5.1f} | {statistics.mean(t['modelCalls'] for t in v):.1f} | {statistics.mean(t['toolCalls'] for t in v):.1f}")
    # quality
    graded = [x for x in convs if "orderCheck" not in x and not judge_failed(x) and x.get("of", 0) > 0]
    p = sum(x["passed"] for x in graded); o = sum(x["of"] for x in graded)
    orders = [x for x in convs if "orderCheck" in x]
    ok = sum(1 for x in orders if x["orderCheck"].get("allCorrect"))
    qty = sum(1 for x in orders if x["orderCheck"].get("itemAndQuantityCorrect"))
    first = [x for x in convs if any(t["tool"] == "prepare_order" for t in x.get("toolCalls", []))]
    f_ok = sum(1 for x in first if [t for t in x["toolCalls"] if t["tool"] == "prepare_order"][0]["ok"])
    print(f"\nquality: judge {p}/{o} = {100*p/max(1,o):.1f}% over {len(graded)} graded conversations ({sum(1 for x in convs if 'orderCheck' not in x and judge_failed(x))} ungraded); orders fully correct {ok}/{len(orders)}, item+quantity correct {qty}/{len(orders)}; first prepare_order ok {f_ok}/{len(first)}")
    # timeline
    tl = [t for x in convs for t in [x["turns"][i] for i in range(len(x["turns"]))] if t.get("timeline")]
    if tl:
        phases = collections.defaultdict(list); llm = []; tools = collections.defaultdict(list); review = []; checks = []
        for t in tl:
            line = t["timeline"]
            for m in re.finditer(r"(\w+)=(\d+)(?:\(([^)]*)\))?", line):
                name, val, inner = m.group(1), int(m.group(2)), m.group(3)
                if name in ("prep", "prompt", "products", "flow", "post", "total"): phases[name].append(val)
                if inner:
                    for im in re.finditer(r"(llm\d+:\w+|tool:\w+|review|ruleChecks)=(\d+)", inner):
                        n, v = im.group(1), int(im.group(2))
                        if n.startswith("llm"): llm.append(v)
                        elif n.startswith("tool:"): tools[n].append(v)
                        elif n == "review": review.append(v)
                        else: checks.append(v)
        print(f"\ntimeline ({len(tl)} replies), median ms: " + ", ".join(f"{k} {statistics.median(v):.0f}" for k, v in phases.items()))
        if llm: print(f"  model call: median {statistics.median(llm)/1000:.1f}s p90 {pct(llm,90)/1000:.1f}s over {len(llm)} calls; review: median {statistics.median(review)/1000 if review else 0:.1f}s over {len(review)}; ruleChecks median {statistics.median(checks) if checks else 0:.0f} ms")
        for n, v in sorted(tools.items(), key=lambda kv: -len(kv[1])): print(f"  {n}: median {statistics.median(v):.0f} ms p90 {pct(v,90):.0f} ms over {len(v)} calls")

if __name__ == "__main__":
    main(sys.argv[1:])
