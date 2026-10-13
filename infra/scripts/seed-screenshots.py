#!/usr/bin/env python3
"""Fills an EXISTING clinic and an EXISTING shop with realistic Arabic dummy data for app-store screenshots, through the public/staff APIs only.

  CLINIC_PASSWORD=... SHOP_PASSWORD=... python3 infra/scripts/seed-screenshots.py \
      --clinic-base https://tarek-test.elmanassa.shop --clinic-phone 01120224441 \
      --shop-base https://demo.elmanassa.shop --shop-phone 01001234567 --part all

Everything it creates is tagged by the reserved dummy phone block +20 10 9999 xxxx (patients 0100-0199, shop customers 0200-0299) and every id is written to
infra/scripts/.demo-manifest.json (git-ignored), so demo-cleanup.sql can remove exactly these rows later. Nothing existing is modified.
Needs Pillow for the pictures (pip install pillow).  --connect http://localhost:8080 sends requests to a local backend with the right Host header.
"""
import argparse, io, json, os, random, sys, time, urllib.error, urllib.request
from datetime import date, datetime, timedelta, timezone
from urllib.parse import quote, urlparse
from zoneinfo import ZoneInfo

from PIL import Image, ImageDraw

ap = argparse.ArgumentParser()
ap.add_argument("--part", choices=["clinic", "shop", "all"], default="all")
ap.add_argument("--new", action="store_true", help="create a brand-new demo clinic and shop (with a new owner) and customize them, instead of filling existing tenants")
ap.add_argument("--owner-phone", default="01099990001")
ap.add_argument("--owner-password", default=os.environ.get("OWNER_PASSWORD", "Noor#Demo2026"))
ap.add_argument("--clinic-slug", default="dr-nour")
ap.add_argument("--shop-slug", default="layan-store")
ap.add_argument("--presign-wait", type=float, default=3.2, help="seconds to wait after each upload (limit: 20 per minute)")
ap.add_argument("--clinic-base", default="https://tarek-test.elmanassa.shop")
ap.add_argument("--clinic-phone", help="the clinic owner's login phone")
ap.add_argument("--shop-base", default="https://demo.elmanassa.shop")
ap.add_argument("--shop-phone", default="01001234567")
ap.add_argument("--clinic-password", default=os.environ.get("CLINIC_PASSWORD"))
ap.add_argument("--shop-password", default=os.environ.get("SHOP_PASSWORD"))
ap.add_argument("--patient-password", default=os.environ.get("DEMO_PATIENT_PASSWORD", "Tabib#2026"), help="password of the dummy patients (the Tabib demo login)")
ap.add_argument("--platform-base", default="https://elmanassa.shop")
ap.add_argument("--connect", help="send requests here (e.g. http://localhost:8080) with the real Host header (local test)")
ap.add_argument("--resolve", help="IP to use for every *.root host (when this machine's DNS cannot resolve the subdomains)")
ap.add_argument("--rate", type=float, default=6.8, help="seconds to wait after each booking/checkout (server limits: 10 per minute)")
ap.add_argument("--manifest", default=os.path.join(os.path.dirname(__file__), ".demo-manifest.json"))
a = ap.parse_args()

if a.resolve:
    import socket
    _orig = socket.getaddrinfo
    root = urlparse(a.platform_base).hostname
    socket.getaddrinfo = lambda host, *r, **k: _orig(a.resolve if host == root or host.endswith("." + root) else host, *r, **k)

MANIFEST = {"createdAt": datetime.now(timezone.utc).isoformat(), "clinicBase": a.clinic_base, "shopBase": a.shop_base, "patients": [], "appointments": [], "encounters": [],
            "schedules": [], "services": [], "orders": [], "banners": [], "phones": [], "files": []}


def save():
    with open(a.manifest, "w", encoding="utf-8") as f:
        json.dump(MANIFEST, f, ensure_ascii=False, indent=1)


def norm(p):
    return p if p.startswith("+") else "+2" + p.lstrip("+")


def call(method, base, path, body=None, token=None, raw=None, ctype="application/json", ok404=False):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request((a.connect or base) + path, method=method, data=data)
    if a.connect:
        req.add_header("Host", urlparse(base).netloc)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/json")
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                t = r.read().decode()
                return json.loads(t) if t and r.headers.get("content-type", "").startswith("application/json") else t
        except urllib.error.HTTPError as e:
            msg = e.read().decode()[:400]
            if e.code == 429:
                print("  rate limited, waiting 20s"); time.sleep(20); continue
            if ok404 and e.code in (404, 409):
                return None
            sys.exit(f"{method} {path} -> {e.code}: {msg}")
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt == 3:
                sys.exit(f"{method} {path} failed: {e}")
            time.sleep(2)


def call_soft(method, base, path, body=None):
    """Like call() but a failed request raises RuntimeError (used where the script can try something else)."""
    try:
        return call(method, base, path, body)
    except SystemExit as e:
        raise RuntimeError(str(e))


def pic(w, h, c1, c2, shape="circle", text=None):
    im = Image.new("RGB", (w, h), c1)
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3)))
    light = tuple(min(255, x + 55) for x in c2)
    if shape == "circle":
        d.ellipse([w * .22, h * .18, w * .78, h * .82], fill=light)
    elif shape == "rect":
        d.rounded_rectangle([w * .25, h * .2, w * .75, h * .8], radius=int(w * .08), fill=light)
    else:
        d.polygon([(w * .5, h * .15), (w * .85, h * .8), (w * .15, h * .8)], fill=light)
    b = io.BytesIO()
    im.save(b, "PNG")
    return b.getvalue()


