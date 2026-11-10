#!/usr/bin/env python3
"""Turns the raw model-comparison report (report.json) into the numbers of the final report. Nothing here is estimated: every figure is computed from the per-reply records the backend wrote."""
import json, math, sys, collections

R = json.load(open(sys.argv[1] if len(sys.argv) > 1 else "report.json"))
PRICE = {  # USD per 1M tokens: input, output, cache-read (providers' own pages, read 2026-11-10)
    "glm": (0.05, 0.17, 0.01),       # Hive, zai-org/glm-5.3-flash
    "deepseek": (0.10, 0.35, 0.003),  # Hive, deepseek-v4.1-flash-1017
    "qwen": (0.03, 0.13, 0.003),     # Alibaba Model Studio international, qwen3.7-flash, <=32K input tier, cache hit = 10% of input
}
MODELS = ["glm", "deepseek", "qwen"]
STORES = ["tech-zone", "nile-style", "beit-elshawarma"]
MODES = ["full", "lean"]


def cost(model, inp, out, cached):
    pi, po, pc = PRICE[model]
    return ((inp - cached) * pi + cached * pc + out * po) / 1e6


def p95(xs):
    if not xs:
        return 0
    xs = sorted(xs)
    return xs[min(len(xs) - 1, math.ceil(0.95 * len(xs)) - 1)]


def pct(a, b):
    return 100.0 * a / b if b else 0.0


def cells(model, mode, store=None):
    return [c for c in R["cells"] if c["model"] == model and c["mode"] == mode and (store is None or c["store"] == store)]


def convs(model, mode, store=None):
    return [x for c in cells(model, mode, store) for x in c["results"] if "turns" in x]


def code_only(x):
    return all(t["path"] == "CODE" for t in x["turns"])


def success(x):
    """A conversation is a success when the judge passed every check, any order was placed right (checked by code), and no model failure showed."""
    if x.get("of", 0) == 0 or x.get("passed", 0) < x["of"]:
        return False
    if "orderCheck" in x and not x["orderCheck"].get("allCorrect"):
        return False
    if any(t.get("errorReply") or t.get("modelFailures") for t in x["turns"]):
        return False
    return True


