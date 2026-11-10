import json, math, collections, sys
import analyze as A
from analyze import R, MODELS, MODES, STORES, PRICE, p95, pct, cost, convs, code_only
O = json.load(open('report3.json'))   # the six order scenarios again, with the corrected contact check

def order_runs(model, mode):
    return [x for c in O['cells'] if c['model']==model and c['mode']==mode for x in c['results'] if 'orderCheck' in x]
def order_runs_main(model, mode):
    return [x for x in convs(model, mode) if 'orderCheck' in x]

def nonorder(model, mode): return [x for x in convs(model, mode) if x['category'] != 'order_flow']
def ok_noorder(x):
    return x.get('of',0)>0 and x['passed']==x['of'] and not any(t.get('errorReply') or t.get('modelFailures') for t in x['turns'])
def ok_order(x):
    return bool(x['orderCheck'].get('allCorrect')) and not any(t.get('errorReply') or t.get('modelFailures') for t in x['turns'])

out = {}
for m in MODELS:
    for mode in MODES:
        s = A.summarize(m, mode)              # all 80 conversations: tokens, cost, latency, tools, failures (real usage)
        no = nonorder(m, mode)
        ord_b = order_runs(m, mode); ord_a = order_runs_main(m, mode)
        d = collections.OrderedDict()
        d['judge_checks'] = (sum(x['passed'] for x in no), sum(x['of'] for x in no))
        d['judge_pct'] = pct(*d['judge_checks'])
        mo = [x for x in no if not code_only(x)]
        d['judge_pct_model_only'] = pct(sum(x['passed'] for x in mo), sum(x['of'] for x in mo)); d['model_only_n'] = len(mo)
        d['ok_noorder'] = (sum(1 for x in no if ok_noorder(x)), len(no))
        d['orders_b_ok'] = (sum(1 for x in ord_b if ok_order(x)), len(ord_b))
        d['orders_a_ok'] = (sum(1 for x in ord_a if x['orderCheck'].get('allCorrect')), len(ord_a))
        ck = collections.Counter()
        for x in ord_b:
            for k, v in x['orderCheck'].items():
                if k not in ('allCorrect', 'pendingAtSummary') and v: ck[k] += 1
        d['order_checks_b'] = dict(ck)
        succ = d['ok_noorder'][0] + d['orders_b_ok'][0]
        d['successes'] = succ; d['of'] = len(no) + len(ord_b)
        d['cost_total'] = s['cost_total']; d['cost_own'] = s['cost_own']; d['cost_so'] = s['cost_second_opinion']
        d['cost_per_success'] = s['cost_total'] / succ if succ else float('nan')
        d['cost_per_conv'] = s['cost_total'] / s['conversations']
        d['s'] = s
        out[(m, mode)] = d

def f(x, n=1): return ("%." + str(n) + "f") % x
print("MAIN TABLE (80 conversations per model/mode: 74 judged + 6 order scenarios)")
print("%-9s %-5s | judge%% (74)  model-only%% | fully-ok convs (74) | orders OK (rerun) | orders OK (run A) | calls/rep tokIn/rep tokOut/rep | lat avg/med/p95 (model replies) | tool err | $ total  $/conv  $/success" % ("model","mode"))
for m in MODELS:
    for mode in MODES:
        d = out[(m, mode)]; s = d['s']
        print("%-9s %-5s | %5.1f%%   %5.1f%% | %2d/%2d = %4.1f%% | %d/%d | %d/%d | %.2f %7.0f %5.0f | %4.1f %4.1f %4.1f | %2d/%3d | %.4f %.5f %.5f" % (
            m, mode, d['judge_pct'], d['judge_pct_model_only'], d['ok_noorder'][0], d['ok_noorder'][1], pct(*d['ok_noorder']), d['orders_b_ok'][0], d['orders_b_ok'][1], d['orders_a_ok'][0], d['orders_a_ok'][1],
            s['calls_per_model_reply'], s['in_per_model_reply'], s['out_per_model_reply'], s['lat_model_avg_s'], s['lat_model_median_s'], s['lat_model_p95_s'], s['tool_errors'], s['tool_calls'], d['cost_total'], d['cost_per_conv'], d['cost_per_success']))
print("\nORDER CHECKS (rerun, corrected contact check), passes out of 6 per cell:")
for m in MODELS:
    for mode in MODES:
        print("  %-9s %-5s all-ok %s  %s" % (m, mode, out[(m, mode)]['orders_b_ok'], out[(m, mode)]['order_checks_b']))
print("\nORDER TOOL FAILURES (all tool calls in the order scenarios, rerun):")
for m in MODELS:
    for mode in MODES:
        tc = [t for x in order_runs(m, mode) for t in x.get('toolCalls', [])]
        er = collections.Counter(t['tool'] + ': ' + t.get('error', '')[:50] for t in tc if not t['ok'])
        print("  %-9s %-5s calls %2d errors %2d  %s" % (m, mode, len(tc), sum(er.values()), dict(er)))
json.dump({f"{m}|{mode}": {k: v for k, v in d.items() if k != 's'} for (m, mode), d in out.items()}, open('final_summary.json', 'w'), default=str, indent=1)
