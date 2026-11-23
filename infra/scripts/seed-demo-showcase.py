#!/usr/bin/env python3
"""Two small demo businesses to look at the new website designs with real content.

  --kind service : «صالون لمسة» (SERVICE tenant): 3 service categories, 9 services with duration and price, 3 staff, weekly booking hours and 2 products on the side.
  --kind small   : «أفق للساعات» (STORE tenant with exactly 3 products), eligible for the 1-3 product designs.

  python3 infra/scripts/seed-demo-showcase.py --kind service --base https://elmanassa.shop --slug lamsa-salon --phone 01000000771 --password '<choose one>' --stage onboard
  (the shop's host must be in the HTTPS certificate: infra/scripts/test-https-add-hosts.sh elmanassa.shop lamsa-salon.elmanassa.shop, run on the server)
  python3 infra/scripts/seed-demo-showcase.py --kind service ... --stage seed

Needs Pillow. No orders and no bookings are created. The password comes from --password or DEMO_PASSWORD and is never stored in the repo.
Pictures are drawn (no photos), so nothing here claims a real business, a real person or a real product.
"""
import argparse, io, json, os, sys, urllib.error, urllib.request
from urllib.parse import urlparse

from PIL import Image, ImageDraw

ap = argparse.ArgumentParser()
ap.add_argument("--kind", choices=["service", "small"], required=True)
ap.add_argument("--base", default="https://elmanassa.shop")
ap.add_argument("--slug", required=True)
ap.add_argument("--phone", required=True)
ap.add_argument("--password", default=os.environ.get("DEMO_PASSWORD"))
ap.add_argument("--connect", help="send requests to this URL (e.g. http://localhost:8080) with the shop's Host header; for local dry runs")
ap.add_argument("--stage", choices=["onboard", "seed", "all"], default="all")
ap.add_argument("--template", help="website design to select (default: vn-wellness for service, pn-trio for small)")
a = ap.parse_args()
if not a.password:
    sys.exit("pass --password or set DEMO_PASSWORD")
root = urlparse(a.base)
scheme, root_host = root.scheme, root.netloc
shop_base = f"{scheme}://{a.slug}.{root_host}"
PHONE = a.phone if a.phone.startswith("+") else "+2" + a.phone.lstrip("+")