# Real product photos (Pexels, free licence, no attribution required; each one was looked at before it was chosen). Downloaded once into infra/scripts/.demo-images/.
PHOTOS = {"shirt_blue": 9558253, "shirt_white": 16825851, "jeans": 6764007, "dress_black": 16894090, "dress_pink": 902030, "abaya": 7249429, "kids_set": 18493878,
          "kids_tee": 19725676, "cookware": 5782042, "knife": 4226864, "bedding": 212269, "pillow": 3705540, "wide_clothes": 581087,
          "c_waiting": 8459996, "c_reception1": 7108318, "c_reception2": 7108329, "c_doctor": 6749773, "c_ped": 7653122, "c_exam": 4173248, "c_toys": 27177493, "store_cover": 1827130}
IMG_DIR = os.path.join(os.path.dirname(__file__), ".demo-images")


def photo(key, w, h):
    """The named photo, centre-cropped to w x h and returned as JPEG bytes."""
    os.makedirs(IMG_DIR, exist_ok=True)
    path = os.path.join(IMG_DIR, key + ".jpg")
    if not os.path.exists(path):
        i = PHOTOS[key]
        req = urllib.request.Request(f"https://images.pexels.com/photos/{i}/pexels-photo-{i}.jpeg?auto=compress&cs=tinysrgb&w=1400", headers={"User-Agent": "Mozilla/5.0"})
        with open(path, "wb") as f:
            f.write(urllib.request.urlopen(req, timeout=60).read())
    im = Image.open(path).convert("RGB")
    r = max(w / im.width, h / im.height)
    im = im.resize((int(im.width * r) + 1, int(im.height * r) + 1))
    left, top = (im.width - w) // 2, (im.height - h) // 3   # keep the upper part (faces, shirt collars)
    im = im.crop((left, max(0, top), left + w, max(0, top) + h))
    b = io.BytesIO()
    im.save(b, "JPEG", quality=86)
    return b.getvalue()


class Site:
    def __init__(self, base, phone, password):
        self.base = base
        r = call("POST", base, "/api/v1/auth/login", {"identifier": norm(phone), "password": password})
        self.tok = r["accessToken"]

    def api(self, m, path, body=None, **k):
        return call(m, self.base, "/api/v1" + path, body, self.tok, **k)

    def up(self, png, cat, jpeg=False):
        ct, fn = ("image/jpeg", "x.jpg") if jpeg else ("image/png", "x.png")
        p = self.api("POST", "/files/presign", {"filename": fn, "contentType": ct, "size": len(png), "category": cat})
        call("PUT", self.base, p["uploadUrl"], raw=png, ctype=ct)
        time.sleep(a.presign_wait)
        MANIFEST["files"].append(p["fileId"])
        return p["fileId"]


# ------------------------------------------------------------------------------------------------ clinic

NAMES = [("سلمى أحمد", "F", 29), ("يوسف محمود", "M", 7), ("مريم خالد", "F", 34), ("عمر حسن", "M", 41), ("نورهان سعيد", "F", 26), ("آدم إبراهيم", "M", 5), ("هدى علي", "F", 52), ("كريم عادل", "M", 33),
         ("ليلى مصطفى", "F", 8), ("محمد رضا", "M", 61), ("جنى طارق", "F", 3), ("أحمد فؤاد", "M", 38), ("ريم ياسر", "F", 31), ("زياد وليد", "M", 12), ("فاطمة عبدالله", "F", 45), ("مصطفى سامي", "M", 27),
         ("دينا هشام", "F", 36), ("إياد نبيل", "M", 9)]
CHILDREN = [("لجين", "F", 4), ("حمزة", "M", 2), ("ملك", "F", 6)]
COMPLAINTS = ["كحة وسخونية منذ يومين", "ألم في البطن بعد الأكل", "صداع متكرر ودوخة", "متابعة ضغط وسكر", "التهاب في الحلق", "ألم أسفل الظهر", "حساسية جلدية وحكة", "متابعة بعد العلاج"]
MEDS = [("Augmentin", "Amoxicillin/Clavulanate", "625 mg", "قرص", "مرتين يوميًا بعد الأكل", "7 أيام"), ("Panadol", "Paracetamol", "500 mg", "قرص", "كل 8 ساعات عند اللزوم", "3 أيام"),
        ("Cataflam", "Diclofenac", "50 mg", "قرص", "مرتين يوميًا بعد الأكل", "5 أيام"), ("Ventolin", "Salbutamol", "100 mcg", "بخة", "عند الحاجة", "أسبوعين")]
LABS = ["صورة دم كاملة CBC", "سكر صائم HbA1c", "وظائف كبد وكلى", "مزرعة حلق"]


CONSULT_NAME, FOLLOW_NAME = "كشف جديد", "إعادة كشف"


