import json, random, collections, math, itertools, re
import bench2
from bench2 import *
random.seed(9)
LOW = bench2.T
PROD = [json.load(open(f"prod-trial{i}.json")) for i in (1, 2)]
def per_conv(reps, m, mo):
    d = collections.defaultdict(list)
    for r in reps:
        for x in results(r, m, mo, exclude=REG):
            if not is_order(x) and x.get("of"): d[(x["_store"], x["id"])].append(x["passed"] / x["of"])
    return {k: sum(v) / len(v) for k, v in d.items()}
def boot(d, n=4000):
    ms = sorted(sum(random.choice(d) for _ in d) / len(d) for _ in range(n)); return sum(d) / len(d), ms[int(.025 * n)], ms[int(.975 * n)]
print("== SAME MODEL, reasoning ON (this run) minus production reasoning (previous re-run): judge points over the same 74 conversations, 2 trials each")
print("   GLM is the CONTROL: its setting (low) did not change, so its difference is pure run-to-run noise")
for m in MODELS:
    for mo in MODES + ["both"]:
        if mo == "both":
            a = {k: (per_conv(LOW, m, "full")[k] + per_conv(LOW, m, "lean")[k]) / 2 for k in per_conv(LOW, m, "full")}
            b = {k: (per_conv(PROD, m, "full")[k] + per_conv(PROD, m, "lean")[k]) / 2 for k in per_conv(PROD, m, "full")}
        else: a, b = per_conv(LOW, m, mo), per_conv(PROD, m, mo)
        ks = sorted(set(a) & set(b)); d = [a[k] - b[k] for k in ks]; mu, lo, hi = boot(d)
        print(f"  {m:9s} {mo:5s} {100*mu:+5.1f} [{100*lo:+5.1f}, {100*hi:+5.1f}]  {'DIFFERENT' if lo > 0 or hi < 0 else 'within noise'}")
print("\n== speed and cost of switching reasoning on (pooled both prompts)")
for m in MODELS:
    a = [summary(LOW, m, mo) for mo in MODES]; b = [summary(PROD, m, mo) for mo in MODES]
    def agg(ss, k): return sum(s[k] for s in ss)
    def lat(ss): return sum(s["lat_avg"] * s["model_replies"] for s in ss) / sum(s["model_replies"] for s in ss)
    print(f"  {m:9s} latency avg {lat(b):.1f}s -> {lat(a):.1f}s ({lat(a)/lat(b):.2f}x) | P95 {sum(s['lat_p95'] for s in b)/2:.1f} -> {sum(s['lat_p95'] for s in a)/2:.1f}s | output tokens/reply {agg(b,'out')/agg(b,'model_replies'):.0f} -> {agg(a,'out')/agg(a,'model_replies'):.0f} | cost per success ${agg(b,'cost_total')/agg(b,'successes'):.5f} -> ${agg(a,'cost_total')/agg(a,'successes'):.5f} ({(agg(a,'cost_total')/agg(a,'successes'))/(agg(b,'cost_total')/agg(b,'successes')):.2f}x) | tool errors {100*agg(b,'tool_errors')/agg(b,'tool_calls'):.1f}% -> {100*agg(a,'tool_errors')/agg(a,'tool_calls'):.1f}% | judge {100*sum(s['judge_pass'] for s in b)/sum(s['judge_of'] for s in b):.1f}% -> {100*sum(s['judge_pass'] for s in a)/sum(s['judge_of'] for s in a):.1f}%")
print("\n== hedged twin requests (a slow call sent a second time after 8 s) and provider calls, this run")
for m in MODELS:
    a = [summary(LOW, m, mo) for mo in MODES]; print(f"  {m:9s} hedged {sum(s['hedges'] for s in a)} of {sum(s['provider_calls'] for s in a)} calls ({100*sum(s['hedges'] for s in a)/sum(s['provider_calls'] for s in a):.0f}%), unanswered {sum(s['failed_attempts'] for s in a)}")
