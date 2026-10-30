#!/usr/bin/env python3
"""An exam for the shop assistant that works on ANY shop: it reads the shop's own catalog from the public API, writes questions whose right answer
is known from that data (the price of each option, a sold-out combination, an option the product does not have, more pieces than the stock, the
colours of a product, a full order up to the summary) and grades every reply against the data. Orders are never confirmed.

usage: ai_exam.py SHOP_SLUG MODEL_WORD OUT_PREFIX [--n 60] [--seed 1] [--gap 4.2] [--only kind,kind]
  writes OUT_PREFIX.txt (transcripts in the corpus format of FactCheckTest) and OUT_PREFIX.json (every case with its verdict),
  and prints the score per kind. MODEL_WORD is the chat word that switches the model (glm, alibaba).
"""
import json, sys, uuid, urllib.request, urllib.parse, concurrent.futures, time, random, re, threading

SLUG, MODEL, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
arg = lambda k, d: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
N, SEED, GAP = int(arg("--n", "60")), int(arg("--seed", "1")), float(arg("--gap", "4.2"))
ONLY = set(arg("--only", "").split(",")) - {""}
BASE = f"https://{SLUG}.elmanassa.shop/api/v1/shop"
rnd = random.Random(SEED)

REGRADE = arg("--regrade", None)   # re-grade a saved OUT_PREFIX.json with this grader, without playing anything

def get(path):
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(BASE + path, headers={"User-Agent": "ai-exam"}), timeout=30) as r:
                return json.load(r)
        except Exception:
            time.sleep(3 + i * 5)
    raise SystemExit("failed " + path)

# ---- the shop's data ---------------------------------------------------------------------------------------------------
items, page = ([], 1) if not REGRADE else ([], 0)
while not REGRADE:
    d = get(f"/products?page={page}&pageSize=100")
    items += d["data"]
    if len(d["data"]) < 100: break
    page += 1
products = []
for it in items:
    p = get("/products/" + urllib.parse.quote(it["slug"]))
    time.sleep(0.1)
    vs = []
    for v in p["variants"]:
        opts = {}
        for kv in (v.get("comboKey") or "").split("|"):
            if "=" in kv: k, val = kv.split("=", 1); opts[k] = val
        vs.append({"options": opts, "price": v["priceMinor"] / 100, "before": (v.get("compareAtPriceMinor") or 0) / 100, "available": v.get("available") or 0})
    products.append({"name": p["name"], "slug": p["slug"], "variants": vs})
prof = get("/profile") if not REGRADE else {"shippingZones": []}
served = sorted({c for z in prof["shippingZones"] if z.get("isActive") for c in z["governorateCodes"]})
MIN_ORDER = ((prof.get("profile") or {}).get("minOrderMinor") or 0) / 100
print(SLUG, len(products), "products", file=sys.stderr)

# ---- text helpers ------------------------------------------------------------------------------------------------------
AR = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")
def light(s):
    s = s.translate(AR)
    for a, b in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ة", "ه"), ("ى", "ي"), ("ـ", "")): s = s.replace(a, b)
    return re.sub("[ً-ٰٟ]", "", s).lower()
def numbers(s):
    t = re.sub(r"(?<=\d)[,٬](?=\d{3}\b)", "", s.translate(AR))
    return [float(x) for x in re.findall(r"(?<![\d.])\d+(?:\.\d{1,2})?(?![\d])", t)]
NEG = re.compile(r"(?<!\w)(?:و|ف)?مش(?!\w)|ما\s+بي?جيش|مبيجيش|مابيجيش|مجيهش|مجيش|ميجيش|معديش|مفيش|مافيش|معندناش|ماعندناش|معنديش|خلص|خلصان|خلصت|نفذ|نفذت|نفد|نفاذ|نافذ|ناضب|نفاص|متوفرش|موجودش|متاحش|غير\s+(?:متاح|متوفر|موجود)|للاسف|ملهوش|مفيهوش|مفيهاش|مبقاش|(?<!\w)لا(?!\w)|(?<!\w)لأ(?!\w)|بس\s+(?:بلون|باللون|بالوان|في)|"
                 r"not\s+available|unavailable|out\s+of\s+stock|sold\s+out|don'?t\s+have|no\s+longer|isn'?t|aren'?t")
POS = re.compile(r"متاح|متوفر|موجود|available|in\s+stock|عندنا")
def sentences(s): return [x for x in re.split(r"[\n.!؟?]+", s) if x.strip()]
def has_values(sentence, values): return all(light(v).split()[0] in light(sentence) for v in values)

