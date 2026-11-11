
import json, re, sys, time, urllib.request, urllib.error
SYSTEM = json.loads("\"You are the senior sales manager of an Egyptian online shop. You check ONE reply that your salesperson is about to send to a customer, and you fix it if needed.\\nYou get: the recent chat, the customer's newest message(s), the facts the tools returned for this reply (FACTS), how to address the customer, and the DRAFT.\\n\\nCheck the draft strictly against this list:\\n1. It answers EVERYTHING the customer asked in their newest message(s) (several lines may be several messages): nothing ignored, related questions answered together naturally, not a robotic question-by-question list. Two separate topics may be two messages separated by a line with only \\u2e3b (never more than two).\\n2. Every fact about a product (what it is, who it is for, material, quality, origin, size, color, stock, price) is in FACTS or was said in the chat. No invented qualities (\\u00ab\\u0623\\u0635\\u0644\\u064a \\u0661\\u0660\\u0660\\u066a\\u00bb, \\u00ab\\u0645\\u0633\\u062a\\u0648\\u0631\\u062f\\u00bb, \\u00ab\\u0642\\u0637\\u0646 \\u0645\\u0635\\u0631\\u064a\\u00bb...) unless FACTS says so; general care advice is fine when worded as general (\\u00ab\\u0639\\u0627\\u062f\\u0629\\u064b\\u2026\\u00bb).\\n2b. What the shop has: never \\\"we have\\\" a kind of item, color, size or offer that FACTS/SHOP DATA do not show (for example jackets or shoes when none are listed), and never \\\"we don't have\\\" a color or size that allOptions lists. Pushing unrelated products (pillows or knives to someone asking for shoes) is wrong: say kindly we do not have it and offer the closest related kind. Never tell the customer that pictures or links \\\"are being sent\\\".\\n            3. The products fit WHO it is for: never a kids' product (for boys, girls, babies) for an adult buying for themself, never women's products for a man buying for himself (unless a gift). If the draft recommends while it is unclear who it is for and the shop has the item for several groups, it should ask one short question instead (\\u00ab\\u0644\\u064a\\u0643 \\u0648\\u0644\\u0627 \\u0644\\u062d\\u062f \\u062a\\u0627\\u0646\\u064a\\u061f\\u00bb).\\n4. Respect and gender: the right masculine/feminine forms for this customer; \\u00ab\\u0623\\u0633\\u062a\\u0627\\u0630/\\u0623\\u0633\\u062a\\u0627\\u0630\\u0629 + name\\u00bb only as ADDRESSING says (never the wrong gender, never a guess); otherwise \\u00ab\\u062d\\u0636\\u0631\\u062a\\u0643\\u00bb/\\u00ab\\u064a\\u0627 \\u0641\\u0646\\u062f\\u0645\\u00bb. Never the bare name (\\u00ab\\u064a\\u0627 \\u0645\\u064a\\u062f\\u0648\\u00bb), never casual words (\\u00ab\\u064a\\u0627 \\u0628\\u0627\\u0634\\u0627\\u00bb\\u060c \\u00ab\\u064a\\u0627 \\u0631\\u0627\\u062c\\u0644\\u00bb\\u060c \\u00ab\\u064a\\u0627 \\u0639\\u0645\\u00bb\\u060c \\u00ab\\u064a\\u0627 \\u0645\\u0639\\u0644\\u0645\\u00bb), never laughing at the customer (\\u00ab\\u0647\\u0647\\u0647\\u0647\\u00bb).\\n4b. Numbers and counts are right: never a total, sum or \\\"free delivery\\\" claim the salesperson worked out itself (only a total from a tool or summary in FACTS/chat may be repeated), and \\\"these three\\\" only when there are three.\\n1b. The answer comes first: a price, availability or yes/no question is answered directly at the start (not replaced by a question about size or color); a question about the product (stainless? can it be sharpened? does it shrink?) gets a direct honest answer, even if only \\\"the description does not say; usually\\u2026\\\".\\n1c. Honest about itself: if asked, it says it is the shop's AI assistant; it never claims to be a person or \\\"customer service\\\"; it speaks about itself in the masculine consistently. No invented shop process or policy (returns, exchanges, warranty) that SHOP DATA does not state.\\n            5. Words: natural, correct Egyptian Arabic (or the customer's language and dialect), simple everyday words, no odd, poetic, literal-translation, rare or invented words (like \\u00ab\\u0623\\u064a\\u0642\\u0646\\u0651\\u0643\\u00bb, \\u00ab\\u0623\\u0628\\u0648 \\u063a\\u0645\\u0651\\u0627\\u062a\\u00bb, \\u00ab\\u062a\\u062d\\u0633\\u0628\\u0643\\u00bb), no typos, no broken grammar, nothing that could be understood wrongly. Replace any word you would not hear from a normal Cairo shop assistant.\\n5b. Spelling: read every word letter by letter; fix doubled or missing letters (\\u00ab\\u0628\\u064a\\u064a\\u0643\\u00bb\\u2192\\u00ab\\u0628\\u064a\\u0643\\u00bb, \\u00ab\\u0644\\u0645\\u064a\\u0631\\u0627\\u062a\\u0643\\u00bb\\u2192\\u00ab\\u0644\\u0645\\u0631\\u0627\\u062a\\u0643\\u00bb, \\u00ab\\u0627\\u0644\\u0642\\u0645\\u0635\\u0635\\u00bb\\u2192\\u00ab\\u0627\\u0644\\u0642\\u0645\\u0635\\u0627\\u0646\\u00bb), Latin letters mixed into an Arabic word, and a phone number written back (replace it with \\u00ab\\u0627\\u0644\\u0631\\u0642\\u0645 \\u062f\\u0647\\u00bb). The salesperson speaks about itself in the masculine (\\u00ab\\u0645\\u062a\\u0627\\u0628\\u0639\\u00bb not \\u00ab\\u0645\\u062a\\u0627\\u0628\\u0639\\u0629\\u00bb).\\n5c. Counts against SHOP DATA: \\u00ab\\u0639\\u0646\\u062f\\u0646\\u0627 \\u0623\\u0643\\u062a\\u0631 \\u0645\\u0646 \\u0646\\u0648\\u0639\\u00bb only if the catalog shows more than one product of that kind.\\n            6. No invented urgency, deadline, guarantee, review, promise of a call, and no discount tied to ordering now. A discount, if any, must be one the customer asked for or a kind gift, never pressure.\\n7. Friendly and calm even if the customer is rude; never argues or lectures. Short acknowledgements like \\u00ab\\u062d\\u0642\\u0643 \\u0639\\u0644\\u064a\\u0627 \\u064a\\u0627 \\u0641\\u0646\\u062f\\u0645\\u00bb or \\u00ab\\u0645\\u0639\\u0627\\u0643 \\u062d\\u0642\\u00bb are good, keep them.\\n8. Short (1-3 sentences per message), sounds like a real person, ends with exactly one step toward the sale, does not repeat a sentence the salesperson already sent in the chat.\\n\\nAnswer with JSON only, no markdown:\\n{\\\"ok\\\": true}  when the draft passes every point, or\\n{\\\"ok\\\": false, \\\"problems\\\": [\\\"short reason\\\", ...], \\\"reply\\\": \\\"the corrected reply\\\"}\\nThe corrected reply keeps everything good in the draft (the products, prices and next step), fixes only what is wrong, adds NO price, amount, product, discount or fact that is not in the draft or FACTS, and is written in the customer's language and dialect, ready to send as it is.\"")
BASE = "https://api-cdn.thehive.ai/api/v3"
def key():
    for line in open("/opt/multitenant/.env"):
        m = re.match(r"\s*GLM_API_KEY\s*=\s*(.*)\s*$", line)
        if m: return m.group(1).strip().strip('"').strip("'")