def call(method, base, path, body=None, token=None, raw=None, ctype="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request((a.connect or base) + path, method=method, data=data)
    if a.connect:
        req.add_header("Host", urlparse(base).hostname)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read().decode()
            return json.loads(t) if t and r.headers.get("content-type", "").startswith("application/json") else t
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")


def pic(w, h, c1, c2, shape="circle"):
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


SERVICE = a.kind == "service"
NAME = "صالون لمسة" if SERVICE else "أفق للساعات"
TEMPLATE = a.template or ("vn-wellness" if SERVICE else "pn-trio")


def onboard():
    r = call("POST", a.base, "/api/v1/onboarding/tenants", {"type": "SERVICE" if SERVICE else "STORE", "name": NAME, "slug": a.slug, "ownerFirstName": "صاحب النشاط", "phone": PHONE,
             "password": a.password, "categories": ["cosmetics"] if SERVICE else ["watches_eyewear"], "locale": "ar"})
    print("created", r.get("host") or r)


def seed():
    tok = call("POST", shop_base, "/api/v1/auth/login", {"identifier": PHONE, "password": a.password})["accessToken"]

    def up(png, cat):
        p = call("POST", shop_base, "/api/v1/files/presign", {"filename": "x.png", "contentType": "image/png", "size": len(png), "category": cat}, tok)
        call("PUT", shop_base, p["uploadUrl"], raw=png, ctype="image/png")
        return p["fileId"]

    def api(m, path, body=None):
        return call(m, shop_base, "/api/v1" + path, body, tok)

    palette = ((122, 74, 90), (214, 160, 150)) if SERVICE else ((24, 42, 74), (120, 160, 210))
    logo = up(pic(256, 256, *palette), "LOGO")
    api("PATCH", "/tenant/branding", {"primaryColor": "#8a4b5c" if SERVICE else "#1c3a6b", "secondaryColor": "#d9a68f" if SERVICE else "#7aa0d4", "logoFileId": logo, "locale": "ar"})
    hours = {d: {"open": "10:00", "close": "21:00", "closed": d == "fri"} for d in ["sat", "sun", "mon", "tue", "wed", "thu", "fri"]}
    profile = {
        "storeName": NAME,
        "shortDescription": "عناية بالشعر والأظافر والبشرة في جو هادئ، بمواعيد تحجزها بنفسك" if SERVICE else "ثلاث ساعات مختارة بعناية، بضمان سنتين وتوصيل لكل المحافظات",
        "about": "نشاط تجريبي لعرض تصميمات المواقع الجديدة. كل ما فيه بيانات تجريبية.",
        "supportPhone": "01000000000", "whatsapp": "01000000000", "addressText": "شارع النيل، المعادي، القاهرة", "workingHours": hours,
        "announcement": "احجزي موعدك أونلاين في أقل من دقيقة" if SERVICE else "شحن مجاني للطلبات فوق ٢٠٠٠ جنيه · الدفع عند الاستلام",
        "coverFileId": up(pic(1600, 700, *palette), "PRODUCT_IMAGE"),
    }
    api("PATCH", "/store/profile", profile)

    if SERVICE:
        cats = {}
        for n in ["الشعر", "الأظافر", "البشرة والعناية"]:
            cats[n] = api("POST", "/store/service-categories", {"name": n})["id"]
        rows = [  # name, category, minutes, EGP, before, description
            ("قص وتصفيف", "الشعر", 45, 250, None, "قص حسب شكل الوجه مع تصفيف بالسشوار."),
            ("صبغة شعر كاملة", "الشعر", 120, 900, 1100, "صبغة بألوان مختارة مع جلسة عناية بعدها."),
            ("جلسة كيراتين", "الشعر", 150, 1500, None, "علاج يرطب الشعر ويقلل الهيشان."),
            ("مانيكير", "الأظافر", 40, 180, None, "تنظيف وتقليم وطلاء."),
            ("باديكير", "الأظافر", 50, 220, None, "عناية كاملة بالقدمين مع طلاء."),
            ("جل بولش", "الأظافر", 60, 300, 350, "طلاء جل يدوم لأسابيع."),
            ("تنظيف بشرة عميق", "البشرة والعناية", 60, 450, None, "تنظيف وتقشير وماسك مناسب لنوع البشرة."),
            ("ماسك ترطيب", "البشرة والعناية", 30, 200, None, "ماسك مرطب مع تدليك خفيف."),
            ("إزالة شعر بالشمع", "البشرة والعناية", 30, 150, None, "حسب المنطقة، يُحدد السعر النهائي في الصالون."),
        ]
        sids = []
        for i, (n, c, m, p, before, desc) in enumerate(rows):
            body = {"name": n, "description": desc, "shortDescription": desc.split("،")[0].split(".")[0], "durationMinutes": m, "bufferMinutes": 10, "priceMinor": p * 100, "active": True,
                    "categoryId": cats[c], "locationMode": "SHOP", "serviceArea": "", "instructions": "", "cancellationPolicy": "يمكن الإلغاء قبل الموعد بـ ٢٤ ساعة.",
                    "imageFileId": up(pic(900, 700, (122 + i * 8, 74, 90), (214, 160 - i * 6, 150), ["circle", "rect", "tri"][i % 3]), "PRODUCT_IMAGE"), "gallery": [], "requiredFields": []}
            if before:
                body["priceBeforeMinor"] = before * 100
            sids.append(api("POST", "/store/services", body)["id"])
        for n, ids in [("منى", sids[:6]), ("هالة", sids[3:]), ("سلمى", sids)]:
            api("POST", "/store/staff", {"name": n, "capacity": 1, "active": True, "serviceIds": ids, "hours": []})
        api("PUT", "/store/booking/hours", {"ranges": [{"weekday": w, "opens": "10:00", "closes": "20:00"} for w in (6, 7, 1, 2, 3, 4)]})
        branch = api("GET", "/store/branches")
        branch = branch[0]["id"] if branch else api("POST", "/store/branches", {"name": "الفرع الرئيسي", "code": "MAIN", "phone": "01000000000", "address": "شارع النيل", "city": "القاهرة", "governorateCode": "CAI", "area": "المعادي"})["id"]
        items = [("سيروم شعر مغذي", "skincare", 32000, (200, 150, 120)), ("طقم عناية بالأظافر", "beauty-tools", 28000, (214, 120, 140))]
    else:
        branch = api("GET", "/store/branches")
        branch = branch[0]["id"] if branch else api("POST", "/store/branches", {"name": "الفرع الرئيسي", "code": "MAIN", "phone": "01000000000", "address": "شارع النيل", "city": "القاهرة", "governorateCode": "CAI", "area": "المعادي"})["id"]
        items = [("ساعة أفق الكلاسيكية", "smart-watches", 345000, (24, 42, 74)), ("ساعة أفق الرياضية", "smart-watches", 289000, (30, 90, 80)), ("ساعة أفق الذهبية", "smart-watches", 459000, (120, 90, 40))]

    for i, (n, slug, price, col) in enumerate(items):
        f = up(pic(900, 1000, col, tuple(min(255, x + 70) for x in col), ["circle", "rect", "tri"][i % 3]), "PRODUCT_IMAGE")
        colors = ["أسود", "فضي"] if not SERVICE else []
        options = [{"name": "اللون", "attribute": "COLOR", "values": colors}] if colors else []
        combos = [{"اللون": c} for c in colors] or [dict()]
        body = {"name": n, "brand": "لمسة" if SERVICE else "أفق", "taxonomySlug": slug, "description": f"{n}. منتج تجريبي لعرض تصميم الصفحة.\nجودة ممتازة وضمان على الصنعة.", "shortDescription": "جودة ممتازة وسعر مناسب",
                "options": options, "variants": [{"sku": f"SHOW-{a.slug[:4].upper()}-{i}-{j}", "priceMinor": price, "optionValues": cv, "stock": [{"branchId": branch, "quantity": 12}]} for j, cv in enumerate(combos)],
                "media": [{"fileId": f, "altText": n}]}
        api("POST", "/store/products", body)

    api("POST", "/store/shipping-methods", {"type": "ZONES", "name": "توصيل حسب المحافظة", "feeMinor": 0})
    api("POST", "/store/shipping-zones", {"name": "القاهرة الكبرى", "governorateCodes": ["CAI", "GIZ", "QLY"], "feeMinor": 5000, "etaMinDays": 1, "etaMaxDays": 2, "freeAboveMinor": 200000})
    api("POST", "/store/shipping-zones", {"name": "باقي المحافظات", "governorateCodes": ["ALX", "DKH", "GHR", "SHR", "MNF", "BHR", "KFS", "DMT", "FYM", "BNS", "MNY", "AST", "SHG", "QNA", "LXR", "ASN"], "feeMinor": 8000, "etaMinDays": 2, "etaMaxDays": 5})
    api("PATCH", "/store/profile", {"template": TEMPLATE})
    print(f"seeded {a.kind} demo at {shop_base} with design {TEMPLATE}")


if a.stage in ("onboard", "all"):
    onboard()
if a.stage in ("seed", "all"):
    seed()
