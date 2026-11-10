#!/usr/bin/env python3
"""Analysis of the re-run benchmark (two trials) and its comparison with the previous one. Every number is computed from the per-reply records the backend wrote."""
import json, math, random, collections, itertools, sys

random.seed(5)
T = [json.load(open(f"trial{i}.json")) for i in (1, 2) if __import__("os").path.exists(f"trial{i}.json")]
PREV = json.load(open("report.json"))        # previous benchmark, main run
PREV_ORD = json.load(open("report3.json"))   # previous benchmark, order scenarios re-run with the corrected check
MODELS = ["glm", "deepseek", "qwen"]; MODES = ["full", "lean"]; STORES = ["tech-zone", "nile-style", "beit-elshawarma"]
PRICE = {"glm": (0.05, 0.17, 0.01), "deepseek": (0.10, 0.35, 0.003), "qwen": (0.03, 0.13, 0.003)}   # in, out, cache read (USD per 1M tokens, providers' pages 2026-11-10)
JUDGE_PRICE = (0.10, 0.50, 0.01)   # the platform's configured Claude Haiku prices
REG = {931, 932, 933, 934, 941, 942, 943, 944, 951, 952, 953, 954}
ORDER_MAIN = {901, 902, 911, 912, 921, 922}
EXPECTED_PER = {"tech-zone": 29, "nile-style": 31, "beit-elshawarma": 32}


def pct(a, b): return 100.0 * a / b if b else 0.0
def p95(xs):
    xs = sorted(xs); return xs[min(len(xs) - 1, math.ceil(0.95 * len(xs)) - 1)] if xs else 0
