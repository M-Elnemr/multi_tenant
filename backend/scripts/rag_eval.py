#!/usr/bin/env python3
"""Measures search by meaning (RAG) for real shops before a setting is changed: reads each shop's catalog from the public API, builds the same product text the
backend embeds (KnowledgeIndex.productText), embeds it and labelled customer questions with the configured provider, and prints how often the right product is
first, in the first 3 and in the first 6, plus the best score of questions about things the shop does NOT sell (those show why a fixed score floor cannot tell
"we don't have it" apart: the seller model decides that from the MEANING_ONLY_NOTE).

usage: EMBED_API_KEY=... EMBED_BASE_URL=... rag_eval.py [rag_cases.json] [--model qwen3.7-text-embedding] [--dims 512] [--misses]
  rag_cases.json: {"shop-slug": [["question", ["right product name", ...]], ...]}; a question starting with "NEG " has no right product.
  Baseline 2026-11-03 (nile-style + beit-elshawarma, 39 questions + 7 negatives): first 0.64-0.67, top 3 0.77, top 6 0.87; negatives up to 0.56, right answers from 0.42.
  Tried and NOT better that day: qwen3-rerank over the top 20 (no gain, +1.2 s), trimmed product texts, model-written search words per product (no gain, invented words).
Run it where the key lives (the server):  scp rag_eval.py rag_cases.json host:/tmp/ &&
  ssh host 'cd /opt/multitenant && set -a && . ./.env && set +a && python3 /tmp/rag_eval.py /tmp/rag_cases.json --misses; rm /tmp/rag_eval.py /tmp/rag_cases.json'
"""
import json, math, os, sys, time, urllib.error, urllib.parse, urllib.request

args = [a for a in sys.argv[1:] if not a.startswith("--")]
opt = lambda k, d: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
CASES_FILE = args[0] if args else os.path.join(os.path.dirname(os.path.abspath(__file__)), "rag_cases.json")
MODEL, DIMS = opt("--model", os.environ.get("EMBED_MODEL") or "qwen3.7-text-embedding"), int(opt("--dims", os.environ.get("EMBED_DIMENSIONS") or "512"))
KEY, BASE = os.environ["EMBED_API_KEY"], os.environ.get("EMBED_BASE_URL", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1").rstrip("/")
AUD = {"MEN": "رجالي", "WOMEN": "حريمي", "BOYS": "ولادي أطفال", "GIRLS": "بناتي أطفال", "BABY": "بيبي رضع", "ALL": "للكل"}

def get(u):
    with urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "rag-eval"}), timeout=30) as r: return json.loads(r.read())

def documents(shop):
    base, docs, page = f"https://{shop}.elmanassa.shop/api/v1/shop", [], 1
    while True:
        d = get(f"{base}/products?pageSize=50&page={page}")
        for p in d["data"]:
            x = get(f"{base}/products/{urllib.parse.quote(p['slug'])}")
            L = []
            line = lambda label, v: v and L.append((label + ": " if label else "") + " ".join(str(v).split()))
            tx = x.get("taxonomy") or []
            line("الماركة", x.get("brand")); line("الفئة", " › ".join(f"{a['nameAr']} / {a['nameEn']}" for a in tx)); line("لمين", AUD.get(x.get("audience")))
            line(None, x.get("shortDescription")); line(None, (x.get("description") or "")[:1500])
            sp = [f"{s['k']}: {s['v']}" for s in (x.get("specs") or []) if s.get("k")]
            line("المواصفات", "؛ ".join(sp)); line("الاختيارات", " | ".join(f"{o['name']}: {'، '.join(o['values'])}" for o in (x.get("options") or [])))
            line("كلمات", "، ".join(x.get("tags") or [])); line("دليل المقاسات", (x.get("sizeGuide") or "")[:400])
            docs.append((x["name"], x["name"] + "\n" + "\n".join(L)))
        if not d["meta"]["hasNext"]: return docs
        page += 1

def embed(texts):
    out = []
    for i in range(0, len(texts), 10):
        body = json.dumps({"model": MODEL, "input": texts[i:i + 10], "dimensions": DIMS, "encoding_format": "float"}).encode()
        for attempt in range(4):
            try:
                req = urllib.request.Request(BASE + "/embeddings", data=body, headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=40) as r: data = json.loads(r.read())["data"]
                break
            except urllib.error.HTTPError as e:
                if e.code != 429 and e.code < 500: sys.exit(f"HTTP {e.code}: {e.read().decode()[:300]}")
                time.sleep(1 + attempt)
        vs = [None] * len(texts[i:i + 10])
        for x in data: vs[x["index"]] = x["embedding"]
        for v in vs: n = math.sqrt(sum(t * t for t in v)); out.append([t / n for t in v])
    return out

cases = json.load(open(CASES_FILE))
first = top3 = top6 = n = 0; right, wrong = [], []
for shop, qs in cases.items():
    docs = documents(shop)
    dv, qv = embed([t for _, t in docs]), embed([q.replace("NEG ", "") for q, _ in qs])
    for (q, want), v in zip(qs, qv):
        ranked = sorted(((sum(a * b for a, b in zip(v, d)), docs[i][0]) for i, d in enumerate(dv)), reverse=True)[:6]
        if not want: wrong.append(ranked[0][0]); mark = "N"
        else:
            n += 1; right.append(ranked[0][0])
            pos = next((i for i, (_, name) in enumerate(ranked) if name in want), None)
            first += pos == 0; top3 += pos is not None and pos < 3; top6 += pos is not None; mark = " " if pos is not None and pos < 3 else "✗"
        if "--misses" in sys.argv and mark != " ": print(f"{mark} [{shop}] {q} → " + "; ".join(f"{name} {s:.2f}" for s, name in ranked[:4]))
    print(f"{shop}: {len(docs)} products, {len(qs)} questions", file=sys.stderr)
print(f"{MODEL}@{DIMS}: first {first / n:.2f}  top 3 {top3 / n:.2f}  top 6 {top6 / n:.2f}  ({n} questions) | right answers score from {min(right):.2f}, "
      f"questions about what the shop does not sell up to {max(wrong):.2f}" if wrong else "")