def clinic(s=None):
    s = s or Site(a.clinic_base, a.clinic_phone, a.clinic_password)
    cairo = ZoneInfo("Africa/Cairo")
    now = datetime.now(cairo)
    doctor = s.api("GET", "/clinic/doctors")[0]["id"]
    branch = s.api("GET", "/clinic/branches")[0]["id"]
    services = s.api("GET", "/clinic/services")
    byType = lambda vt: next((x["id"] for x in services if x.get("visitType") == vt and x.get("isActive", True)), services[0]["id"])

    # services in Arabic (only when the clinic has no Arabic ones yet)
    if not any(x["name"] == CONSULT_NAME for x in services):
        for name, mins, price, vt in [(CONSULT_NAME, 30, 30000, "CONSULTATION"), (FOLLOW_NAME, 15, 15000, "FOLLOW_UP")]:
            r = s.api("POST", "/clinic/services", {"name": name, "description": "", "durationMinutes": mins, "priceMinor": price, "visitType": vt})
            MANIFEST["services"].append(r["id"])
        services = s.api("GET", "/clinic/services")
    consult = next(x["id"] for x in services if x["name"] == CONSULT_NAME)
    follow = next(x["id"] for x in services if x["name"] == FOLLOW_NAME)

    # a late schedule for TODAY ONLY so there are bookable slots this evening
    wd = now.isoweekday()
    r = s.api("POST", "/clinic/schedules", {"doctorId": doctor, "branchId": branch, "weekday": wd, "startTime": "12:00", "endTime": "23:30", "slotDurationMinutes": 30, "bufferMinutes": 0,
                                           "effectiveFrom": now.date().isoformat(), "effectiveTo": now.date().isoformat()}, ok404=True)
    if r:
        MANIFEST["schedules"].append(r["id"])

    # patients
    people = []
    for i, (name, sex, age) in enumerate(NAMES):
        phone = f"010999901{i:02d}"
        r = s.api("POST", "/clinic/patients", {"firstName": name, "phone": phone, "sex": sex, "ageYears": age, "ageMonths": 0, "initialPassword": a.patient_password}, ok404=True)
        if r is None:  # already exists (re-run): find it
            r = next(x for x in s.api("GET", "/clinic/patients?q=" + phone)["data"])
        people.append({"id": r["id"], "name": name, "phone": norm(phone)})
        MANIFEST["patients"].append({"id": r["id"], "phone": norm(phone)}); MANIFEST["phones"].append(norm(phone))
    for k, (cname, sex, age) in enumerate(CHILDREN):
        parent = people[2 + k * 3]
        r = s.api("POST", "/clinic/patients", {"firstName": cname, "guardianPatientId": parent["id"], "relationship": "PARENT", "sex": sex, "ageYears": age, "ageMonths": 0}, ok404=True)
        if r:
            MANIFEST["patients"].append({"id": r["id"], "child": True})
    save()
    print(f"  {len(people)} patients + {len(CHILDREN)} children")

    def pace():
        time.sleep(a.rate)

    def walk(p, vt="CONSULTATION"):
        r = s.api("POST", "/clinic/appointments/walk-in", {"patientId": p["id"], "doctorId": doctor, "branchId": branch, "visitType": vt})
        MANIFEST["appointments"].append(r["id"]); pace()
        return r["id"]

    def visit(p, appt, complaint, finish, vt_note=True, with_rx=True, with_lab=False, hr=88):
        s.api("POST", f"/clinic/appointments/{appt}/call", {}, ok404=True)
        s.api("POST", f"/clinic/appointments/{appt}/start", {})
        enc = s.api("POST", "/clinic/encounters", {"patientId": p["id"], "appointmentId": appt, "chiefComplaint": complaint})["id"]
        MANIFEST["encounters"].append(enc)
        s.api("POST", f"/clinic/encounters/{enc}/vitals", {"heightCm": 168, "weightKg": 70, "temperatureC": 37.8, "heartRateBpm": hr, "respiratoryRate": 18, "systolicBp": 120, "diastolicBp": 80, "oxygenSaturation": 98})
        if vt_note:
            s.api("POST", f"/clinic/encounters/{enc}/notes", {"noteType": "PROGRESS", "content": "الحالة مستقرة. تم شرح خطة العلاج والمتابعة للمريض.", "patientVisible": True})
            s.api("POST", f"/clinic/encounters/{enc}/conditions", {"name": "التهاب الجهاز التنفسي العلوي", "status": "ACTIVE", "notes": ""}, ok404=True)
        if with_rx:
            items = [{"medicationName": m[0], "genericName": m[1], "strength": m[2], "dosage": m[3], "frequency": m[4], "duration": m[5], "quantity": "1", "instructions": "بعد الأكل"} for m in random.sample(MEDS, 2)]
            s.api("POST", f"/clinic/encounters/{enc}/prescriptions", {"items": items, "notes": "الراحة وشرب سوائل كثيرة", "issue": True})
        if with_lab:
            lab = s.api("POST", f"/clinic/encounters/{enc}/lab-orders", {"testName": random.choice(LABS), "instructions": "صيام 8 ساعات", "priority": "ROUTINE", "kind": "LAB"})
            s.api("POST", f"/clinic/lab-orders/{lab['id']}/results", {"resultText": "جميع القيم ضمن الحدود الطبيعية.", "resultSummary": "طبيعي", "patientVisible": True}, ok404=True)
            s.api("POST", f"/clinic/lab-orders/{lab['id']}/review", {"shareWithPatient": True}, ok404=True)
        if finish:
            s.api("PATCH", f"/clinic/encounters/{enc}", {"clinicalSummary": "فحص سريري كامل. العلاج الموصوف لمدة أسبوع مع المتابعة عند اللزوم.", "followUpDate": (now.date() + timedelta(days=7)).isoformat()})
            s.api("POST", f"/clinic/appointments/{appt}/complete", {})
        return enc

    tabib, p1, p2, p3 = people[0], people[1], people[2], people[3]
    # finished visits today: the Tabib patient has three (record screen), two more patients one each
    for k in range(3):
        visit(tabib, walk(tabib, "CONSULTATION" if k == 0 else "FOLLOW_UP"), COMPLAINTS[k], True, with_lab=(k < 2))
    visit(p1, walk(p1), COMPLAINTS[3], True, with_lab=True)
    visit(p2, walk(p2, "FOLLOW_UP"), COMPLAINTS[4], True, with_rx=True)
    print("  finished visits created")

    # the line right now: one with the doctor, then four waiting (the Tabib patient is third, so two are ahead)
    inprog = walk(people[4])
    visit(people[4], inprog, COMPLAINTS[5], False, with_rx=False)
    w = [walk(people[5], "CONSULTATION"), walk(people[6], "FOLLOW_UP"), walk(tabib, "CONSULTATION"), walk(people[7], "CONSULTATION"), walk(people[8], "FOLLOW_UP")]
    s.api("POST", f"/clinic/appointments/{w[0]}/call", {})
    print("  queue created: 1 in progress, 5 waiting (first one called)")

    # booked for later today (Reserved), one no-show and one cancelled
    date_s = now.date().isoformat()
    free = [x for x in s.api("GET", f"/clinic/appointments/slots?doctorId={doctor}&branchId={branch}&serviceId={consult}&date={date_s}")
            if datetime.fromisoformat(x.replace("Z", "+00:00")).astimezone(cairo) > now + timedelta(minutes=50)]
    if len(free) < 6:
        date_s = (now.date() + timedelta(days=1)).isoformat()
        free = s.api("GET", f"/clinic/appointments/slots?doctorId={doctor}&branchId={branch}&serviceId={consult}&date={date_s}")
        print("  WARNING: not enough slots left today, reserved bookings are for tomorrow")
    step = max(1, len(free) // 6)
    picks = free[::step][:6]
    for k, slot in enumerate(picks):
        p = people[9 + k]
        r = s.api("POST", "/clinic/appointments", {"patientId": p["id"], "doctorId": doctor, "branchId": branch, "serviceId": follow if k % 3 == 1 else consult, "startAt": slot,
                                                   "paymentMethod": "CASH_AT_CLINIC", "source": "RECEPTION", "visitType": "FOLLOW_UP" if k % 3 == 1 else "CONSULTATION"})
        MANIFEST["appointments"].append(r["id"]); pace()
        if k == 4:
            s.api("POST", f"/clinic/appointments/{r['id']}/no-show", {})
        if k == 5:
            s.api("POST", f"/clinic/appointments/{r['id']}/cancel", {"reason": "اعتذر المريض"})
    save()
    print("  reserved bookings created")


# ------------------------------------------------------------------------------------------------ shop

BUYERS = ["منى السيد", "أحمد جمال", "هبة عمرو", "كريم صلاح", "سارة مجدي", "مازن شريف", "رنا أيمن", "خالد عصام", "دعاء رمضان", "عمرو حلمي", "نادين وائل", "طارق ممدوح", "إيمان لطفي", "باسم عزت"]
CITIES = [("القاهرة", "CAI", "مدينة نصر"), ("الجيزة", "GIZ", "الدقي"), ("الإسكندرية", "ALX", "سموحة"), ("القاهرة", "CAI", "المعادي"), ("الجيزة", "GIZ", "6 أكتوبر")]


def shop(s=None):
    s = s or Site(a.shop_base, a.shop_phone, a.shop_password)
    methods = s.api("GET", "/store/shipping-methods")
    if not methods:
        s.api("POST", "/store/shipping-methods", {"type": "ZONES", "name": "توصيل حسب المحافظة", "feeMinor": 0})
        s.api("POST", "/store/shipping-zones", {"name": "القاهرة الكبرى", "governorateCodes": ["CAI", "GIZ", "QLY"], "feeMinor": 5000, "codFeeMinor": 1000, "etaMinDays": 1, "etaMaxDays": 2, "freeAboveMinor": 150000})
        s.api("POST", "/store/shipping-zones", {"name": "الإسكندرية والدلتا", "governorateCodes": ["ALX", "DKH", "GHR", "SHR", "MNF", "BHR", "KFS", "DMT"], "feeMinor": 7500, "etaMinDays": 2, "etaMaxDays": 4})
        methods = s.api("GET", "/store/shipping-methods")
    method = methods[0]["id"]
    existing_banners = len(s.api("GET", "/store/banners"))
    for i, (t, sub, key) in enumerate([("وصل حديثًا", "تشكيلة الخريف الجديدة بأسعار مميزة", "wide_clothes"), ("بيت أنيق براحة أكثر", "مفروشات ومستلزمات منزلية بأسعار مناسبة", "bedding")]):
        if a.new or existing_banners + i >= 6:
            break
        before = {b["id"] for b in s.api("GET", "/store/banners")}
        s.api("POST", "/store/banners", {"imageFileId": s.up(photo(key, 1600, 640), "PRODUCT_IMAGE", jpeg=True), "title": t, "subtitle": sub, "linkUrl": "/products"})
        MANIFEST["banners"] += [b["id"] for b in s.api("GET", "/store/banners") if b["id"] not in before]

    # replace the drawn sample pictures of the products with real photos
    KEYS = [("قميص قطن", "shirt_blue"), ("قميص كتان", "shirt_white"), ("جينز", "jeans"), ("فستان سهرة", "dress_black"), ("فستان صيفي", "dress_pink"), ("عباية", "abaya"), ("طقم أطفال", "kids_set"),
            ("تيشيرت أطفال", "kids_tee"), ("أواني", "cookware"), ("سكاكين", "knife"), ("ملايات", "bedding"), ("مخدة", "pillow")]
    prods = {} if a.new else s.api("GET", "/store/products?pageSize=100")   # a new shop already has real photos
    for pr in (prods["data"] if isinstance(prods, dict) and prods else prods):
        key = next((k for kw, k in KEYS if kw in pr["name"]), None)
        if not key:
            continue
        detail = s.api("GET", f"/store/products/{pr['id']}")
        old = [m["id"] for m in detail.get("media", [])]
        if any(str(m.get("mediaExt", "")).lower() in ("jpg", "jpeg") for m in detail.get("media", [])):
            continue   # already has a real photo
        s.api("POST", f"/store/products/{pr['id']}/media", {"fileId": s.up(photo(key, 900, 1125), "PRODUCT_IMAGE", jpeg=True), "altText": pr["name"]})
        for mid in old:
            s.api("DELETE", f"/store/products/{pr['id']}/media/{mid}", ok404=True)
        print("  photo ->", pr["name"])

    listing = call("GET", a.shop_base, "/api/v1/shop/products?pageSize=40")
    items = listing["data"] if isinstance(listing, dict) else listing
    variants = []
    for pr in items:
        d = call("GET", a.shop_base, f"/api/v1/shop/products/{quote(pr['slug'])}")
        for v in d.get("variants", []):
            if (v.get("available") or 0) >= 2 and v.get("id"):
                variants.append(v["id"])
                break
    if len(variants) < 4:
        sys.exit("the shop has too few in-stock products to make orders")
    print(f"  {len(variants)} products can be ordered")
    random.seed(7)
    for i, buyer in enumerate(BUYERS):
        city, gov, area = CITIES[i % len(CITIES)]
        r = None
        for attempt in range(6):   # a variant that ran out of stock is replaced by another one
            lines = [{"variantId": v, "quantity": 1} for v in random.sample(variants, random.choice([1, 2]))]
            try:
                r = call_soft("POST", a.shop_base, "/api/v1/shop/checkout", {"items": lines, "shippingMethodId": method, "paymentMethod": "CASH_ON_DELIVERY",
                              "address": {"recipientName": buyer, "phone": f"010999902{i:02d}", "addressLine1": f"{10 + i} شارع النصر", "city": city, "governorateCode": gov, "area": area, "landmark": "بجوار المسجد"}})
                break
            except RuntimeError as e:
                if "INSUFFICIENT_STOCK" not in str(e):
                    sys.exit(str(e))
        if r is None:
            print("  skipped an order: not enough stock"); continue
        MANIFEST["orders"].append(r["orderId"]); MANIFEST["phones"].append(norm(f"010999902{i:02d}"))
        time.sleep(a.rate)
    save()
    # move them along: some new, some preparing, shipped (with tracking), delivered, one returned, one cancelled
    ids = MANIFEST["orders"][-len(BUYERS):]
    plan = ["arrive", "ship", "prep", "new", "arrive", "ship", "new", "return", "cancel", "prep", "arrive", "new", "ship", "confirm"]
    for oid, step in zip(ids, plan):
        if step == "new":
            continue
        if step == "cancel":
            s.api("POST", f"/store/orders/{oid}/status", {"status": "CANCELLED", "reason": "طلب المشتري الإلغاء"}, ok404=True)
            continue
        s.api("POST", f"/store/orders/{oid}/confirm", {}, ok404=True)
        if step in ("prep", "ship", "arrive", "return"):
            s.api("POST", f"/store/orders/{oid}/status", {"status": "PREPARING"}, ok404=True)
        if step in ("ship", "arrive", "return"):
            s.api("PATCH", f"/store/orders/{oid}/tracking", {"courierName": "Bosta", "trackingNumber": f"BO{random.randint(10**8, 10**9 - 1)}", "trackingUrl": ""}, ok404=True)
            s.api("POST", f"/store/orders/{oid}/status", {"status": "SHIPPED"}, ok404=True)
        if step == "arrive":
            s.api("POST", f"/store/orders/{oid}/status", {"status": "ARRIVED"}, ok404=True)
        if step == "return":
            s.api("POST", f"/store/orders/{oid}/status", {"status": "RETURNED", "reason": "المقاس غير مناسب"}, ok404=True)
    print(f"  {len(ids)} orders created and moved through the statuses")



# ------------------------------------------------------------------------------------------------ brand-new tenants (--new)

import ssl

HOURS = {d: {"open": "10:00", "close": "22:00", "closed": d == "fri"} for d in ["sat", "sun", "mon", "tue", "wed", "thu", "fri"]}


def logo(kind, c1, c2):
    """A clean app-style logo: gradient rounded square with a white symbol."""
    S = 512
    im = Image.new("RGB", (S, S), c1)
    d = ImageDraw.Draw(im)
    for y in range(S):
        t = y / S
        d.line([(0, y), (S, y)], fill=tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3)))
    if kind == "clinic":   # medical cross with a heart-beat notch
        d.rounded_rectangle([S * .42, S * .2, S * .58, S * .8], radius=22, fill="white")
        d.rounded_rectangle([S * .2, S * .42, S * .8, S * .58], radius=22, fill="white")
        d.ellipse([S * .44, S * .44, S * .56, S * .56], fill=c2)
    else:                  # shopping bag
        d.rounded_rectangle([S * .24, S * .36, S * .76, S * .8], radius=34, fill="white")
        d.arc([S * .36, S * .18, S * .64, S * .5], 180, 360, fill="white", width=26)
        d.ellipse([S * .38, S * .5, S * .46, S * .58], fill=c2)
        d.ellipse([S * .54, S * .5, S * .62, S * .58], fill=c2)
    b = io.BytesIO()
    im.save(b, "PNG")
    return b.getvalue()


