import json, random, itertools, collections, math
from bench2 import *
random.seed(21)
print(f"TRIALS: {len(T)} -> {[t['id']+':'+t['status'] for t in T]}; conversations {sum(len(c['results']) for t in T for c in t['cells'])}")

def cat_of_error(e):
    e = e or ""
    if "Invalid variantId" in e or "No product has the slug" in e or "has no option" in e or "has no version" in e or "comes in more than one version" in e or "has no «" in e: return "item reference (variant / slug / options)"
    if any(k in e for k in ("valid mobile", "full delivery address", "WhatsApp", "customer's name", "Ask for")): return "details the customer had not given"
    if any(k in e for k in ("has not chosen", "never asked for", "asked for", "SOLD OUT", "Only ", "Wrong option", "minimum")): return "business locks (option, quantity, stock, minimum)"
    if any(k in e for k in ("no order summary", "not confirmed", "not answering", "Show the summary")): return "order sequence (place before summary / confirmation)"
    return "other"

def tool_table():
    print("\n== TOOL CALLS AND ERRORS (all conversations, both prompts, all trials pooled)")
    for m in MODELS:
        tc = [t for r in T for x in results(r, m) for t in x.get("toolCalls", [])]
        errs = [t for t in tc if not t["ok"]]
        by = collections.Counter(cat_of_error(t.get("error")) for t in errs)
        print(f"  {m:9s} calls {len(tc):4d} errors {len(errs):3d} ({pct(len(errs), len(tc)):.1f}%)  " + "; ".join(f"{k}: {v}" for k, v in by.most_common()))
    print("  first prepare_order of each conversation (calls it: first try ok / failed-then-recovered / never succeeded):")
    for m in MODELS:
        for mo in MODES:
            t, a, r, l = first_attempt(T, m, mo)
            print(f"    {m:9s} {mo:5s} {t:3d}: {a:3d} ({pct(a, t):.0f}%) / {r:2d} / {l:2d}")

def quality_tables():
    print("\n== QUALITY: judge pass rate on the 74 judged conversations per model+prompt (order scenarios are code-checked), per trial and pooled")
    for m in MODELS:
        for mo in MODES:
            per = [summary([t], m, mo, comparable_only=True) for t in T]
            pool = summary(T, m, mo, comparable_only=True)
            print(f"  {m:9s} {mo:5s} trials: " + "  ".join(f"{s['judge_pct']:.1f}%" for s in per) + f"   pooled {pool['judge_pct']:.1f}% ({pool['judge_pass']}/{pool['judge_of']})   fully-ok {pool['judged_ok']}/{pool['judged']}")
    print("\n== PAIRED BOOTSTRAP over conversations (per-conversation score averaged over trials), 95% interval, points")
    def cmap(m, mo):
        d = collections.defaultdict(list)
        for r in T:
            for x in results(r, m, mo, exclude=REG):
                if not is_order(x) and x.get("of"): d[(x["_store"], x["id"])].append(x["passed"] / x["of"])
        return {k: sum(v) / len(v) for k, v in d.items()}
    CM = {(m, mo): cmap(m, mo) for m in MODELS for mo in MODES}
    keys = sorted(set.intersection(*[set(v) for v in CM.values()]))
    def boot(d, n=4000):
        ms = sorted(sum(random.choice(d) for _ in d) / len(d) for _ in range(n)); return sum(d) / len(d), ms[int(.025 * n)], ms[int(.975 * n)]
    def cmp(a, b, label):
        d = [CM[a][k] - CM[b][k] for k in keys]; mu, lo, hi = boot(d)
        print(f"  {label:34s} {100 * mu:+5.1f} [{100 * lo:+5.1f}, {100 * hi:+5.1f}]  {'DIFFERENT' if lo > 0 or hi < 0 else 'within noise'}")
    print(f"  ({len(keys)} judged conversations)")
    for m in MODELS: cmp((m, "lean"), (m, "full"), f"{m}: lean - full")
    for mo in MODES:
        for a, b in itertools.combinations(MODELS, 2): cmp((a, mo), (b, mo), f"{a} - {b} ({mo})")
    for a, b in itertools.combinations(MODELS, 2):
        d = [(CM[(a, 'full')][k] + CM[(a, 'lean')][k]) / 2 - (CM[(b, 'full')][k] + CM[(b, 'lean')][k]) / 2 for k in keys]; mu, lo, hi = boot(d)
        print(f"  {a + ' - ' + b + ' (both prompts)':34s} {100 * mu:+5.1f} [{100 * lo:+5.1f}, {100 * hi:+5.1f}]  {'DIFFERENT' if lo > 0 or hi < 0 else 'within noise'}")
    print("\n== NOW vs PREVIOUS benchmark, same 74 judged conversations (previous = one run; now = pooled trials)")
    for m in MODELS:
        for mo in MODES:
            pv = [x for x in results(PREV, m, mo) if not is_order(x)]
            ppct = pct(sum(x["passed"] for x in pv), sum(x["of"] for x in pv)); now = summary(T, m, mo, comparable_only=True)
            print(f"  {m:9s} {mo:5s} previous {ppct:5.1f}%  now {now['judge_pct']:5.1f}%  ({now['judge_pct'] - ppct:+.1f})")
    if len(T) > 1:
        print("\n== TRIAL-TO-TRIAL VARIATION (same scenarios, same settings): how much does one run move?")
        diffs = []
        for m in MODELS:
            for mo in MODES:
                a, b = summary([T[0]], m, mo, comparable_only=True)["judge_pct"], summary([T[1]], m, mo, comparable_only=True)["judge_pct"]; diffs.append(abs(a - b))
                print(f"  {m:9s} {mo:5s} trial1 {a:.1f}%  trial2 {b:.1f}%  |diff| {abs(a - b):.1f}")
        print(f"  mean |trial1 - trial2| over the 6 cells = {sum(diffs) / len(diffs):.1f} points; largest {max(diffs):.1f}")
        same = tot = 0
        for m in MODELS:
            for mo in MODES:
                a = {(x['_store'], x['id']): x['passed'] for x in results(T[0], m, mo, exclude=REG) if not is_order(x)}
                b = {(x['_store'], x['id']): x['passed'] for x in results(T[1], m, mo, exclude=REG) if not is_order(x)}
                for k in a:
                    if k in b: tot += 1; same += a[k] == b[k]
        print(f"  the same conversation scored exactly the same in both trials: {same}/{tot} = {pct(same, tot):.0f}%")

