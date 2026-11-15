#!/usr/bin/env python3
"""Embeds the FIXED dataset with a real open-weight embedding model and writes build/retrieval/emb-<name>.json for evaluate.py: per shop and query the ranking (product ids, best
first) by cosine similarity, plus the single-query latency. Runs on CPU with a bounded thread count, one model per invocation, no remote code (trust_remote_code is never used).
usage: embed_bench.py <hf-model-id> <short-name> [--threads 4] [--query-prefix "query: "] [--doc-prefix "passage: "] [--prompt-name query] [--dims 0]"""
import json, os, statistics, sys, time
here = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
model_id, short = args[0], args[1]
opt = lambda k, d: args[args.index(k) + 1] if k in args else d
threads = int(opt("--threads", "4")); qp, dp, prompt_name, dims = opt("--query-prefix", ""), opt("--doc-prefix", ""), opt("--prompt-name", ""), int(opt("--dims", "0"))
import numpy as np, torch
torch.set_num_threads(threads)
from sentence_transformers import SentenceTransformer
import huggingface_hub, sentence_transformers, transformers
data = json.load(open(os.path.join(here, "../../src/test/resources/retrieval/dataset.json"), encoding="utf-8"))
info = huggingface_hub.model_info(model_id)
t0 = time.time(); model = SentenceTransformer(model_id, device="cpu"); load_s = time.time() - t0
if dims: model.truncate_dim = dims
def enc(texts, query):
    kw = {"normalize_embeddings": True, "show_progress_bar": False, "convert_to_numpy": True}
    if query and prompt_name: kw["prompt_name"] = prompt_name
    return model.encode([(qp if query else dp) + t for t in texts], **kw)
out = {"model": short, "hf_id": model_id, "revision": info.sha, "license": (info.card_data.get("license") if info.card_data else None), "dims": int(model.get_sentence_embedding_dimension() if not dims else dims),
       "max_seq_length": model.max_seq_length, "parameters_million": round(sum(p.numel() for p in model.parameters()) / 1e6), "threads": threads, "device": "cpu", "load_seconds": round(load_s, 1),
       "versions": {"torch": torch.__version__, "sentence_transformers": sentence_transformers.__version__, "transformers": transformers.__version__}, "shops": {}}
doc_seconds, lat = 0.0, []
for shop in data["shops"]:
    t0 = time.time(); D = enc([p["title"] + ". " + p["body"] for p in shop["products"]], False); doc_seconds += time.time() - t0
    ids = [p["id"] for p in shop["products"]]; rows = []
    for q in shop["queries"]:
        t0 = time.time(); v = enc([q["text"]], True)[0]; ms = (time.time() - t0) * 1000; lat.append(ms)
        sims = D @ v; order = np.argsort(-sims)[:10]
        rows.append({"ranking": [ids[i] for i in order], "scores": [round(float(sims[i]), 4) for i in order], "latency_ms": round(ms, 1)})
    out["shops"][shop["name"]] = rows
out["index_seconds_for_90_products"] = round(doc_seconds, 1)
out["query_latency_ms"] = {"p50": round(statistics.median(lat), 1), "p95": round(sorted(lat)[int(0.95 * len(lat)) - 1], 1), "n": len(lat)}
out["vector_bytes_per_product_float32"] = out["dims"] * 4
dest = os.path.join(here, "../../build/retrieval", f"emb-{short}.json"); os.makedirs(os.path.dirname(dest), exist_ok=True)
json.dump(out, open(dest, "w"), ensure_ascii=False)
print(short, "dims", out["dims"], "license", out["license"], "rev", info.sha[:8], "load", out["load_seconds"], "s; query p50/p95 ms", out["query_latency_ms"]["p50"], out["query_latency_ms"]["p95"], "; index 90 docs", out["index_seconds_for_90_products"], "s")
