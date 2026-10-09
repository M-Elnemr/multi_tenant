#!/usr/bin/env python3
"""Creates (or fills) a demo STORE with Arabic sample data so every shop feature can be tried.

  python3 infra/scripts/seed-demo-store.py --base https://elmanassa.shop --slug demo --phone 01001234567 --password '<choose one>' --stage onboard
  (add the shop's host to the HTTPS certificate: infra/scripts/test-https-add-hosts.sh elmanassa.shop demo.elmanassa.shop)
  python3 infra/scripts/seed-demo-store.py --base https://elmanassa.shop --slug demo --phone 01001234567 --password '<same>' --stage seed

Needs Pillow (pip install pillow) to draw the sample pictures. The password is only passed on the command line / env, never stored in the repo.
No orders are created: place them from the storefront to try confirm -> ship -> track -> return.
"""
import argparse, io, json, os, sys, urllib.error, urllib.request
from urllib.parse import urlparse

from PIL import Image, ImageDraw

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="https://elmanassa.shop", help="platform site (root domain)")
ap.add_argument("--slug", default="demo")
ap.add_argument("--phone", required=True)
ap.add_argument("--password", default=os.environ.get("DEMO_PASSWORD"))
ap.add_argument("--connect", help="send requests to this URL (e.g. http://localhost:8080) but with the shop's Host header; for local dry runs")
ap.add_argument("--stage", choices=["onboard", "seed", "all"], default="all")
ap.add_argument("--parts", default="profile,branches,products,delivery", help="which parts of the seed to run (comma list): profile, branches, products, delivery")
a = ap.parse_args()
if not a.password:
    sys.exit("pass --password or set DEMO_PASSWORD")
root = urlparse(a.base)
scheme, root_host = root.scheme, root.netloc
shop_base = f"{scheme}://{a.slug}.{root_host}"
PHONE = a.phone if a.phone.startswith("+") else "+2" + a.phone.lstrip("+")