def category(kind, prefs):
    cats = call("GET", a.platform_base, f"/api/v1/onboarding/categories?type={kind}")
    codes = [c["code"] for c in (cats["data"] if isinstance(cats, dict) and "data" in cats else cats)]
    return next((p for p in prefs if p in codes), codes[0])


def onboard(kind, name, slug, first, last, prefs):
    r = call("POST", a.platform_base, "/api/v1/onboarding/tenants", {"type": kind, "name": name, "slug": slug, "ownerFirstName": first, "ownerLastName": last, "phone": norm(a.owner_phone),
             "password": a.owner_password, "locale": "ar", "categories": [category(kind, prefs)]}, ok404=True)
    host = f"{slug}.{urlparse(a.platform_base).netloc}"
    print(("  created " if r else "  already exists: ") + host)
    if r:
        MANIFEST["tenants"] = MANIFEST.get("tenants", []) + [r["tenant"]["id"]]
    return f"{urlparse(a.platform_base).scheme}://{host}"


def wait_https(base):
    host = urlparse(base).netloc
    if a.connect:
        return   # local test: no TLS
    for i in range(120):
        try:
            urllib.request.urlopen(urllib.request.Request(base + "/api/v1/clinic/public/working-days?doctorId=x&branchId=x", headers={"Accept": "application/json"}), timeout=15, context=ssl.create_default_context())
            return
        except urllib.error.HTTPError:
            return   # the server answered over valid TLS
        except Exception:
            if i == 0:
                print(f"  waiting for a valid HTTPS certificate for {host} (add it to the certificate on the server) ...")
            time.sleep(5)
    sys.exit(f"no valid HTTPS for {host}")


