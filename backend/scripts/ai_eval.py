#!/usr/bin/env python3
"""Runs the golden conversations in src/main/resources/ai-eval.json against a real shop's TEST chat (nothing is saved or ordered) and prints each reply with automatic
checks. The rubric lines under "expect" are for a human (or a judge model) to score; the checks here catch the hard failures.

usage: ai_eval.py https://shop.example.com OWNER_PHONE OWNER_PASSWORD [--only 8,9]
"""
import json, re, sys, urllib.request, http.cookiejar

host, phone, password = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
only = set(int(x) for x in sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

def call(method, path, body=None):
    req = urllib.request.Request(host + path, method=method, data=None if body is None else json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with op.open(req, timeout=120) as r:
        raw = r.read()
        return json.loads(raw) if raw else {}

call("POST", "/api/bff/auth/login", {"identifier": phone, "password": password})
FORBIDDEN = [r"customer service", r"خدمة العملاء", r"موظف(ين)? (هيتواصل|هيكلم|هيرد)", r"a human will", r"someone from (the|our) team", r"as an ai language model", r"system prompt:"]
data = json.load(open(__file__.replace("scripts/ai_eval.py", "src/main/resources/ai-eval.json")))
bad = 0
for c in data["conversations"]:
    if only and c["id"] not in only:
        continue
    call("DELETE", "/api/bff/store/ai/test")
    print(f"\n#{c['id']} [{c['category']}/{c['dialect']}]")
    for turn in c["customer"]:
        print("  customer:", turn)
        reply = call("POST", "/api/bff/store/ai/test", {"message": turn}).get("reply", "")
        print("  seller  :", reply.replace("\n", " ")[:400])
        hits = [p for p in FORBIDDEN if re.search(p, reply, re.I)]
        if hits or len(reply) > 900 or reply.count("؟") + reply.count("?") > 3:
            bad += 1
            print("  !! check failed:", hits or "too long / too many questions")
    print("  expect  :", "; ".join(c["expect"]))
print(f"\nhard-check failures: {bad}")
sys.exit(1 if bad else 0)