print("\n== reasoning actually used (this run)")
for m in MODELS:
    xs = [x for r in LOW for x in results(r, m)]; calls = sum(x['provider']['calls'] for x in xs); wr = sum(x['provider']['callsWithReasoning'] for x in xs); rt = sum(x['provider']['reasoningTokens'] for x in xs)
    print(f"  {m:9s} calls with reasoning {wr}/{calls} ({100*wr/calls:.0f}%), reasoning tokens {rt:,} (avg {rt/max(1,wr):.0f}/call, {rt/max(1,sum(len([t for t in x['turns'] if t['path']!='CODE']) for x in xs)):.0f}/reply), finish reasons {dict(sum((collections.Counter(x['provider']['finishReasons']) for x in xs),collections.Counter()))}")
    print(f"             request sent: {json.dumps({k: v for k, v in LOW[0]['cells'][[c['model'] for c in LOW[0]['cells']].index(m)]['results'][4]['provider']['request'].items() if k != 'toolsInRequest'}, ensure_ascii=False)}")
print("\n== failure classification (judged conversations not fully successful), this run")
all_x = [x for t in LOW for x in results(t) if not is_order(x)]
fails = collections.Counter(); tot = collections.Counter()
for x in all_x:
    for ch in x.get('checks', []):
        k = (x['id'], ch['expect'][:80]); tot[k] += 1
        if not ch['pass']: fails[k] += 1
structural = {k for k in tot if fails[k] == tot[k] and tot[k] >= 12}
def classify(x):
    if judged_ok(x): return None
    failing = [ch for ch in x.get('checks', []) if not ch['pass']]
    if all(t['path'] == 'CODE' for t in x['turns']): return "backend/business logic (instant code answer)"
    if any(t.get('ragUnavailable') for t in x['turns']): return "RAG failure"
    if failing and all((x['id'], ch['expect'][:80]) in structural for ch in failing): return "judge limitation"
    if any(not t['ok'] for t in x.get('toolCalls', [])): return "tool integration (a tool call was refused)"
    return "model quality"
cl = {m: collections.Counter() for m in MODELS}
for x in all_x:
    c = classify(x)
    if c: cl[x['_model']][c] += 1
for c in ["model quality", "judge limitation", "backend/business logic (instant code answer)", "tool integration (a tool call was refused)", "RAG failure"]:
    print(f"  {c:48s} glm {cl['glm'][c]:3d}  deepseek {cl['deepseek'][c]:3d}  qwen {cl['qwen'][c]:3d}")
print(f"  structurally unpassable rubric lines: {len(structural)} of {len(tot)}")
kw = re.compile(r"(dialect|gulf|khaleeji|msa|formal arabic|modern standard|فصحى|gender|masculine|feminine|wrong word|odd word|unnatural|mistransl|مذكر|مؤنث|wording)", re.I)
ar = collections.Counter(); n = collections.Counter()
for x in all_x:
    for ch in x.get('checks', []):
        n[x['_model']] += 1
        if not ch['pass'] and kw.search(ch.get('why', '') + ' ' + ch['expect']): ar[x['_model']] += 1
print("  wording/dialect/gender named by the judge:", {m: f"{ar[m]} of {n[m]} ({100*ar[m]/n[m]:.1f}%)" for m in MODELS})
bycat = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0]))
for x in all_x: b = bycat[x['category']][x['_model']]; b[0] += x['passed']; b[1] += x['of']
rows = [(max(pct(*d[m]) for m in MODELS) - min(pct(*d[m]) for m in MODELS), c, {m: round(pct(*d[m])) for m in MODELS}) for c, d in bycat.items() if all(d[m][1] >= 12 for m in MODELS)]
print("  categories where models differ most:", [(r[1], r[2]) for r in sorted(rows, reverse=True)[:5]])