def customize_clinic(s):
    wait_logo = logo("clinic", (13, 148, 136), (59, 130, 246))
    s.api("PATCH", "/tenant/branding", {"primaryColor": "#0e9488", "secondaryColor": "#3b82f6", "logoFileId": s.up(wait_logo, "LOGO"), "locale": "ar"})
    doctor = s.api("GET", "/clinic/doctors")[0]
    branch = s.api("GET", "/clinic/branches")[0]["id"]
    s.api("PATCH", "/clinic/doctors/me", {"fields": {"displayName": "د. نور الهدى منصور", "bio": "استشارية طب الأطفال وحديثي الولادة، خبرة تزيد عن ١٢ عامًا في متابعة نمو الأطفال والتطعيمات وعلاج أمراض الصدر والحساسية. أؤمن بأن طفلك يستحق وقتًا كافيًا وشرحًا واضحًا لكل خطوة.",
                                          "publicPhone": "01099990002", "qualifications": "بكالوريوس الطب والجراحة - جامعة القاهرة، ماجستير طب الأطفال، زمالة الأمراض الصدرية للأطفال", "yearsExperience": 12,
                                          "languages": ["العربية", "English"], "defaultAppointmentFeeMinor": 40000, "gender": "F", "profileImageFileId": s.up(photo("c_doctor", 800, 1000), "AVATAR", jpeg=True)}}, ok404=True)
    s.api("PATCH", f"/clinic/branches/{branch}", {"name": "فرع مدينة نصر", "addressLine1": "٢٤ شارع عباس العقاد، مدينة نصر", "city": "القاهرة", "district": "مدينة نصر", "phone": "01099990002", "landmark": "بجوار سيتي ستارز",
                                                 "mapsUrl": "https://maps.google.com/?q=Abbas+El+Akkad+Nasr+City"}, ok404=True)
    gallery = [s.up(photo(k, 1200, 900), "PRODUCT_IMAGE", jpeg=True) for k in ("c_reception1", "c_waiting", "c_reception2", "c_ped", "c_toys", "c_exam")]
    s.api("PATCH", "/clinic/profile", {
        "clinicName": "عيادة د. نور الهدى لطب الأطفال", "tagline": "رعاية دافئة لطفلك من أول يوم",
        "about": "عيادة متخصصة في طب الأطفال وحديثي الولادة بمدينة نصر. نقدّم كشفًا شاملًا، متابعة النمو والتغذية، التطعيمات، وعلاج الحساسية وأمراض الصدر، في بيئة هادئة مجهزة بأحدث الأجهزة وركن ألعاب لطفلك.",
        "phone": "01099990002", "email": "info@dr-nour.example", "addressText": "٢٤ شارع عباس العقاد، مدينة نصر، القاهرة", "whatsapp": "01099990002", "extraPhones": ["01099990003"],
        "facebookUrl": "https://facebook.com/dr.nour.clinic", "instagramUrl": "https://instagram.com/dr.nour.clinic", "tiktokUrl": "https://tiktok.com/@dr.nour.clinic", "mapsUrl": "https://maps.google.com/?q=Abbas+El+Akkad+Nasr+City",
        "workingHours": HOURS, "coverFileId": s.up(photo("c_waiting", 1600, 700), "PRODUCT_IMAGE", jpeg=True), "gallery": gallery, "establishedYear": 2014,
        "announcement": "🩺 الحجز متاح اليوم - كشف الأطفال والتطعيمات - تقبل العيادة جميع شركات التأمين الكبرى", "isOpen": True,
        "insurance": ["بوبا مصر", "ميدنت", "أليانز", "أكسا", "مصر للتأمين", "ميتلايف"],
        "faqs": [{"q": "كيف أحجز موعدًا؟", "a": "من زر احجز الآن بالموقع أو التطبيق، واختر الخدمة والموعد المناسب، وستصلك رسالة تأكيد."},
                 {"q": "ما هي مواعيد التطعيمات؟", "a": "نقدّم التطعيمات الأساسية والاختيارية يوميًا بنظام الجدول الزمني الرسمي لوزارة الصحة."},
                 {"q": "هل توجد خدمة استشارة هاتفية؟", "a": "نعم، استشارة هاتفية قصيرة للمتابعة بعد الكشف بسعر مخفّض."},
                 {"q": "هل يمكن إحضار الأشقاء في نفس الموعد؟", "a": "يمكنك حجز كشف منفصل لكل طفل بنفس الوقت وسننسّق بينهما."},
                 {"q": "ماذا أحضر معي في أول زيارة؟", "a": "كارت التطعيمات وأي تحاليل أو أشعة سابقة وأسماء الأدوية الحالية."}],
        "siteLayout": {"order": ["hero", "about", "doctors", "services", "visit", "gallery", "faq", "insurance", "contact", "how"], "hidden": []}})
    print("  clinic customized")