def summarize(model, mode, store=None):
    cs = convs(model, mode, store)
    s = collections.OrderedDict()
    s["conversations"] = len(cs)
    graded = [x for x in cs if x.get("of")]
    s["judge_checks_passed"] = sum(x["passed"] for x in graded)
    s["judge_checks_total"] = sum(x["of"] for x in graded)
    s["judge_pct"] = pct(s["judge_checks_passed"], s["judge_checks_total"])
    mc = [x for x in graded if not code_only(x)]
    s["model_conversations"] = len(mc)
    s["judge_pct_model_only"] = pct(sum(x["passed"] for x in mc), sum(x["of"] for x in mc))
    s["all_checks_passed_convs"] = sum(1 for x in graded if x["passed"] == x["of"])
    s["successful_convs"] = sum(1 for x in cs if success(x))
    oc = [x for x in cs if "orderCheck" in x]
    s["order_scenarios"] = len(oc)
    s["orders_all_correct"] = sum(1 for x in oc if x["orderCheck"].get("allCorrect"))
    ck = collections.Counter()
    for x in oc:
        for k, v in x["orderCheck"].items():
            if k != "allCorrect" and v:
                ck[k] += 1
    s["order_check_passes"] = dict(ck)
    # tools
    tc = [t for x in cs for t in x.get("toolCalls", [])]
    s["tool_calls"] = len(tc)
    s["tool_errors"] = sum(1 for t in tc if not t["ok"])
    byt = collections.Counter(t["tool"] for t in tc)
    bye = collections.Counter(t["tool"] for t in tc if not t["ok"])
    s["tools"] = {k: (byt[k], bye.get(k, 0)) for k in byt}
    s["tool_error_messages"] = collections.Counter(t.get("error", "")[:90] for t in tc if not t["ok"]).most_common(6)
    # replies, paths, failures
    turns = [t for x in cs for t in x["turns"]]
    s["customer_messages"] = len(turns)
    s["code_answered"] = sum(1 for t in turns if t["path"] == "CODE")
    mt = [t for t in turns if t["path"] != "CODE"]
    s["model_replies"] = len(mt)
    s["model_failed_replies"] = sum(1 for t in turns if t["path"] == "MODEL_FAILED" or t.get("errorReply"))
    s["model_failure_events"] = sum(t.get("modelFailures", 0) for t in turns)
    s["fallbacks"] = sum(t.get("fallbacks", 0) for t in turns)
    s["second_opinions"] = sum(t.get("secondOpinions", 0) for t in turns)
    s["second_opinions_adopted"] = sum(1 for t in turns if t.get("secondOpinionAdopted"))
    # latency
    allms = [t["ms"] for t in turns]
    mms = [t["ms"] for t in mt]
    s["lat_all_avg_s"] = sum(allms) / max(1, len(allms)) / 1000
    s["lat_all_p95_s"] = p95(allms) / 1000
    s["lat_model_avg_s"] = sum(mms) / max(1, len(mms)) / 1000
    s["lat_model_median_s"] = (sorted(mms)[len(mms) // 2] / 1000) if mms else 0
    s["lat_model_p95_s"] = p95(mms) / 1000
    # tokens (answering model's own calls only)
    s["model_calls"] = sum(t["modelCalls"] for t in turns)
    s["input_tokens"] = sum(t["inputTokens"] for t in turns)
    s["output_tokens"] = sum(t["outputTokens"] for t in turns)
    s["cached_tokens"] = sum(t["cachedTokens"] for t in turns)
    s["calls_per_model_reply"] = s["model_calls"] / max(1, len(mt))
    s["in_per_model_reply"] = s["input_tokens"] / max(1, len(mt))
    s["out_per_model_reply"] = s["output_tokens"] / max(1, len(mt))
    s["in_per_call"] = s["input_tokens"] / max(1, s["model_calls"])
    s["system_chars"] = round(sum(x["usage"].get("systemChars", 0) for x in cs if x["usage"].get("systemChars")) / max(1, sum(1 for x in cs if x["usage"].get("systemChars"))))
    so = [o for x in cs for o in x.get("secondOpinions", [])]
    s["so_by_model"] = {m: dict(n=sum(1 for o in so if o["model"] == m), inp=sum(o["input"] for o in so if o["model"] == m), out=sum(o["output"] for o in so if o["model"] == m),
                               cached=sum(o["cached"] for o in so if o["model"] == m)) for m in set(o["model"] for o in so)}
    s["cost_own"] = cost(model, s["input_tokens"], s["output_tokens"], s["cached_tokens"])
    s["cost_second_opinion"] = sum(cost(m, v["inp"], v["out"], v["cached"]) for m, v in s["so_by_model"].items())
    s["cost_total"] = s["cost_own"] + s["cost_second_opinion"]
    s["cost_per_conv"] = s["cost_total"] / max(1, len(cs))
    s["cost_per_success"] = s["cost_total"] / s["successful_convs"] if s["successful_convs"] else float("nan")
    s["cost_per_1000_model_replies"] = s["cost_own"] / max(1, len(mt)) * 1000
    s["tools_hashes"] = sorted(set(x["usage"].get("toolsHash") for x in cs if x["usage"].get("toolsHash")))
    return s


def show(label, s):
    print(f"\n### {label}")
    for k, v in s.items():
        if isinstance(v, float):
            v = round(v, 4)
        print(f"  {k}: {v}")


if __name__ == "__main__":
    print("report", R["id"], R["status"], "temperature", R.get("temperature"))
    done = {(c["model"], c["mode"]): 0 for c in R["cells"]}
    for c in R["cells"]:
        done[(c["model"], c["mode"])] += len(c["results"])
    print("conversations done per model/mode:", done)
    for m in MODELS:
        for mode in MODES:
            if convs(m, mode):
                show(f"{m} / {mode} (all stores)", summarize(m, mode))