KEY = key()
EFFORT="low"
def call(user):
    body = {"model": "deepseek-ai/deepseek-v4.1-flash-1017", "reasoning_effort": EFFORT, "max_tokens": 1000, "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}]}
    req = urllib.request.Request(BASE + "/chat/completions", data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            d = json.loads(r.read()); return 200, d["choices"][0]["message"].get("content") or "", int((time.time() - t0) * 1000), d.get("usage", {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:200], int((time.time() - t0) * 1000), {}
    except Exception as e:
        return 0, type(e).__name__, int((time.time() - t0) * 1000), {}
AR = str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789")
def nums(t):
    t = t.translate(AR).replace("٬", ",").replace("٫", ".")
    out = set()
    for m in re.finditer(r"\d[\d,]*(?:\.\d+)?", t):
        n = m.group().replace(",", ""); n = re.sub(r"\.0+$", "", n)
        if len(n.replace(".", "")) >= 2 and float(n) >= 10: out.add(n)
    return out
def parse(text):
    raw = re.sub(r"^```(?:json)?|```$", "", text.strip()).strip()
    a, b = raw.find("{"), raw.rfind("}")
    if a < 0 or b <= a: return None
    try: return json.loads(raw[a:b+1])
    except Exception: return None
FACTS = ('- search_products: {"products":[{"name":"قميص قطن","slug":"cotton-shirt","price":150,"currency":"EGP","for":"MEN","variants":[{"options":{"اللون":"أبيض","المقاس":"M"},"stock":5},{"options":{"اللون":"أبيض","المقاس":"L"},"stock":3},{"options":{"اللون":"أسود","المقاس":"M"},"stock":0}]}]}\n')
SHOP = "SHOP FACTS: currency EGP. Payment: cash on delivery. Delivery 50 EGP, free above 1000.\nSHOP CATALOG LIST: قميص قطن (150 EGP, for men, colors: أبيض, أسود; sizes: M, L)\n"
def request(chat, newest, draft, facts=FACTS):
    return ("RECENT CHAT:\n" + chat + "\nCUSTOMER'S NEWEST MESSAGE(S):\n" + newest + "\nFACTS (tool results for this reply):\n" + facts + "\nSHOP DATA (also facts):\n" + SHOP + "\nADDRESSING: not known, use «حضرتك»\nDRAFT:\n" + draft)

CASES = [
 ("valid price + variants", "بكام القميص القطن؟", "القميص القطن بـ 150 جنيه، متاح أبيض مقاس M وL. تحب أحجزهولك؟", True),
 ("valid, quantity of two", "عايز اتنين قميص قطن أبيض مقاس M", "تمام يا فندم، اتنين قميص قطن أبيض مقاس M متاحين، سعر القطعة 150 جنيه. ابعتلي الاسم والعنوان؟", True),
 ("valid, plain dialect", "القميص ده بيتغسل ازاي؟", "عادةً القطن بيتغسل على بارد يا فندم، والبيانات على التيكت بتوضح أكتر. تحب أحجزلك واحد بـ 150 جنيه؟", True),
 ("invented quality claim", "بكام القميص القطن؟", "القميص قطن مصري ١٠٠٪ أصلي بـ 150 جنيه، تحب أحجزهولك؟", False),
 ("casual address", "بكام القميص القطن؟", "القميص بـ 150 جنيه يا باشا، تحب أحجزهولك؟", False),
 ("salesperson's own total + free delivery", "عايز اتنين قميص قطن", "اتنين قميص بـ 300 جنيه والتوصيل مجاني، تحب أحجزهملك؟", False),
 ("sold-out colour+size offered", "عايز قميص اسود مقاس M", "متاح قميص أسود مقاس M بـ 150 جنيه، تحب أحجزهولك؟", False),
 ("invented urgency", "بكام القميص القطن؟", "القميص بـ 150 جنيه والعرض هينتهي النهاردة، الحق اطلبه دلوقتي!", False),
 ("ignores the second question", "بكام القميص القطن؟ وبتوصلوا مدينة نصر امتى؟", "القميص القطن بـ 150 جنيه يا فندم، تحب أحجزهولك؟", False),
 ("pushes a kids product to an adult", "عايز قميص ليا", "عندنا قميص ولادي بيج بـ 150 جنيه، تحب أحجزهولك؟", False),
]
import sys
RUNS = 2
res = {}
for eff in ("none", "low"):
    EFFORT = eff
    tot_ms = []; tot_out = []; agree = 0; n = 0; rows = []
    for name, newest, draft, should_pass in CASES:
        for r in range(RUNS):
            asked = request("Customer: السلام عليكم\nSalesperson: وعليكم السلام، أقدر أساعدك إزاي؟\n", newest, draft)
            s, text, ms, u = call(asked)
            v = parse(text) if s == 200 else None
            if v is None: rows.append((name, r, "UNUSABLE", ms)); continue
            ok = bool(v.get("ok")); tot_ms.append(ms); tot_out.append((u.get("completion_tokens") or 0)); n += 1
            correct = (ok == should_pass); agree += correct
            rows.append((name, r, "ok" if ok else "flag", ms, correct, (u.get("completion_tokens") or 0), (u.get("completion_tokens_details") or {}).get("reasoning_tokens")))
            time.sleep(0.3)
    res[eff] = (tot_ms, tot_out, agree, n, rows)
for eff in ("none", "low"):
    ms, out, agree, n, rows = res[eff]
    if not ms: print(eff, "no usable runs"); continue
    ms2 = sorted(ms)
    print(f"\n== DeepSeek review, reasoning {eff.upper()}: usable {n}/{len(CASES)*RUNS}; verdict as expected {agree}/{n}; latency median {statistics.median(ms)/1000:.1f}s p90 {ms2[int(0.9*(len(ms2)-1)+0.5)]/1000:.1f}s; avg output tokens {sum(out)/len(out):.0f}") if (statistics:=__import__("statistics")) else None
    for name in [c[0] for c in CASES]:
        mine = [r for r in rows if r[0] == name]
        print(f"   {name:42} " + "  ".join(f"{r[2]}({r[3]/1000:.1f}s{'' if len(r)<5 else (',' + str(r[5]) + 'tok')})" for r in mine))
print("\ndone")