def call(method, base, path, body=None, token=None, raw=None, ctype="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    target = (a.connect or base) + path
    req = urllib.request.Request(target, method=method, data=data)
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


def onboard():
    r = call("POST", a.base, "/api/v1/onboarding/tenants", {"type": "STORE", "name": "متجر ديمو", "slug": a.slug, "ownerFirstName": "صاحب المتجر", "phone": PHONE, "password": a.password, "categories": ["fashion_men"]})
    print("created", r["host"])


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


def seed():
    tok = call("POST", shop_base, "/api/v1/auth/login", {"identifier": PHONE, "password": a.password})["accessToken"]

    def up(png, cat):
        p = call("POST", shop_base, "/api/v1/files/presign", {"filename": "x.png", "contentType": "image/png", "size": len(png), "category": cat}, tok)
        call("PUT", shop_base, p["uploadUrl"], raw=png, ctype="image/png")
        return p["fileId"]

    def api(m, path, body=None):
        return call(m, shop_base, "/api/v1" + path, body, tok)

    parts = set(a.parts.split(","))
    hours = {d: {"open": "10:00", "close": "22:00", "closed": d == "fri"} for d in ["sat", "sun", "mon", "tue", "wed", "thu", "fri"]}
    if "profile" in parts:
        logo = up(pic(256, 256, (31, 106, 153), (61, 154, 99)), "LOGO")
        api("PATCH", "/tenant/branding", {"primaryColor": "#b4532a", "secondaryColor": "#e9a23b", "logoFileId": logo, "locale": "ar"})
        api("PATCH", "/store/profile", {
            "storeName": "متجر ديمو", "shortDescription": "ملابس ومستلزمات منزلية بجودة عالية وتوصيل لجميع المحافظات", "about": "هذا متجر تجريبي لتجربة كل مزايا المنصة: البحث، الأقسام، الدفع عند الاستلام، التوصيل حسب المحافظة، التتبع والاسترجاع.",
            "supportPhone": "01001234567", "whatsapp": "01001234567", "extraPhones": ["01111234567"], "supportEmail": "hello@demo.example", "addressText": "٥ شارع التحرير، وسط البلد، القاهرة",
            "mapsUrl": "https://maps.google.com/?q=Tahrir+Square", "facebookUrl": "https://facebook.com/demo", "instagramUrl": "https://instagram.com/demo", "tiktokUrl": "https://tiktok.com/@demo",
            "announcement": "🚚 شحن مجاني للطلبات فوق ١٥٠٠ جنيه · الدفع عند الاستلام", "workingHours": hours, "returnWindowDays": 14, "taxId": "123-456-789", "vatIncluded": True, "vatPercent": 14,
            "coverFileId": up(pic(1600, 700, (120, 60, 30), (233, 162, 59)), "PRODUCT_IMAGE")})
    if "branches" in parts:
        api("POST", "/store/branches", {"name": "فرع وسط البلد", "code": "DT", "phone": "01001234567", "whatsapp": "01001234567", "address": "٥ شارع التحرير", "city": "القاهرة", "governorateCode": "CAI", "area": "وسط البلد", "landmark": "بجوار ميدان التحرير", "isPickup": True, "mapsUrl": "https://maps.google.com/?q=Tahrir", "workingHours": hours})
        api("POST", "/store/branches", {"name": "فرع الإسكندرية", "code": "ALX", "phone": "01111234567", "address": "٢٠ كورنيش النيل", "city": "الإسكندرية", "governorateCode": "ALX", "area": "سموحة"})
        branch = api("GET", "/store/branches")[0]["id"]

    if "products" in parts:
        branch = api("GET", "/store/branches")[0]["id"]
        sizes = [{"kind": "SIZE", "values": ["S", "M", "L"]}]
        colors2 = lambda *c: {"kind": "COLOR", "values": list(c)}
        items = [  # name, brand, category (standard slug), for whom, price, compare-at, picture colour, badge, options, featured, stock
            ("قميص قطن أزرق كلاسيك", "نور", "shirts-tops", "MEN", 45000, 60000, (40, 80, 140), "NEW", [colors2("أزرق", "أبيض", "كحلي"), sizes[0]], True, 9),
            ("قميص كتان أبيض", "نور", "shirts-tops", "MEN", 52000, None, (200, 200, 190), "", [colors2("أبيض", "بيج"), sizes[0]], False, 10),
            ("بنطلون جينز سليم", "Denim Co", "pants-jeans", "MEN", 80000, 100000, (30, 40, 70), "SALE", [colors2("أزرق", "كحلي"), {"kind": "SIZE", "values": ["M", "L", "XL"]}], False, 12),
            ("فستان سهرة أسود", "Elegance", "dresses", "WOMEN", 150000, None, (30, 30, 35), "BEST_SELLER", [colors2("أسود", "كحلي"), sizes[0]], True, 6),
            ("فستان صيفي زهري", "Elegance", "dresses", "WOMEN", 95000, 120000, (220, 130, 150), "", [colors2("وردي", "أبيض", "أصفر"), sizes[0]], False, 8),
            ("عباية كريب", "Hijab House", "abayas-modest", "WOMEN", 120000, None, (25, 25, 30), "NEW", [colors2("أسود", "كحلي"), {"kind": "SIZE", "values": ["M", "L", "XL"]}], True, 9),
            ("طقم أطفال قطن", "Kiddo", "outfit-sets", "BOYS", 48000, 60000, (240, 190, 80), "", [colors2("أصفر", "أزرق"), {"kind": "SIZE", "scale": "KIDS", "values": ["2 سنة", "4 سنة", "6 سنة"]}], False, 14),
            ("تيشيرت أطفال ملون", "Kiddo", "shirts-tops", "GIRLS", 25000, None, (80, 170, 140), "NEW", [colors2("أخضر", "وردي", "متعدد"), {"kind": "SIZE", "scale": "KIDS", "values": ["4 سنة", "6 سنة", "8 سنة"]}], False, 20),
            ("طقم أواني جرانيت ١٠ قطع", "Home+", "cookware", "", 380000, 450000, (110, 110, 120), "SALE", [], True, 5),
            ("مجموعة سكاكين ستانلس", "Home+", "kitchen-tools", "", 95000, None, (150, 150, 160), "", [], False, 0),
            ("طقم ملايات قطن مصري", "Nile Linen", "bedding", "", 210000, 260000, (180, 160, 130), "BEST_SELLER", [colors2("أبيض", "بيج", "رمادي"), {"kind": "SIZE", "scale": "BED", "values": ["فردي", "كوين", "كينج"]}], True, 7),
            ("مخدة طبية", "Nile Linen", "pillows-quilts", "", 65000, None, (200, 190, 210), "LIMITED", [], False, 3),
        ]
        for i, (n, brand, slug, aud, price, cmp_, col, badge, opts, feat, stock) in enumerate(items):
            f = up(pic(800, 1000, col, tuple(min(255, x + 70) for x in col), ["circle", "rect", "tri"][i % 3]), "PRODUCT_IMAGE")
            options = []
            for o in opts:
                options.append({"name": "اللون", "attribute": "COLOR", "values": o["values"]} if o["kind"] == "COLOR" else {"name": "المقاس", "attribute": "SIZE", **({"sizeScale": o["scale"]} if o.get("scale") else {}), "values": o["values"]})
            combos = [dict()]
            for o in options:
                combos = [{**c, o["name"]: v} for c in combos for v in o["values"]]
            body = {"name": n, "brand": brand, "taxonomySlug": slug, "audience": aud or None, "description": f"{n} بجودة عالية وخامات مريحة.\nمناسب للاستخدام اليومي، متوفر بأكثر من خيار.", "shortDescription": "جودة ممتازة وسعر مناسب",
                    "options": options,
                    "variants": [{"sku": f"DEMO{i:02d}-{j}", "priceMinor": price, "compareAtPriceMinor": cmp_, "optionValues": cv, "stock": [{"branchId": branch, "quantity": stock}]} for j, cv in enumerate(combos)],
                    "media": [{"fileId": f, "altText": n}]}
            pid = api("POST", "/store/products", body)["id"]
            api("PATCH", f"/store/products/{pid}/extras", {"badge": badge, "isFeatured": feat, "tags": ["ديمو", brand], "specs": [{"k": "الخامة", "v": "قطن ١٠٠٪"}, {"k": "بلد الصنع", "v": "مصر"}], "sizeGuide": "S: 36-38 · M: 40-42 · L: 44-46" if any(o["name"] == "المقاس" and o.get("sizeScale") in (None, "APPAREL") for o in options) else ""})
    if "profile" in parts:
        api("POST", "/store/banners", {"imageFileId": up(pic(1600, 640, (180, 83, 42), (233, 162, 59)), "PRODUCT_IMAGE"), "title": "تخفيضات الموسم", "subtitle": "خصم حتى ٣٠٪ على تشكيلة مختارة", "linkUrl": "/products?onSale=true"})
    if "delivery" in parts:
        api("POST", "/store/shipping-methods", {"type": "ZONES", "name": "توصيل حسب المحافظة", "feeMinor": 0})
        api("POST", "/store/shipping-zones", {"name": "القاهرة الكبرى", "governorateCodes": ["CAI", "GIZ", "QLY"], "feeMinor": 5000, "codFeeMinor": 1000, "etaMinDays": 1, "etaMaxDays": 2, "freeAboveMinor": 150000})
        api("POST", "/store/shipping-zones", {"name": "الإسكندرية والدلتا", "governorateCodes": ["ALX", "DKH", "GHR", "SHR", "MNF", "BHR", "KFS", "DMT"], "feeMinor": 7500, "etaMinDays": 2, "etaMaxDays": 4, "freeAboveMinor": 150000})
        api("POST", "/store/shipping-zones", {"name": "الصعيد", "governorateCodes": ["FYM", "BNS", "MNY", "AST", "SHG", "QNA", "LXR", "ASN"], "feeMinor": 10000, "etaMinDays": 3, "etaMaxDays": 6})
        try:
            api("POST", "/store/coupons", {"code": "WELCOME10", "discountType": "PERCENT", "value": 10, "minOrderMinor": 50000})
        except SystemExit:
            print("coupon skipped (plan does not include coupons)")
    print(f"seeded parts {sorted(parts)} at {shop_base}")


if a.stage in ("onboard", "all"):
    onboard()
if a.stage in ("seed", "all"):
    seed()
