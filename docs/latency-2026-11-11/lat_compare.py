#!/usr/bin/env python3 -I
"""Before / after of the latency benchmark.

usage: python3 -I lat_compare.py "before1.json,before2.json" "after1.json,after2.json"
Prices (USD per 1M tokens, DeepSeek V4.1 Flash on Hive): input 0.10, cached input 0.003, output 0.35. GLM-fallback replies are priced the same (GLM is cheaper, so cost is slightly overstated).
Hedge twins are billed by the provider but their tokens are not in a reply's usage: their cost is estimated as twins x the average input/output of one call.
"""
import json, statistics, sys, collections

P_IN, P_CACHED, P_OUT = 0.10, 0.003, 0.35

def pct(xs, p):
    xs = sorted(xs); return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else 0.0
def judge_failed(x): return bool(x.get("judgeFailed")) or str(x.get("note", "")).startswith("The judge could not grade")

def load(paths):
    convs = []
    for p in paths.split(","):
        for c in json.load(open(p))["cells"]:
            for x in c["results"]:
                if "turns" in x: convs.append(dict(x, _store=c["store"]))
    return convs

def metrics(convs):
    turns = [t for x in convs for t in x["turns"]]
    model = [t for t in turns if t["path"] != "CODE"]
    ms = [t["ms"] / 1000 for t in model]
    calls = sum(t["modelCalls"] for t in model)
    inp = sum(t["inputTokens"] for t in model); cached = sum(t["cachedTokens"] for t in model); out = sum(t["outputTokens"] for t in model)
    twins = sum(x["provider"].get("hedgedTwins", 0) for x in convs)
    provider_calls = sum(x["provider"].get("calls", 0) for x in convs)
    failed = sum(x["provider"].get("failedAttempts", 0) for x in convs)
    avg_in = inp / max(1, calls); avg_out = out / max(1, calls)
    reply_cost = ((inp - cached) * P_IN + cached * P_CACHED + out * P_OUT) / 1e6
    twin_cost = twins * ((avg_in * 0.6) * P_IN + (avg_in * 0.4) * P_CACHED + avg_out * P_OUT) / 1e6   # a twin is the same request: assume the typical cache share of 40%
    graded = [x for x in convs if "orderCheck" not in x and not judge_failed(x) and x.get("of", 0) > 0]
    jp = sum(x["passed"] for x in graded); jo = sum(x["of"] for x in graded)
    orders = [x for x in convs if "orderCheck" in x]
    first = [x for x in convs if any(t["tool"] == "prepare_order" for t in x.get("toolCalls", []))]
    reviews_by_none = None
    by_type = {}
    return dict(
        conversations=len(convs), messages=len(turns), model_replies=len(model),
        median=statistics.median(ms), p90=pct(ms, 90), p95=pct(ms, 95), mean=statistics.mean(ms), maxv=max(ms),
        calls_per_reply=calls / max(1, len(model)), in_per_reply=inp / max(1, len(model)), out_per_reply=out / max(1, len(model)), cached_share=100 * cached / max(1, inp),
        fallback_replies=sum(1 for t in model if t["fallbacks"] > 0 or t["modelFailures"] > 0), fallbacks=sum(t["fallbacks"] for t in model),
        hedge_twins=twins, hedged_conversations=sum(1 for x in convs if x["provider"].get("hedgedTwins", 0) > 0), provider_calls=provider_calls, failed_attempts=failed,
        tool_errors=sum(t["toolErrors"] for t in model), second_opinions=sum(t["secondOpinions"] for t in model),
        cost_replies=reply_cost, cost_twins=twin_cost, cost_per_model_reply=(reply_cost + twin_cost) / max(1, len(model)),
        judge_pct=100 * jp / max(1, jo), judge_checks=jo, judge_graded=len(graded), judge_ungraded=sum(1 for x in convs if "orderCheck" not in x and judge_failed(x)),
        orders=len(orders), orders_ok=sum(1 for x in orders if x["orderCheck"].get("allCorrect")), orders_item_qty=sum(1 for x in orders if x["orderCheck"].get("itemAndQuantityCorrect")),
        orders_contact=sum(1 for x in orders if x["orderCheck"].get("contactDetailsCorrect")), orders_payment=sum(1 for x in orders if x["orderCheck"].get("paymentCorrect")),
        first_prepare_ok=sum(1 for x in first if [t for t in x["toolCalls"] if t["tool"] == "prepare_order"][0]["ok"]), first_prepare=len(first),
        by_model_answered=collections.Counter(m for x in convs for m in (x.get("answeredBy") or [])),
    )

def main(before, after):
    b, a = metrics(load(before)), metrics(load(after))
    def row(label, key, fmt="{:.1f}", better=None):
        x, y = b[key], a[key]
        d = y - x
        print(f"  {label:44} {fmt.format(x):>10} {fmt.format(y):>10}   {('%+.1f' % d) if isinstance(d, float) else d:>8}")
    print(f"{'':46} {'before':>10} {'after':>10}   {'change':>8}")
    print(f"conversations / model replies: before {b['conversations']}/{b['model_replies']}, after {a['conversations']}/{a['model_replies']}")
    print("latency of model replies (s)")
    for l, k in (("median", "median"), ("P90", "p90"), ("P95", "p95"), ("mean", "mean"), ("slowest", "maxv")): row(l, k)
    print("calls, tokens, cost")
    row("model calls per reply", "calls_per_reply", "{:.2f}"); row("input tokens per reply", "in_per_reply", "{:.0f}"); row("output tokens per reply", "out_per_reply", "{:.0f}"); row("cached share of input (%)", "cached_share", "{:.0f}")
    row("cost per model reply, replies + hedge twins (USD)", "cost_per_model_reply", "{:.5f}")
    print(f"  of which hedge twins (estimate): before ${b['cost_twins']:.4f} total, after ${a['cost_twins']:.4f} total; replies: before ${b['cost_replies']:.4f}, after ${a['cost_replies']:.4f}")
    print("reliability")
    row("replies with a fallback / failed DeepSeek call", "fallback_replies", "{:d}"); row("hedge twins sent", "hedge_twins", "{:d}"); row("conversations with a hedge", "hedged_conversations", "{:d}")
    row("failed provider attempts", "failed_attempts", "{:d}"); row("provider calls", "provider_calls", "{:d}"); row("tool errors", "tool_errors", "{:d}"); row("second opinions", "second_opinions", "{:d}")
    print("quality")
    row("judge pass % (graded)", "judge_pct"); row("judge graded conversations", "judge_graded", "{:d}"); row("judge ungraded", "judge_ungraded", "{:d}")
    row("orders fully correct", "orders_ok", "{:d}"); row("orders item+quantity correct", "orders_item_qty", "{:d}"); row("orders contact details correct", "orders_contact", "{:d}"); row("orders payment correct", "orders_payment", "{:d}")
    row("first prepare_order ok", "first_prepare_ok", "{:d}")
    print(f"  orders in the plan: before {b['orders']}, after {a['orders']}; first prepare_order attempts: before {b['first_prepare']}, after {a['first_prepare']}")
    print("answered by:", "before", dict(b["by_model_answered"]), "| after", dict(a["by_model_answered"]))

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