def med(xs):
    xs = sorted(xs); return xs[len(xs) // 2] if xs else 0
def cost(m, i, o, c):
    pi, po, pc = PRICE[m]; return ((i - c) * pi + c * pc + o * po) / 1e6


def results(rep, model=None, mode=None, store=None, ids=None, exclude=None):
    out = []
    for c in rep["cells"]:
        if model and c["model"] != model: continue
        if mode and c["mode"] != mode: continue
        if store and c["store"] != store: continue
        for x in c["results"]:
            if "turns" not in x: continue
            if ids is not None and x["id"] not in ids: continue
            if exclude is not None and x["id"] in exclude: continue
            out.append(dict(x, _store=c["store"], _model=c["model"], _mode=c["mode"]))
    return out


def failed_reply(x): return any(t.get("errorReply") or t.get("modelFailures") for t in x["turns"])
def judged_ok(x): return x.get("of", 0) > 0 and x["passed"] == x["of"] and not failed_reply(x)
def order_ok(x): return bool(x["orderCheck"].get("allCorrect")) and not failed_reply(x)
def is_order(x): return "orderCheck" in x


def summary(reps, model, mode, store=None, comparable_only=False):
    xs = [x for r in reps for x in results(r, model, mode, store, exclude=REG if comparable_only else None)]
    judged = [x for x in xs if not is_order(x)]
    orders = [x for x in xs if is_order(x)]
    s = collections.OrderedDict()
    s["n"] = len(xs); s["judged"] = len(judged); s["orders"] = len(orders)
    s["judge_pass"] = sum(x["passed"] for x in judged); s["judge_of"] = sum(x["of"] for x in judged); s["judge_pct"] = pct(s["judge_pass"], s["judge_of"])
    s["judged_ok"] = sum(1 for x in judged if judged_ok(x))
    s["orders_ok"] = sum(1 for x in orders if order_ok(x))
    s["qty_ok"] = sum(1 for x in orders if x["orderCheck"].get("itemAndQuantityCorrect"))
    s["successes"] = s["judged_ok"] + s["orders_ok"]
    turns = [t for x in xs for t in x["turns"]]
    mt = [t for t in turns if t["path"] != "CODE"]
    ms = [t["ms"] / 1000 for t in mt]
    s["messages"] = len(turns); s["model_replies"] = len(mt); s["code_replies"] = len(turns) - len(mt)
    s["lat_avg"] = sum(ms) / max(1, len(ms)); s["lat_p50"] = med(ms); s["lat_p95"] = p95(ms)
    s["calls"] = sum(t["modelCalls"] for t in turns)
    s["inp"] = sum(t["inputTokens"] for t in turns); s["out"] = sum(t["outputTokens"] for t in turns); s["cached"] = sum(t["cachedTokens"] for t in turns)
    s["inp_per_reply"] = s["inp"] / max(1, len(mt)); s["out_per_reply"] = s["out"] / max(1, len(mt))
    s["tokens_per_conv"] = (s["inp"] + s["out"]) / max(1, len(xs))
    s["reasoning_tokens"] = sum(x["provider"]["reasoningTokens"] for x in xs); s["calls_with_reasoning"] = sum(x["provider"]["callsWithReasoning"] for x in xs); s["provider_calls"] = sum(x["provider"]["calls"] for x in xs)
    s["cost_in_uncached"] = (s["inp"] - s["cached"]) * PRICE[model][0] / 1e6
    s["cost_cached"] = s["cached"] * PRICE[model][2] / 1e6
    s["cost_out"] = s["out"] * PRICE[model][1] / 1e6
    so = [o for x in xs for o in x.get("secondOpinions", [])]
    s["so_n"] = len(so); s["so_adopted"] = sum(1 for o in so if o["adopted"])
    s["so_cost"] = sum(cost(o["model"], o["input"], o["output"], o["cached"]) for o in so)
    s["cost_own"] = s["cost_in_uncached"] + s["cost_cached"] + s["cost_out"]
    s["cost_total"] = s["cost_own"] + s["so_cost"]
    s["cost_per_success"] = s["cost_total"] / s["successes"] if s["successes"] else float("nan")
    s["cost_per_conv"] = s["cost_total"] / max(1, len(xs))
    tc = [t for x in xs for t in x.get("toolCalls", [])]
    s["tool_calls"] = len(tc); s["tool_errors"] = sum(1 for t in tc if not t["ok"])
    s["failures"] = sum(len(x.get("modelFailures", [])) for x in xs)
    s["fallbacks"] = sum(t.get("fallbacks", 0) for t in turns)
    s["http_errors"] = collections.Counter(); s["failed_attempts"] = sum(x["provider"]["failedAttempts"] for x in xs); s["hedges"] = sum(x["provider"]["hedgedTwins"] for x in xs)
    for x in xs:
        for k, v in x["provider"]["httpErrors"].items(): s["http_errors"][k] += v
    s["finish"] = collections.Counter()
    for x in xs:
        for k, v in x["provider"]["finishReasons"].items(): s["finish"][k] += v
    s["rag_missing_replies"] = sum(1 for t in turns if t.get("ragToolMissing")); s["rag_unavail_replies"] = sum(1 for t in turns if t.get("ragUnavailable"))
    s["judge_tok_in"] = sum(x.get("judgeTokens", {}).get("input", 0) for x in xs); s["judge_tok_out"] = sum(x.get("judgeTokens", {}).get("output", 0) for x in xs)
    return s


def first_attempt(reps, model, mode=None):
    """First prepare_order call of each conversation: ok on the first try / failed then recovered / never succeeded."""
    first_ok = rec = lost = total = 0
    for r in reps:
        for x in results(r, model, mode):
            calls = [t for t in x.get("toolCalls", []) if t["tool"] == "prepare_order"]
            if not calls: continue
            total += 1
            if calls[0]["ok"]: first_ok += 1
            elif any(t["ok"] for t in calls[1:]): rec += 1
            else: lost += 1
    return total, first_ok, rec, lost


if __name__ == "__main__":
    print("trials loaded:", len(T), [t["id"] + ":" + t["status"] for t in T])
