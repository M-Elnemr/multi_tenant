import json, collections, math
from analyze import *

def row(m, mode, store=None):
    s = summarize(m, mode, store); return s

print("=== QUALITY (judge pass rate, over all conversations incl. code-answered) and successful conversations ===")
print("%-9s %-5s %5s | %-22s | %-22s | %-18s | %-16s" % ("model","mode","convs","judge checks passed","model-answered only","fully successful","orders all correct"))
for m in MODELS:
    for mode in MODES:
        s=row(m,mode)
        print("%-9s %-5s %5d | %4d/%4d = %5.1f%%    | %5.1f%% (%3d convs)    | %3d/%3d = %5.1f%% | %d/%d" % (m,mode,s['conversations'],s['judge_checks_passed'],s['judge_checks_total'],s['judge_pct'],s['judge_pct_model_only'],s['model_conversations'],s['successful_convs'],s['conversations'],pct(s['successful_convs'],s['conversations']),s['orders_all_correct'],s['order_scenarios']))
print("\n=== QUALITY BY STORE (judge %) ===")
for st in STORES:
    print(st)
    for m in MODELS:
        print("   %-9s full %5.1f%%  lean %5.1f%%   (successful convs full %d/%d lean %d/%d)" % (m, row(m,'full',st)['judge_pct'], row(m,'lean',st)['judge_pct'], row(m,'full',st)['successful_convs'], row(m,'full',st)['conversations'], row(m,'lean',st)['successful_convs'], row(m,'lean',st)['conversations']))
print("\n=== ORDER CORRECTNESS (code-checked, 6 scenarios per model/mode: 2 per store) ===")
for m in MODELS:
    for mode in MODES:
        s=row(m,mode)
        print("%-9s %-5s all correct %d/%d ; check passes %s" % (m,mode,s['orders_all_correct'],s['order_scenarios'],s['order_check_passes']))
print("\n=== TOOLS ===")
for m in MODELS:
    for mode in MODES:
        s=row(m,mode)
        print("%-9s %-5s tool calls %4d  errors %3d (%.1f%%)  by tool %s" % (m,mode,s['tool_calls'],s['tool_errors'],pct(s['tool_errors'],s['tool_calls']),{k:v for k,v in s['tools'].items() if v[1]}))
        for msg,n in s['tool_error_messages'][:4]: print("        %2dx %s" % (n,msg))
print("\n=== FAILURES / FALLBACKS / SECOND OPINIONS / CODE-ANSWERED ===")
print("%-9s %-5s %6s %6s %6s | %5s %5s | %5s %5s %7s" % ("model","mode","msgs","code","model","failed","fallbk","2ndOp","adopt","modelFailEvents"))
for m in MODELS:
    for mode in MODES:
        s=row(m,mode)
        print("%-9s %-5s %6d %6d %6d | %5d %5d | %5d %5d %7d  so_by=%s" % (m,mode,s['customer_messages'],s['code_answered'],s['model_replies'],s['model_failed_replies'],s['fallbacks'],s['second_opinions'],s['second_opinions_adopted'],s['model_failure_events'],{k:v['n'] for k,v in s['so_by_model'].items()}))
print("\n=== LATENCY (seconds per customer message) ===")
print("%-9s %-5s | all msgs: avg  p95 | model-answered: avg  median  p95" % ("model","mode"))
for m in MODELS:
    for mode in MODES:
        s=row(m,mode)
        print("%-9s %-5s |          %5.1f %5.1f |                 %5.1f %6.1f %5.1f" % (m,mode,s['lat_all_avg_s'],s['lat_all_p95_s'],s['lat_model_avg_s'],s['lat_model_median_s'],s['lat_model_p95_s']))
print("\n=== TOKENS (answering model's own calls; second opinions apart) ===")
print("%-9s %-5s %7s %9s %9s %9s %6s %9s %8s %8s" % ("model","mode","modelRp","inputTok","outputTok","cachedTok","calls","calls/rep","in/rep","out/rep"))
for m in MODELS:
    for mode in MODES:
        s=row(m,mode)
        print("%-9s %-5s %7d %9d %9d %9d %6d %9.2f %8.0f %8.0f   in/call %.0f  cache-hit %.0f%%" % (m,mode,s['model_replies'],s['input_tokens'],s['output_tokens'],s['cached_tokens'],s['model_calls'],s['calls_per_model_reply'],s['in_per_model_reply'],s['out_per_model_reply'],s['in_per_call'],pct(s['cached_tokens'],s['input_tokens'])))
print("\n=== COST (USD; provider list prices) ===")
print("%-9s %-5s %10s %10s %10s %12s %14s %14s" % ("model","mode","own","2nd-opin","TOTAL","per conv","per success","per 1000 replies"))
for m in MODELS:
    for mode in MODES:
        s=row(m,mode)
        print("%-9s %-5s %10.4f %10.4f %10.4f %12.5f %14.5f %14.3f" % (m,mode,s['cost_own'],s['cost_second_opinion'],s['cost_total'],s['cost_per_conv'],s['cost_per_success'],s['cost_per_1000_model_replies']))
