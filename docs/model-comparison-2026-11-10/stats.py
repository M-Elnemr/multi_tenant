import json, random, collections, itertools, statistics
from analyze import *
random.seed(7)
def key(c,x): return (c['store'], x['id'])
def conv_map(model, mode):
    return {key(c,x): x for c in cells(model,mode) for x in c['results'] if 'turns' in x}
CM = {(m,mo): conv_map(m,mo) for m in MODELS for mo in MODES}
keys = sorted(set.intersection(*[set(v) for v in CM.values()]))
print("conversations present in all 6 cells:", len(keys))

def frac(x): return x['passed']/x['of'] if x.get('of') else 0.0
def succ(x): return 1.0 if success(x) else 0.0
def boot(diffs, n=5000):
    ms=[]
    for _ in range(n):
        s=[random.choice(diffs) for _ in diffs]; ms.append(sum(s)/len(s))
    ms.sort(); return sum(diffs)/len(diffs), ms[int(.025*n)], ms[int(.975*n)]

def compare(a, b, ks, f, label):
    d=[f(CM[a][k])-f(CM[b][k]) for k in ks]
    mean,lo,hi=boot(d)
    better=sum(1 for v in d if v>1e-9); worse=sum(1 for v in d if v<-1e-9)
    sig = "SIGNIFICANT" if (lo>0 or hi<0) else "within noise"
    print("  %-34s mean diff %+6.1f pts  95%% CI [%+6.1f, %+6.1f]  better/worse/tie %2d/%2d/%2d  %s" % (label, 100*mean,100*lo,100*hi,better,worse,len(d)-better-worse,sig))

print("\n== judge score (fraction of rubric checks passed per conversation), paired over the same conversations ==")
print("-- lean minus full, per model")
for m in MODELS: compare((m,'lean'),(m,'full'),keys,frac,f"{m}: lean - full")
print("-- model minus model, full prompt")
for a,b in itertools.combinations(MODELS,2): compare((a,'full'),(b,'full'),keys,frac,f"{a} - {b} (full)")
print("-- model minus model, lean prompt")
for a,b in itertools.combinations(MODELS,2): compare((a,'lean'),(b,'lean'),keys,frac,f"{a} - {b} (lean)")
print("\n== fully successful conversation (all checks + order + no failure), paired ==")
for m in MODELS: compare((m,'lean'),(m,'full'),keys,succ,f"{m}: lean - full")
for a,b in itertools.combinations(MODELS,2): compare((a,'full'),(b,'full'),keys,succ,f"{a} - {b} (full)")

# RAG-clean subset: every cell of that conversation offered the RAG tool at its last reply
def rag15(x): return x['usage'].get('toolsHash','').endswith(':15')
clean=[k for k in keys if all(rag15(CM[c][k]) for c in CM)]
print("\nRAG-clean conversations (RAG tool offered in all 6 cells):", len(clean), "of", len(keys))
def clean_pct(m,mode,ks):
    xs=[CM[(m,mode)][k] for k in ks]; return pct(sum(x['passed'] for x in xs), sum(x['of'] for x in xs))
for m in MODELS: print("  %-9s full %5.1f%%  lean %5.1f%%" % (m, clean_pct(m,'full',clean), clean_pct(m,'lean',clean)))
# second opinion free
def no_so(x): return not any(t.get('secondOpinionAdopted') for t in x['turns'])
nso=[k for k in keys if all(no_so(CM[c][k]) for c in CM)]
print("\nconversations with no second-opinion reply in any cell:", len(nso), "of", len(keys))
for m in MODELS: print("  %-9s full %5.1f%%  lean %5.1f%%" % (m, clean_pct(m,'full',nso), clean_pct(m,'lean',nso)))
# model-only (exclude conversations answered wholly by code)
mo=[k for k in keys if not code_only(CM[('glm','full')][k])]
print("\nconversations needing a model (not answered by code):", len(mo))
for m in MODELS: print("  %-9s full %5.1f%%  lean %5.1f%%" % (m, clean_pct(m,'full',mo), clean_pct(m,'lean',mo)))

print("\n== cost with caching ignored (tokens x list price; isolates the token effect) ==")
for m in MODELS:
    for mode in MODES:
        s=summarize(m,mode); pi,po,pc=PRICE[m]
        nocache=(s['input_tokens']*pi+s['output_tokens']*po)/1e6
        print("  %-9s %-5s tokens-only cost $%.4f   actual (with cache hits) $%.4f   cache saved %.0f%%" % (m,mode,nocache,s['cost_own'],100*(1-s['cost_own']/nocache)))
print("\n== lean vs full token change ==")
for m in MODELS:
    f=summarize(m,'full'); l=summarize(m,'lean')
    print("  %-9s input tokens %+5.1f%%  output tokens %+5.1f%%  cost %+5.1f%%  latency(model avg) %+5.1f%%  calls/reply %.2f -> %.2f" % (m,100*(l['input_tokens']/f['input_tokens']-1),100*(l['output_tokens']/f['output_tokens']-1),100*(l['cost_own']/f['cost_own']-1),100*(l['lat_model_avg_s']/f['lat_model_avg_s']-1),f['calls_per_model_reply'],l['calls_per_model_reply']))

print("\n== judge by category group (checks passed %), full / lean ==")
groups={'greeting/identity':{'greeting','are_you_ai','human_request','respect_name'},'trust/delivery/policy':{'trust','cod_fear','delivery','out_of_area','no_returns','returns','shipping','delivery_time','hours','unknown_question'},
 'price/negotiation/coupon':{'price_objection','negotiation','coupon','sale'},'safety (injection etc.)':{'injection','sensitive','off_topic','rude_customer','angry'},
 'product/shop-specific':{'options','product_knowledge','need_first','out_of_stock','vegetarian','who_is_it_for','size_advice','shoes','full_outfit','budget_family'},'order flow (judge)':{'order_flow'}}
for g,cats in groups.items():
    line=[]
    for m in MODELS:
        out=[]
        for mode in MODES:
            xs=[x for x in CM[(m,mode)].values() if x['category'] in cats]
            out.append("%5.1f%%"%pct(sum(x['passed'] for x in xs),sum(x['of'] for x in xs)))
        line.append(f"{m} {out[0]}/{out[1]}")
    n=len([x for x in CM[('glm','full')].values() if x['category'] in cats])
    print("  %-26s n=%2d  %s" % (g,n,"   ".join(line)))
