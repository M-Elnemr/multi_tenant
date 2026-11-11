import json, os, re, sys, time, statistics, uuid, urllib.request, urllib.error
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
def call(system, tl, effort, nonce=False):
    sysmsg = (("[req %s]\n" % uuid.uuid4().hex) if nonce else "") + system
    body = {"model": MODEL, "max_tokens": 300, "messages": [{"role": "system", "content": sysmsg}, {"role": "user", "content": "بكام القميص القطن الأبيض مقاس M؟"}]}
    if effort: body["reasoning_effort"] = effort
    if tl: body["tools"] = tl
    req = urllib.request.Request(BASE + "/chat/completions", data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            d = json.loads(r.read()); u = d.get("usage", {}); det = u.get("completion_tokens_details") or {}
            return int((time.time() - t0) * 1000), u.get("prompt_tokens", 0), u.get("prompt_cache_hit_tokens", (u.get("prompt_tokens_details") or {}).get("cached_tokens", 0)) or 0, u.get("completion_tokens", 0), det.get("reasoning_tokens") or 0
    except Exception as e:
        return -1, 0, 0, 0, 0
def run(label, system, tl, effort, nonce, n=6):
    rs = [call(system, tl, effort, nonce) for _ in range(n)]
    ok = [r for r in rs if r[0] > 0]
    if not ok: print(f"  {label:48} all failed"); return
    ms = sorted(r[0] for r in ok)
    print(f"  {label:48} median {statistics.median(ms)/1000:5.1f}s  p90 {ms[min(len(ms)-1,int(0.9*(len(ms)-1)+0.5))]/1000:5.1f}s  min {ms[0]/1000:4.1f}s | prompt {int(statistics.mean(r[1] for r in ok))} tok, cached {int(statistics.mean(r[2] for r in ok))}, out {int(statistics.mean(r[3] for r in ok))} (reasoning {int(statistics.mean(r[4] for r in ok))})")
    time.sleep(0.5)
small = RULES; med = RULES + catalog(30); big = RULES + catalog(75)
print("== effect of prompt size, tools, reasoning and cache (DeepSeek, one question, 6 runs each, sequential) ==")
run("A small prompt, no tools, low", small, None, "low", True)
run("B ~medium prompt, no tools, low, cache broken", med, None, "low", True)
run("C ~big prompt, no tools, low, cache broken", big, None, "low", True)
run("D ~big prompt, no tools, low, cache warm", big, None, "low", False)
run("E ~big + 15 tools, low, cache broken", big, tools(15), "low", True)
run("F ~big + 15 tools, low, cache warm", big, tools(15), "low", False)
run("G ~big + 15 tools, none (no reasoning), warm", big, tools(15), "none", False)
run("H ~big + 3 tools, low, warm", big, tools(3), "low", False)
print("done")