def num_txt(x): return str(int(x)) if x == int(x) else str(x)
def ask_values(p, v):
    opts = v["options"]
    parts = []
    for k, val in opts.items():
        parts.append(("مقاس " + val) if light(k) in ("المقاس", "مقاس") and rnd.random() < .5 else val)
    return " ".join(parts)

# ---- the cases ---------------------------------------------------------------------------------------------------------
cases = []
def add(kind, msgs, **expect): cases.append({"kind": kind, "msgs": msgs, **expect})

priced = [p for p in products if len({v["price"] for v in p["variants"]}) > 1]
optioned = [p for p in products if any(v["options"] for v in p["variants"])]
with_soldout = [p for p in optioned if any(v["available"] <= 0 for v in p["variants"]) and any(v["available"] > 0 for v in p["variants"])]
single = [p for p in products if len({v["price"] for v in p["variants"]}) == 1]

for p in rnd.sample(priced, min(len(priced), N // 5)):
    v = rnd.choice([x for x in p["variants"] if x["available"] > 0] or p["variants"])   # the price of an option that is there (a sold-out one is the soldout cases' business)
    q = rnd.choice(["{n} {o} بكام؟", "سعر {n} {o} كام؟", "{n} {o} بكام لو سمحت", "عايز اعرف سعر {n} {o}"]).format(n=p["name"], o=ask_values(p, v))
    add("price_option", [q], product=p["name"], price=v["price"], others=sorted({x["price"] for x in p["variants"] if x["price"] != v["price"]}))
for p in rnd.sample(single, min(len(single), N // 10)):
    q = rnd.choice(["{n} بكام؟", "سعر {n} كام؟"]).format(n=p["name"])
    add("price_single", [q], product=p["name"], price=p["variants"][0]["price"])
for p in rnd.sample(with_soldout, min(len(with_soldout), N // 5)):
    v = rnd.choice([x for x in p["variants"] if x["available"] <= 0])
    q = rnd.choice(["{n} {o} موجود؟", "عايز {n} {o}", "عندكم {n} {o}؟", "{n} {o} متاح؟"]).format(n=p["name"], o=ask_values(p, v))
    add("soldout_combo", [q], product=p["name"], values=list(v["options"].values()))
for p in rnd.sample(optioned, min(len(optioned), N // 6)):
    ins = [x for x in p["variants"] if x["available"] > 0 and x["options"]]
    if not ins: continue
    v = rnd.choice(ins)
    q = rnd.choice(["{n} {o} متاح؟", "عندكم {n} {o}؟", "{n} {o} موجود ولا خلص؟"]).format(n=p["name"], o=ask_values(p, v))
    add("instock_combo", [q], product=p["name"], values=list(v["options"].values()), available=v["available"])
# an option value another product has but this one does not (a size, a colour, a capacity…)
all_values = {}
for p in optioned:
    for v in p["variants"]:
        for k, val in v["options"].items(): all_values.setdefault(light(k), set()).add(val)
for p in rnd.sample(optioned, min(len(optioned), N // 6)):
    own = {}
    for v in p["variants"]:
        for k, val in v["options"].items(): own.setdefault(light(k), set()).add(val)
    k = rnd.choice(list(own))
    unit = re.compile(r"\d")
    own_has_numbers = any(unit.search(light(v)) for v in own[k])
    # a fair question: a value of the same kind (a size for sizes, a number only where its own values are numbers), never «فرد» (how many it feeds)
    foreign = sorted(v for v in all_values.get(k, set()) - own[k] if light(v) not in ("فرد", "فردي", "عائلي") and bool(unit.search(light(v))) == own_has_numbers)
    if not foreign: continue
    val = rnd.choice(foreign)
    q = rnd.choice(["{n} {v} عندكم؟", "عايز {n} {v}", "{n} بييجي {v}؟"]).format(n=p["name"], v=val)
    add("invented_value", [q], product=p["name"], values=[val], own=sorted(own[k]))
for p in rnd.sample(optioned, min(len(optioned), N // 8)):
    ins = [x for x in p["variants"] if x["available"] > 0 and x["options"]]
    if not ins: continue
    v = rnd.choice(ins)
    want = v["available"] + rnd.choice([2, 5, 10])
    add("qty_over_stock", [f"عايز {want} قطعة من {p['name']} {ask_values(p, v)}"], product=p["name"], values=list(v["options"].values()), available=v["available"], want=want)
COLOR_KEYS = {"اللون", "لون", "color", "colour"}
for p in rnd.sample(optioned, min(len(optioned), N // 8)):
    cols = {v["options"][k] for v in p["variants"] for k in v["options"] if light(k) in COLOR_KEYS}
    if len(cols) < 2: continue
    instock = sorted({v["options"][k] for v in p["variants"] if v["available"] > 0 for k in v["options"] if light(k) in COLOR_KEYS})
    add("colours", [rnd.choice(["{n} متاح بألوان ايه؟", "ايه ألوان {n}؟"]).format(n=p["name"])], product=p["name"], colours=instock, all_colours=sorted(cols))
GOV = {"CAI": "القاهرة مدينة نصر شارع مكرم عبيد عمارة ٣ شقة ٧", "GIZ": "الجيزة الدقي شارع التحرير عمارة ٥ شقة ٢", "ALX": "اسكندرية سموحة شارع فوزي معاذ عمارة ٩ شقة ٤"}
gov = next((g for g in ("CAI", "GIZ", "ALX") if g in served), None)
if gov:
    pool = [p for p in products if any(v["available"] > 1 for v in p["variants"])]
    for p in rnd.sample(pool, min(len(pool), N // 8)):
        v = rnd.choice([x for x in p["variants"] if x["available"] > 1])
        o = ask_values(p, v)
        add("order", [f"عايز {p['name']} {o}".strip(), f"انا كريم، {GOV[gov]}، ٠١٠٢٣٤٥٦٧٨٩، كاش"], product=p["name"], price=v["price"], values=list(v["options"].values()), min_order=MIN_ORDER)
cases = [c for c in cases if not ONLY or c["kind"] in ONLY]
rnd.shuffle(cases)

# ---- playing them ------------------------------------------------------------------------------------------------------
HOST = f"https://{SLUG}.elmanassa.shop/api/v1/shop/chat"
_lock, _last = threading.Lock(), [0.0]
def send(conv, msg):
    with _lock:
        wait = _last[0] + GAP - time.time()
        if wait > 0: time.sleep(wait)
        _last[0] = time.time()
    req = urllib.request.Request(HOST, data=json.dumps({"conversationId": conv, "message": msg}).encode(), method="POST", headers={"Content-Type": "application/json"})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.loads(r.read()).get("reply", ""), round(time.time() - t, 1)
    except Exception as e:
        return f"[ERROR {e}]", round(time.time() - t, 1)

def play(c):
    conv = str(uuid.uuid4())
    send(conv, MODEL)
    time.sleep(7)
    c["replies"] = []
    for m in c["msgs"]:
        r, sec = send(conv, m)
        c["replies"].append(r); c.setdefault("secs", []).append(sec)
    return c

# ---- grading -----------------------------------------------------------------------------------------------------------
def grade(c):
    r = c["replies"][-1]
    if r.startswith("[ERROR"): return "error: " + r
    k, nums, lr = c["kind"], numbers(r), light(r)
    if k in ("price_option", "price_single"):
        if c["price"] not in nums: return f"price {num_txt(c['price'])} missing"
        return None
    if k == "soldout_combo":
        if not NEG.search(lr): return "sold-out combination not said to be unavailable"
        alt = re.compile(r"تاني|تانيه|غير|بدل|بديل|المتاح|متاح\s+في|متوفر\s+في|يرجع|نبلغك|ابلغك|لكن|بس\s+عندنا|other|instead")
        said_no = False
        for s in sentences(r):
            ls = light(s)
            if has_values(s, c["values"]) and NEG.search(ls): said_no = True
            elif has_values(s, c["values"]) and POS.search(ls) and not NEG.search(ls) and not alt.search(ls) and not said_no: return "sold-out combination offered: " + s.strip()[:80]
        return None
    if k == "instock_combo":
        for s in sentences(r):
            ls = light(s)
            # the clause that holds the values denies them («… وردي، … (الشريحة مش داخل معاها)» denies the SIM, not the colour)
            clauses = [x for x in re.split(r"[،,(]", s) if x.strip()]
            holder = [x for x in clauses if has_values(x, c["values"])] or [s]   # values spread over clauses: the whole sentence
            if has_values(s, c["values"]) and not POS.search(ls) and any(NEG.search(light(x)) for x in holder):
                return "in-stock combination denied: " + s.strip()[:80]
        if not POS.search(lr) and not any(w in lr for w in ("ايوه", "اه ", "تمام", "yes")): return "availability not confirmed"
        return None
    if k == "invented_value":
        v = light(c["values"][0])
        if re.search(r"(?:من\s+غير|بدون|ماعدا|عدا|بدل)\s+(?:\S+\s+){0,2}" + re.escape(v), lr): return None   # «S لحد XL، من غير XXL»
        own = c.get("own")
        if v in lr and not NEG.search(lr): return f"value {c['values'][0]} the product does not have was agreed to"
        if own is None: return None   # an old run without the product's own values: not agreed is enough
        # strict: a no to it, or the real values of that option named (all of them, or a range «من 26 لحد 32»)
        if v in lr and NEG.search(lr): return None
        if all(light(o).split()[0] in lr for o in own): return None
        if re.search(r"من\s+\S+\s+(?:لحد|لغايه|الي|ل|-)\s*\S+", lr) and NEG.search(lr): return None
        return f"value {c['values'][0]} not answered: neither a no nor its real options ({'، '.join(own)})"
    if k == "qty_over_stock":
        if c["available"] >= 100: return None   # a made-to-order kitchen: the stock number is not a limit a customer meets
        WORDS = {1: r"قطعه\s+واحده|واحده\s+بس|حته\s+واحده", 2: r"قطعتين|اتنين|حتتين", 3: r"تلات|تلاته|ثلاث", 4: r"اربع|اربعه", 5: r"خمس|خمسه"}
        if c["available"] in WORDS and re.search(r"(?<!\w)(?:" + WORDS[c["available"]] + r")(?!\w)", lr): nums = nums + [c["available"]]   # «قطعتين بس»
        if c["available"] not in nums and not NEG.search(lr): return f"only {c['available']} in stock, reply does not say so"
        if c["want"] in nums and not NEG.search(lr) and c["available"] not in nums: return "promised more than the stock"
        return None
    if k == "colours":
        missing = [x for x in c["colours"] if light(x)[:3] not in lr]
        return ("in-stock colours missing: " + "، ".join(missing)) if missing else None
    if k == "order":
        told_min = re.search(r"اقل\s+(?:طلب|قيمه|مبلغ)|الحد\s+الادني|minimum\s+order|minimum\s+is", lr)
        if c.get("min_order") and c["price"] < c["min_order"]:   # under the shop's minimum: no summary can be made, the customer must hear the minimum
            return None if told_min and num_txt(c["min_order"]) in [num_txt(x) for x in nums] else f"below the minimum order {num_txt(c['min_order'])}: not told"
        if told_min and not c.get("min_order"): return None   # an old run without the minimum: asking to add is right
        if re.search(r"[×x]\s*([2-9]|\d{2,})(?!\d)", r.translate(AR)): return "summary with a quantity the customer never asked for"
        if "ملخص" not in r and "Total" not in r and "الاجمالي" not in lr:
            # one question back for something really missing is fine only when the product has an option the customer did not say
            return "no order summary after full details"
        if c["price"] not in nums: return f"summary without the price {num_txt(c['price'])}"
        for val in c["values"]:
            if light(val).split()[0] not in lr: return f"summary without the chosen {val}"
        return None
    return None

if REGRADE:
    done = json.load(open(REGRADE))
else:
    with concurrent.futures.ThreadPoolExecutor(4) as ex:
        done = list(ex.map(play, cases))
by = {}
for c in done:
    c["fail"] = grade(c)
    by.setdefault(c["kind"], [0, 0])[0 if c["fail"] is None else 1] += 1
if not REGRADE:
  with open(OUT + ".txt", "w") as f:
    for i, c in enumerate(done):
        f.write(f"### {c['kind']}_{i}\n")
        for m, r, s in zip(c["msgs"], c["replies"], c["secs"]): f.write(f"👤 {m}\n🤖 ({s}s) {r}\n")
        f.write("\n")
  json.dump(done, open(OUT + ".json", "w"), ensure_ascii=False, indent=1)
secs = sorted(s for c in done for s in c["secs"])
total_fail = sum(v[1] for v in by.values())
print(f"{SLUG} {MODEL}: {len(done)} cases, {total_fail} failed; avg {sum(secs)/max(1,len(secs)):.1f}s p90 {secs[int(len(secs)*.9)] if secs else 0}s")
for k, (ok, bad) in sorted(by.items()): print(f"  {k}: {ok} ok, {bad} failed")
for c in done:
    if c["fail"]: print(f"  FAIL {c['kind']}: {c['msgs'][-1][:70]} -> {c['fail']}")