def shop_tables():
    print("\n== PER SHOP (judge pass rate pooled over trials, 74-conv set; full / lean)  and orders ok (main 6 + regression 12, all trials)")
    for st in STORES:
        line = []
        for m in MODELS:
            f = summary(T, m, "full", st, comparable_only=True); l = summary(T, m, "lean", st, comparable_only=True)
            line.append(f"{m} {f['judge_pct']:.1f}/{l['judge_pct']:.1f}")
        print(f"  {st:16s} " + "   ".join(line))

def orders_tables():
    print("\n== ORDERS (code-checked): main 6 scenarios and quantity regression 12, per model+prompt, pooled over trials")
    for m in MODELS:
        for mo in MODES:
            main = [x for r in T for x in results(r, m, mo, ids=ORDER_MAIN)]; reg = [x for r in T for x in results(r, m, mo, ids=REG)]
            def line(xs): return f"{sum(order_ok(x) for x in xs)}/{len(xs)} all correct, quantity ok {sum(x['orderCheck']['itemAndQuantityCorrect'] for x in xs)}/{len(xs)}, contact ok {sum(x['orderCheck']['contactDetailsCorrect'] for x in xs)}/{len(xs)}"
            print(f"  {m:9s} {mo:5s} main: {line(main)} | regression: {line(reg)}")
    print("  failures by scenario (all models, modes, trials):", dict(collections.Counter(x['id'] for r in T for x in results(r) if is_order(x) and not x['orderCheck']['allCorrect'])))

def reliability_tables():
    print("\n== RELIABILITY (pooled over trials and prompts)")
    print("  %-9s %6s | %9s %9s | %10s %8s %6s | %5s %5s | rag tool missing / search unavailable (replies)" % ("model", "tools", "toolErr", "modelFail", "failedAtt", "hedges", "http", "2ndOp", "adopt"))
    for m in MODELS:
        s = [summary(T, m, mo) for mo in MODES]
        calls = sum(x["tool_calls"] for x in s); er = sum(x["tool_errors"] for x in s)
        he = collections.Counter()
        for x in s: he.update(x["http_errors"])
        print("  %-9s %6d | %4d %4.1f%% %9d | %10d %8d %6s | %5d %5d | %d / %d (of %d model replies)" % (m, calls, er, pct(er, calls), sum(x["failures"] for x in s), sum(x["failed_attempts"] for x in s), sum(x["hedges"] for x in s), dict(he), sum(x["so_n"] for x in s), sum(x["so_adopted"] for x in s), sum(x["rag_missing_replies"] for x in s), sum(x["rag_unavail_replies"] for x in s), sum(x["model_replies"] for x in s)))
    for t in T:
        a, b = t["ragHealthAtStart"], t["ragHealthAtEnd"]
        print(f"  embeddings in {t['id']}: queries {b['queries'] - a['queries']}, ok {b['queriesOk'] - a['queriesOk']}, failed {b['queriesFailed'] - a['queriesFailed']}, recovered by retry {b['recoveredByRetry'] - a['recoveredByRetry']}; failures by kind (cumulative) {b.get('failuresByKind')}; latency {b.get('latencyMs')}")

def perf_cost_tables():
    print("\n== PERFORMANCE and COST per model+prompt, pooled over trials")
    print("%-9s %-5s | %5s %5s %5s | in/reply out/reply tokens/conv reasoning(tok) | $in(uncached) $cached $out $2nd-opinion = $total | $/conv $/success | judge tok in/out" % ("model", "prompt", "avg", "p50", "p95"))
    for m in MODELS:
        for mo in MODES:
            s = summary(T, m, mo)
            print("%-9s %-5s | %5.1f %5.1f %5.1f | %8.0f %9.0f %10.0f %10d | %.4f %.4f %.4f %.4f = %.4f | %.5f %.5f | %d/%d" % (m, mo, s["lat_avg"], s["lat_p50"], s["lat_p95"], s["inp_per_reply"], s["out_per_reply"], s["tokens_per_conv"], s["reasoning_tokens"], s["cost_in_uncached"], s["cost_cached"], s["cost_out"], s["so_cost"], s["cost_total"], s["cost_per_conv"], s["cost_per_success"], s["judge_tok_in"], s["judge_tok_out"]))
    j = [summary(T, m, mo) for m in MODELS for mo in MODES]
    ji, jo = sum(x["judge_tok_in"] for x in j), sum(x["judge_tok_out"] for x in j)
    print(f"  judge (Claude) in total: {ji:,} in / {jo:,} out tokens = ${(ji * JUDGE_PRICE[0] + jo * JUDGE_PRICE[1]) / 1e6:.4f} (kept apart from the model costs)")

if __name__ == "__main__":
    tool_table(); quality_tables(); shop_tables(); orders_tables(); reliability_tables(); perf_cost_tables()