PRODUCTS = [  # name, brand, category, audience, price, compare-at, photo key, badge, colours, sizes, featured, stock
    ("قميص قطن أزرق كلاسيك", "نور", "shirts-tops", "MEN", 45000, 60000, "shirt_blue", "NEW", ["أزرق", "أبيض", "كحلي"], ["S", "M", "L"], True, 9),
    ("قميص كتان أبيض", "نور", "shirts-tops", "MEN", 52000, None, "shirt_white", "", ["أبيض", "بيج"], ["S", "M", "L"], False, 10),
    ("بنطلون جينز سليم", "دينم", "pants-jeans", "MEN", 80000, 100000, "jeans", "SALE", ["أزرق", "كحلي"], ["M", "L", "XL"], False, 12),
    ("فستان سهرة أسود", "أناقة", "dresses", "WOMEN", 150000, None, "dress_black", "BEST_SELLER", ["أسود", "كحلي"], ["S", "M", "L"], True, 6),
    ("فستان صيفي زهري", "أناقة", "dresses", "WOMEN", 95000, 120000, "dress_pink", "", ["وردي", "أبيض"], ["S", "M", "L"], False, 8),
    ("عباية كريب أصفر", "بيت الحجاب", "abayas-modest", "WOMEN", 120000, None, "abaya", "NEW", ["أصفر", "أسود"], ["M", "L", "XL"], True, 9),
    ("طقم أطفال قطن", "كيدو", "outfit-sets", "BOYS", 48000, 60000, "kids_set", "", ["رمادي", "أزرق"], ["2 سنة", "4 سنة", "6 سنة"], False, 14),
    ("تيشيرت أطفال وردي", "كيدو", "shirts-tops", "GIRLS", 25000, None, "kids_tee", "NEW", ["وردي", "أبيض"], ["4 سنة", "6 سنة", "8 سنة"], False, 20),
    ("طقم أواني ستانلس ١٠ قطع", "هوم بلس", "cookware", "", 380000, 450000, "cookware", "SALE", [], [], True, 7),
    ("سكين شيف ياباني", "هوم بلس", "kitchen-tools", "", 65000, None, "knife", "", [], [], False, 11),
    ("طقم ملايات قطن مصري", "نيل لينن", "bedding", "", 210000, 260000, "bedding", "BEST_SELLER", ["أبيض", "بيج"], [], True, 8),
    ("مخدة طبية مريحة", "نيل لينن", "pillows-quilts", "", 65000, None, "pillow", "LIMITED", [], [], False, 9),
]


