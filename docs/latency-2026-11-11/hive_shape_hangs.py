import json, os, re, sys, time, statistics, uuid, collections, urllib.request, urllib.error
BASE = "https://api-cdn.thehive.ai/api/v3"
MODEL = "deepseek-ai/deepseek-v4.1-flash-1017"
def key():
    for line in open("/opt/multitenant/.env"):
        m = re.match(r"\s*GLM_API_KEY\s*=\s*(.*)\s*$", line)
        if m: return m.group(1).strip().strip('"').strip("'")
KEY = key()
RULES = "You are the salesperson of the online shop 'Test shop'. Answer in the customer's dialect, 1-3 short sentences, one question at most. Never invent products, prices or stock; prices come only from tools. Search first before talking about products.\n"
def catalog(n):
    cols = ["أبيض", "أسود", "كحلي", "رمادي", "بيج"]; sizes = ["S", "M", "L", "XL", "XXL"]
    lines = []
    for i in range(n):
        lines.append(f"- منتج رقم {i} موديل {i*7%97} قميص قطن رجالي (slug item-{i}-cotton) سعر {150+i*3} جنيه، ألوان: {', '.join(cols[:2+i%4])}؛ مقاسات: {', '.join(sizes[:2+i%4])}؛ المخزون: {i%9+1} قطع؛ الخامة قطن ١٠٠٪ صنع مصر؛ مناسب للصيف")
    return "SHOP CATALOG LIST:\n" + "\n".join(lines) + "\n"
def tools(n):
    out = []
    for i in range(n):
        out.append({"type": "function", "function": {"name": f"tool_{i}", "description": ("Tool %d: looks something up or does something for the shop's customer. Use it only when the customer asks for it; arguments must come from the customer's words. " % i) * 3,
            "parameters": {"type": "object", "properties": {f"arg{j}": {"type": "string", "description": "An argument the model fills from the chat, as short text."} for j in range(8)}, "required": ["arg0"]}}})
    out[0]["function"]["name"] = "search_products"
    return out

QUESTIONS = ["بكام القميص القطن الأبيض مقاس M؟", "عندكم ايه في القمصان؟", "عايز اتنين قميص وايه الفرق بينهم؟", "ينفع ادفع كاش؟", "عايز اطلب قميص اسود مقاس L", "التوصيل لمدينة نصر بكام؟"]
def call2(system, tl, effort, q, max_tokens=1000):
    body = {"model": MODEL, "max_tokens": max_tokens, "messages": [{"role": "system", "content": system}, {"role": "user", "content": q}]}
    if effort: body["reasoning_effort"] = effort
    if tl: body["tools"] = tl
    req = urllib.request.Request(BASE + "/chat/completions", data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=70) as r:
            d = json.loads(r.read()); u = d.get("usage", {}); det = u.get("completion_tokens_details") or {}
            ch = d["choices"][0]
            return dict(ms=int((time.time() - t0) * 1000), ok=True, out=u.get("completion_tokens", 0), reasoning=det.get("reasoning_tokens") or 0, finish=ch.get("finish_reason"), prompt=u.get("prompt_tokens", 0), cached=u.get("prompt_cache_hit_tokens", 0) or 0)
    except Exception as e:
        return dict(ms=int((time.time() - t0) * 1000), ok=False, err=type(e).__name__ + ":" + str(e)[:60])
system = RULES + catalog(60)
tl = tools(15)
rows = []
N = 36
for i in range(N):
    rows.append(call2(system, tl, "low", QUESTIONS[i % len(QUESTIONS)]))
    time.sleep(0.4)
ok = [r for r in rows if r["ok"]]; bad = [r for r in rows if not r["ok"]]
ms = sorted(r["ms"] for r in ok)
print(f"{N} calls, production-shaped (~{int(statistics.mean(r['prompt'] for r in ok))} prompt tokens, 15 tools, low, max_tokens 1000): ok {len(ok)}, failed {len(bad)}")
print(f"  ms: median {statistics.median(ms)/1000:.1f}s p90 {ms[int(0.9*(len(ms)-1)+0.5)]/1000:.1f}s max {ms[-1]/1000:.1f}s")
print("  slow calls (>= 10 s): tokens out/reasoning/finish ->", [(round(r['ms']/1000,1), r['out'], r['reasoning'], r['finish']) for r in sorted(ok, key=lambda r: -r['ms']) if r['ms'] >= 10000])
print("  fast calls (< 6 s) typical tokens: out %.0f reasoning %.0f" % (statistics.mean(r['out'] for r in ok if r['ms'] < 6000), statistics.mean(r['reasoning'] for r in ok if r['ms'] < 6000)))
print("  failures:", [(round(r['ms']/1000,1), r['err']) for r in bad])
by = collections.defaultdict(list)
for r in ok: by[r['finish']].append(r['ms'])
print("  by finish reason:", {k: (len(v), round(statistics.median(v)/1000,1)) for k, v in by.items()})
print("  ms per output token: median %.0f" % statistics.median(r['ms']/max(1,r['out']) for r in ok))
print("done")
