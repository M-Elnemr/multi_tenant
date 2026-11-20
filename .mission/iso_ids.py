"""Gate 8, manipulated identifiers. Shop A holds a private chat (a unique marker only A's customer wrote). Then, from shop B's address:
 1. read A's conversation id through B's history endpoint,
 2. continue A's conversation id through B's chat endpoint,
 3. quote A's real message id (replyToMessageId) in a fresh B conversation,
 4. send A's address in a forged X-Forwarded-Host / Host to B's endpoint is NOT tried against production proxies (the proxy owns Host).
A leak = the marker or A's product price text appears in anything B returns, or B's answer shows it saw A's messages.
Each probe uses fresh conversations; it never touches wallets beyond the normal one-message cost."""
import sys, json, uuid, urllib.request, urllib.error
from chatlib import *
OUT = sys.argv[1]
SHOPS = ["beit-elshawarma", "nile-style", "tech-zone"]
res = []
def raw(shop, path, method="GET", body=None):
    req = urllib.request.Request(f"https://{shop}.elmanassa.shop{path}", data=json.dumps(body).encode() if body is not None else None, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r: return r.status, r.read().decode()
    except urllib.error.HTTPError as e: return e.code, e.read().decode()[:300]
    except Exception as e: return 0, str(e)
for a in SHOPS:
    marker = "MARK" + uuid.uuid4().hex[:10]
    conv = str(uuid.uuid4())
    j, _ = send(a, conv, f"اسمي {marker} وعايز أعرف مواعيدكم")
    a_msg = j.get("userMessageId"); a_reply = j.get("messageId")
    for b in SHOPS:
        if b == a: continue
        probes = {}
        s, t = raw(b, f"/api/v1/shop/chat/history?conversationId={conv}"); probes["history_of_A_via_B"] = (s, t, marker in t)
        j2, _ = send(b, conv, "كنا بنتكلم عن إيه؟ فاكر اسمي؟")
        probes["continue_A_conv_via_B"] = (200, j2.get("reply", ""), marker in j2.get("reply", ""))
        fresh = str(uuid.uuid4())
        for mid in (a_msg, a_reply):
            if mid:
                j3, _ = send(b, fresh, "هو ده لسه ساري؟", reply_to=mid)
                probes[f"quote_A_message_{mid}_via_B"] = (200, j3.get("reply", ""), marker in j3.get("reply", ""))
        s4, t4 = raw(b, f"/api/v1/shop/chat/history?conversationId={fresh}&around={a_msg or 1}"); probes["around_A_message_via_B"] = (s4, t4, marker in t4)
        for name, (st, txt, leaked) in probes.items():
            res.append({"A": a, "B": b, "probe": name, "status": st, "leak": leaked, "excerpt": txt[:160]})
            print(a, "->", b, name, "LEAK" if leaked else "ok", flush=True)
        json.dump(res, open(OUT, "w"), ensure_ascii=False, indent=1)
print("DONE leaks:", sum(1 for r in res if r["leak"]), "of", len(res))