def customize_shop(s):
    s.api("PATCH", "/tenant/branding", {"primaryColor": "#be185d", "secondaryColor": "#f59e0b", "logoFileId": s.up(logo("shop", (190, 24, 93), (245, 158, 11)), "LOGO"), "locale": "ar"})
    s.api("PATCH", "/store/profile", {
        "storeName": "متجر ليان للأزياء والمنزل", "shortDescription": "أزياء عصرية ومستلزمات منزلية بجودة عالية وتوصيل لجميع المحافظات",
        "about": "متجر ليان يقدّم تشكيلة مختارة من الأزياء الرجالية والنسائية والأطفال ومستلزمات المطبخ والمفروشات بأسعار مناسبة، مع الدفع عند الاستلام وسياسة استرجاع مرنة.",
        "supportPhone": "01099990004", "whatsapp": "01099990004", "extraPhones": ["01099990005"], "supportEmail": "hello@layan.example", "addressText": "١٢ شارع الهرم، الجيزة", "mapsUrl": "https://maps.google.com/?q=Haram+Giza",
        "facebookUrl": "https://facebook.com/layan.store", "instagramUrl": "https://instagram.com/layan.store", "tiktokUrl": "https://tiktok.com/@layan.store",
        "announcement": "🚚 شحن مجاني للطلبات فوق ١٥٠٠ جنيه · الدفع عند الاستلام · خصم ١٠٪ بكود WELCOME10", "workingHours": HOURS, "returnWindowDays": 14, "vatIncluded": True, "vatPercent": 14,
        "shippingPolicy": "نوصّل لجميع المحافظات خلال ١-٤ أيام عمل، والشحن مجاني للطلبات فوق ١٥٠٠ جنيه.", "returnPolicy": "يمكنك استرجاع المنتج خلال ١٤ يومًا من الاستلام بشرط عدم الاستخدام.",
        "coverFileId": s.up(photo("store_cover", 1600, 700), "PRODUCT_IMAGE", jpeg=True),
        "trustTiles": [{"title": "الدفع عند الاستلام", "text": "ادفع عند وصول طلبك"}, {"title": "توصيل لكل المحافظات", "text": "خلال ١-٤ أيام عمل"}, {"title": "استرجاع خلال ١٤ يومًا", "text": "استبدال سهل بدون تعقيد"}, {"title": "دعم عبر واتساب", "text": "نرد عليك خلال دقائق"}],
        "siteLayout": {"order": ["banners", "trust", "categories", "featured", "offers", "bestSellers", "newest", "whatsapp", "branches"], "hidden": []}})
    s.api("POST", "/store/branches", {"name": "فرع الهرم", "code": "HRM", "phone": "01099990004", "whatsapp": "01099990004", "address": "١٢ شارع الهرم", "city": "الجيزة", "governorateCode": "GIZ", "area": "الهرم", "landmark": "أمام محطة المترو", "isPickup": True, "workingHours": HOURS}, ok404=True)
    branch = s.api("GET", "/store/branches")[0]["id"]
    for i, (t, sub, key) in enumerate([("وصل حديثًا", "تشكيلة الخريف الجديدة بأسعار مميزة", "wide_clothes"), ("أناقة بلا حدود", "ملابس رجالي وحريمي وأطفال", "store_cover"), ("بيت أنيق براحة أكثر", "مفروشات ومستلزمات منزلية", "bedding")]):
        s.api("POST", "/store/banners", {"imageFileId": s.up(photo(key, 1600, 640), "PRODUCT_IMAGE", jpeg=True), "title": t, "subtitle": sub, "linkUrl": "/products"})
    s.api("POST", "/store/shipping-methods", {"type": "ZONES", "name": "توصيل حسب المحافظة", "feeMinor": 0}, ok404=True)
    s.api("POST", "/store/shipping-zones", {"name": "القاهرة الكبرى", "governorateCodes": ["CAI", "GIZ", "QLY"], "feeMinor": 5000, "codFeeMinor": 1000, "etaMinDays": 1, "etaMaxDays": 2, "freeAboveMinor": 150000}, ok404=True)
    s.api("POST", "/store/shipping-zones", {"name": "الإسكندرية والدلتا", "governorateCodes": ["ALX", "DKH", "GHR", "SHR", "MNF", "BHR", "KFS", "DMT"], "feeMinor": 7500, "etaMinDays": 2, "etaMaxDays": 4, "freeAboveMinor": 150000}, ok404=True)
    s.api("POST", "/store/shipping-zones", {"name": "الصعيد", "governorateCodes": ["FYM", "BNS", "MNY", "AST", "SHG", "QNA", "LXR", "ASN"], "feeMinor": 10000, "etaMinDays": 3, "etaMaxDays": 6}, ok404=True)
    s.api("POST", "/store/coupons", {"code": "WELCOME10", "discountType": "PERCENT", "value": 10, "minOrderMinor": 50000}, ok404=True)
    for i, (n, brand, slug, aud, price, cmp_, key, badge, colors, sizes, feat, stock) in enumerate(PRODUCTS):
        options = ([{"name": "اللون", "attribute": "COLOR", "values": colors}] if colors else []) + ([{"name": "المقاس", "attribute": "SIZE", "values": sizes, **({"sizeScale": "KIDS"} if "سنة" in sizes[0] else {})}] if sizes else [])
        combos = [dict()]
        for o in options:
            combos = [{**c, o["name"]: v} for c in combos for v in o["values"]]
        body = {"name": n, "brand": brand, "taxonomySlug": slug, "audience": aud or None, "description": f"{n} بخامات ممتازة وتصميم عملي يناسب الاستخدام اليومي.\nجودة مضمونة وسهولة في الاسترجاع.", "shortDescription": "جودة ممتازة وسعر مناسب",
                "options": options, "variants": [{"sku": f"LYN{i:02d}-{j}", "priceMinor": price, "compareAtPriceMinor": cmp_, "optionValues": cv, "stock": [{"branchId": branch, "quantity": stock}]} for j, cv in enumerate(combos)],
                "media": [{"fileId": s.up(photo(key, 900, 1125), "PRODUCT_IMAGE", jpeg=True), "altText": n}]}
        pid = s.api("POST", "/store/products", body)["id"]
        s.api("PATCH", f"/store/products/{pid}/extras", {"badge": badge, "isFeatured": feat, "tags": [brand], "specs": [{"k": "الخامة", "v": "جودة عالية"}, {"k": "بلد الصنع", "v": "مصر"}], "sizeGuide": ""})
    print("  shop customized with", len(PRODUCTS), "products")


