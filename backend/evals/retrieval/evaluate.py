#!/usr/bin/env python3
"""The ONE metric implementation for the retrieval benchmark: scores every strategy and every embedding model identically over the fixed dataset.
Inputs: src/test/resources/retrieval/dataset.json, build/retrieval/rankings.json (lexical strategies, exported by RetrievalDatasetExportTest) and optionally
build/retrieval/emb-<model>.json (embedding rankings, from embed_bench.py). Metrics over the ANSWERABLE queries: Hit@1/3/5 (a relevant product in the first K; equals recall
when one product is relevant), Precision@3/5, Recall@5 (found / relevant), MRR, NDCG@5 (binary relevance). Not-sold queries are reported separately: how often a strategy
returns anything at all. usage: python3 evaluate.py [--md out.md] [--json out.json] [--shop NAME]"""
import glob, json, math, os, sys
here = os.path.dirname(os.path.abspath(__file__))
data = json.load(open(os.path.join(here, "../../src/test/resources/retrieval/dataset.json"), encoding="utf-8"))
build = os.path.join(here, "../../build/retrieval")
lex = json.load(open(os.path.join(build, "rankings.json"), encoding="utf-8"))
emb = {}
for f in sorted(glob.glob(os.path.join(build, "emb-*.json"))):
    d = json.load(open(f, encoding="utf-8")); emb[d["model"]] = d

def rrf(*rankings, k=60, limit=10):
    score = {}
    for r in rankings:
        for i, pid in enumerate(r): score[pid] = score.get(pid, 0) + 1.0 / (k + i + 1)
    return [p for p, _ in sorted(score.items(), key=lambda kv: -kv[1])][:limit]

def with_identity(identity, rest, limit=10):
    out = list(identity[:2])
    for p in rest:
        if p not in out and len(out) < limit: out.append(p)
    return out

def strategies_for(shop, qi):
    L = lex[shop]["rankings"][qi]
    s = {"legacy word search (baseline)": L["legacy"], "normalising word search": L["normalising"], "BM25 word search": L["bm25"], "trigram vector stand-in": L["trigram"],
         "identity + trigram stand-in": with_identity(L["identity"], L["trigram"]), "hybrid (identity + RRF trigram + normalising)": with_identity(L["identity"], rrf(L["trigram"], L["normalising"]))}
    for m, e in emb.items():
        E = e["shops"][shop][qi]["ranking"]
        s[f"{m}: vectors only"] = E
        s[f"{m}: identity + vectors"] = with_identity(L["identity"], E)
        s[f"{m}: hybrid (identity + RRF vectors + normalising)"] = with_identity(L["identity"], rrf(E, L["normalising"]))
        s[f"{m}: RRF vectors + BM25"] = rrf(E, L["bm25"])
    return s

def score(rows):
    n = len(rows); m = {"n": n, "hit1": 0, "hit3": 0, "hit5": 0, "p3": 0, "p5": 0, "recall5": 0, "mrr": 0, "ndcg5": 0}
    for ranking, rel in rows:
        rel = set(rel); top = ranking[:5]
        first = next((i + 1 for i, p in enumerate(ranking) if p in rel), 0)
        m["hit1"] += first == 1; m["hit3"] += 0 < first <= 3; m["hit5"] += 0 < first <= 5
        m["p3"] += sum(p in rel for p in ranking[:3]) / 3; m["p5"] += sum(p in rel for p in top) / 5
        m["recall5"] += sum(p in rel for p in top) / len(rel)
        m["mrr"] += 1 / first if first else 0
        dcg = sum(1 / math.log2(i + 2) for i, p in enumerate(top) if p in rel); ideal = sum(1 / math.log2(i + 2) for i in range(min(len(rel), 5)))
        m["ndcg5"] += dcg / ideal if ideal else 0
    return {k: (v / n if k != "n" and n else v) for k, v in m.items()}

def run(shop_filter=None):
    per = {}   # strategy -> {"all": rows, category: rows}
    notsold = {}
    for shop in data["shops"]:
        if shop_filter and shop["name"] != shop_filter: continue
        for qi, q in enumerate(shop["queries"]):
            for name, ranking in strategies_for(shop["name"], qi).items():
                if not q["relevant"]:
                    notsold.setdefault(name, []).append(bool(ranking)); continue
                d = per.setdefault(name, {}); d.setdefault("ALL", []).append((ranking, q["relevant"])); d.setdefault(q["category"], []).append((ranking, q["relevant"]))
    return {name: {c: score(rows) for c, rows in d.items()} for name, d in per.items()}, {n: (sum(v), len(v)) for n, v in notsold.items()}

def fmt(m): return f"{m['hit1']:.2f} / {m['hit5']:.2f} / {m['mrr']:.2f}"

if __name__ == "__main__":
    args = sys.argv[1:]
    shop = args[args.index("--shop") + 1] if "--shop" in args else None
    res, notsold = run(shop)
    cats = [c for c in next(iter(res.values())).keys() if c != "ALL"]
    lines = [f"Queries scored: {next(iter(res.values()))['ALL']['n']} answerable" + (f" in shop {shop}" if shop else " across 3 shops") + f"; not-sold queries: {next(iter(notsold.values()))[1]}", "",
             "| strategy | n | Hit@1 | Hit@3 | Hit@5 | P@3 | P@5 | Recall@5 | MRR | NDCG@5 |", "|---|---|---|---|---|---|---|---|---|---|"]
    for name, d in res.items():
        a = d["ALL"]; lines.append(f"| {name} | {a['n']} | {a['hit1']:.3f} | {a['hit3']:.3f} | {a['hit5']:.3f} | {a['p3']:.3f} | {a['p5']:.3f} | {a['recall5']:.3f} | {a['mrr']:.3f} | {a['ndcg5']:.3f} |")
    lines += ["", "Hit@1 / Hit@5 / MRR by category:", "", "| strategy | " + " | ".join(cats) + " |", "|---|" + "---|" * len(cats)]
    for name, d in res.items(): lines.append(f"| {name} | " + " | ".join(fmt(d[c]) if c in d else "-" for c in cats) + " |")
    lines += ["", "Not-sold queries: how many returned something (a vector search always does):", ""] + [f"- {n}: {a}/{b}" for n, (a, b) in notsold.items()]
    text = "\n".join(lines); print(text)
    if "--md" in args: open(args[args.index("--md") + 1], "w", encoding="utf-8").write(text)
    if "--json" in args: json.dump({"metrics": res, "notsold": notsold}, open(args[args.index("--json") + 1], "w"), ensure_ascii=False, indent=1)