def write_credentials():
    path = os.path.join(os.path.dirname(__file__), "DEMO-CREDENTIALS.local.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"""# Demo logins (dummy data, git-ignored)

Business app (one login, shows both businesses):  phone {a.owner_phone}  password {a.owner_password}
  - clinic: عيادة د. نور الهدى لطب الأطفال  https://{a.clinic_slug}.elmanassa.shop
  - shop:   متجر ليان للأزياء والمنزل        https://{a.shop_slug}.elmanassa.shop
Tabib patient:  phone 01099990100 (or the 6-digit patient ID)  password {a.patient_password}
""")
    print("credentials written to", path)


if a.new:
    write_credentials()
    scheme_root = a.platform_base
    cb = onboard("CLINIC", "عيادة د. نور الهدى لطب الأطفال", a.clinic_slug, "نور", "الهدى منصور", ["pediatrics", "general_practice"])
    sb = onboard("STORE", "متجر ليان للأزياء والمنزل", a.shop_slug, "نور", "الهدى منصور", ["fashion_women", "fashion_men"])
    save()
    wait_https(cb); wait_https(sb)
    a.clinic_base, a.shop_base, a.clinic_phone, a.shop_phone = cb, sb, a.owner_phone, a.owner_phone
    a.clinic_password = a.shop_password = a.owner_password
    CONSULT_NAME, FOLLOW_NAME = "كشف أطفال", "إعادة كشف"
    print("clinic:"); cs = Site(cb, a.owner_phone, a.owner_password)
    # Arabic services only: the two English defaults are switched off
    for sv in cs.api("GET", "/clinic/services"):
        if sv["name"] in ("Consultation", "Follow-up"):
            cs.api("PUT", f"/clinic/services/{sv['id']}/active", {"active": False}, ok404=True)
    customize_clinic(cs)
    for name, mins, price, vt in [("استشارة هاتفية", 15, 25000, None), ("متابعة نمو وتغذية", 30, 35000, None)]:
        r = cs.api("POST", "/clinic/services", {"name": name, "description": "", "durationMinutes": mins, "priceMinor": price, **({"visitType": vt} if vt else {})}); MANIFEST["services"].append(r["id"])
    clinic(cs); save()
    print("shop:"); ss = Site(sb, a.owner_phone, a.owner_password)
    customize_shop(ss)
    shop(ss); save()
    print("done. manifest:", a.manifest)
    sys.exit(0)

if a.part in ("clinic", "all"):
    if not (a.clinic_phone and a.clinic_password):
        sys.exit("clinic part needs --clinic-phone and CLINIC_PASSWORD")
    print("clinic:"); clinic(); save()
if a.part in ("shop", "all"):
    if not a.shop_password:
        sys.exit("shop part needs SHOP_PASSWORD")
    print("shop:"); shop(); save()
print("done. manifest:", a.manifest)
