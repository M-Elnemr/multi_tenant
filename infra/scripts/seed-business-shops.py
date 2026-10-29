#!/usr/bin/env python3
"""Creates real, public demo shops with a big catalog: a take-away restaurant (beit-elshawarma, ~60 dishes), a fashion shop (nile-style, ~140 products
with colour and size variants) and an electronics shop (tech-zone, ~80 devices and accessories whose storage, RAM, size and installation change the price).
Data: business_shops_data.py.

  The fashion and electronics pictures are drawn (garments.py, devices.py: one per colour, no photos of people, no brands); the restaurant uses photos:
  1) photos (no login, restaurant): free photos from Wikimedia Commons (CC0 / public domain / CC BY / CC BY-SA only), cropped square;
     the author and licence go in each photo's alt text. Review the contact sheets, then re-run with --redo key,... if needed.
       python3 infra/scripts/seed-business-shops.py --stage photos --cache ~/shop-photos
       python3 infra/scripts/seed-business-shops.py --stage sheet --cache ~/shop-photos
  2) onboard + seed (the owner phone and password are only passed on the command line / env, never stored):
       SEED_PASSWORD='<owner password>' python3 infra/scripts/seed-business-shops.py --stage all --phone 010... --cache ~/shop-photos
     An existing owner phone with its password adds the shops to that account (one business-app login for all of them).
     Only the electronics shop:  ... --stage all --shops electronics --phone 010...
     Add the shop hosts to the HTTPS certificate afterwards: infra/scripts/test-https-add-hosts.sh elmanassa.shop beit-elshawarma.elmanassa.shop nile-style.elmanassa.shop tech-zone.elmanassa.shop

Needs Pillow. Re-running the seed skips products that already exist (by name), so a failed run can simply be repeated.
"""
import argparse, io, json, os, random, re, sys, time, urllib.error, urllib.parse, urllib.request
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import business_shops_data as D
import garments as G
import devices as V
from PIL import Image, ImageDraw, ImageFont

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="https://elmanassa.shop", help="platform site (root domain)")
ap.add_argument("--connect", help="send requests to this URL (e.g. http://localhost:8080) with the shop's Host header; for local dry runs")
ap.add_argument("--stage", choices=["photos", "sheet", "onboard", "seed", "all"], required=True)
ap.add_argument("--shops", default="restaurant,fashion")
ap.add_argument("--phone")
ap.add_argument("--password", default=os.environ.get("SEED_PASSWORD"))
ap.add_argument("--cache", default=os.path.expanduser("~/shop-photos"), help="folder for the downloaded photos and the manifest")
ap.add_argument("--redo", default="", help="photos stage: comma list of product keys to pick again (the next candidate)")
ap.add_argument("--limit", type=int, default=0, help="seed only the first N products per shop (dry runs)")
a = ap.parse_args()
os.makedirs(a.cache, exist_ok=True)
UA = "ElmanassaSeed/1.0 (https://elmanassa.shop; shop demo photos)"
SHOPS = {
    "restaurant": {"slug": "beit-elshawarma", "name": D.RESTAURANT_PROFILE["storeName"], "categories": ["food"], "items": D.RESTAURANT, "colors": ("#b4321e", "#f2a33a"), "initials": "BS"},
    "fashion": {"slug": "nile-style", "name": D.FASHION_PROFILE["storeName"], "categories": ["fashion_men", "fashion_women", "fashion_kids", "shoes_bags"], "items": D.FASHION, "colors": ("#1f3a5f", "#c8a165"), "initials": "NS"},
    "electronics": {"slug": "tech-zone", "name": D.ELECTRONICS_PROFILE["storeName"], "categories": ["mobiles", "electronics", "computers"], "items": D.ELECTRONICS, "colors": ("#0f2a44", "#18a0c8"), "initials": "TZ"},
}
chosen = [s.strip() for s in a.shops.split(",") if s.strip()]
PHOTOS = os.path.join(a.cache, "photos.json")


def load(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        return default


# ---------------------------------------------------------------- photos
OK_LICENSE = re.compile(r"^(CC0|Public domain|PD.*|CC BY(-SA)? [0-9.]+)$", re.I)
BAD_TITLE = re.compile(r"logo|map|diagram|drawing|illustration|museum|poster|painting|statue|icon|svg|menu|sign|advert|screenshot|collage|"
                       r"kfc|mcdonald|burger king|pepsi|coca|sierra mist|starbucks|subway|wendy|nike|adidas|puma|zara|gucci|louis vuitton|chanel|prada|"
                       r"packag|carton|wrapper|store|shop|market|mannequin", re.I)


def commons(query, n=20):
    q = urllib.parse.urlencode({"action": "query", "generator": "search", "gsrsearch": f"{query} filetype:bitmap", "gsrnamespace": 6, "gsrlimit": n,
                                "prop": "imageinfo", "iiprop": "url|extmetadata|size|mime", "iiurlwidth": 1200, "format": "json"})
    req = urllib.request.Request("https://commons.wikimedia.org/w/api.php?" + q, headers={"User-Agent": UA})
    for i in range(4):   # Commons now and then answers without results (throttling): wait and ask again
        with urllib.request.urlopen(req, timeout=60) as r:
            d = json.load(r)
        if "query" in d or "error" not in d and "continue" not in d:
            break
        time.sleep(4 * (i + 1))
    pages = d.get("query", {}).get("pages", {})
    out = []
    for p in sorted(pages.values(), key=lambda p: p.get("index", 0)):
        ii = (p.get("imageinfo") or [{}])[0]
        m = ii.get("extmetadata", {})
        lic = m.get("LicenseShortName", {}).get("value", "")
        if not OK_LICENSE.match(lic.strip()) or BAD_TITLE.search(p["title"]) or ii.get("mime") not in ("image/jpeg", "image/png"):
            continue
        if min(ii.get("width", 0), ii.get("height", 0)) < 500:
            continue
        artist = re.sub(r"<[^>]+>", "", m.get("Artist", {}).get("value", "")).strip() or "unknown"
        out.append({"title": p["title"], "url": ii.get("thumburl") or ii["url"], "page": ii.get("descriptionurl"), "license": lic, "author": artist[:80]})
    return out


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for i in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or i == 3:
                raise
            time.sleep(5 * (i + 1))


def square(raw, size=1000):
    im = Image.open(io.BytesIO(raw)).convert("RGB")
    w, h = im.size
    s = min(w, h)
    im = im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s)).resize((size, size), Image.LANCZOS)
    b = io.BytesIO()
    im.save(b, "JPEG", quality=82, optimize=True)
    return b.getvalue()


def photos():
    got = load(PHOTOS, {})
    redo = {k.strip() for k in a.redo.split(",") if k.strip()}
    used = {v["title"] for v in got.values()}
    for shop in [x for x in chosen if x == "restaurant"]:
        for it in SHOPS[shop]["items"]:
            key, query = it[0], it[8]
            if key in got and key not in redo:
                continue
            skip = got.get(key, {}).get("skip", []) + ([got[key]["title"]] if key in got else [])
            cands = []
            for q in query.split("|"):   # fallback searches, best first
                try:
                    cands = [c for c in commons(q) if c["title"] not in used and c["title"] not in skip]
                except Exception as e:
                    print("search failed", key, q, e)
                if cands:
                    break
            if not cands:
                print("NO PHOTO", key, query); continue
            c = cands[0]
            try:
                raw = fetch(c["url"])
                with open(os.path.join(a.cache, key + ".jpg"), "wb") as f:
                    f.write(square(raw))
                with open(os.path.join(a.cache, key + "-orig.jpg"), "wb") as f:
                    f.write(raw)
            except Exception as e:
                print("download failed", key, e); continue
            got[key] = {**c, "skip": skip}
            used.add(c["title"])
            with open(PHOTOS, "w") as f:
                json.dump(got, f, ensure_ascii=False, indent=1)
            print("ok", key, "|", c["license"], "|", c["title"][:70])
            time.sleep(0.5)


def sheet():
    got = load(PHOTOS, {})
    keys = [it[0] for shop in chosen for it in SHOPS[shop]["items"] if os.path.exists(os.path.join(a.cache, it[0] + ".jpg"))]
    font = None
    for p in ("/System/Library/Fonts/Supplemental/Arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            font = ImageFont.truetype(p, 16); break
    for n in range(0, len(keys), 30):
        page = Image.new("RGB", (6 * 220, 5 * 240), "white")
        d = ImageDraw.Draw(page)
        for i, k in enumerate(keys[n:n + 30]):
            im = Image.open(os.path.join(a.cache, k + ".jpg")).resize((210, 210))
            x, y = (i % 6) * 220 + 5, (i // 6) * 240 + 5
            page.paste(im, (x, y))
            d.text((x, y + 212), k, fill="black", font=font)
        out = os.path.join(a.cache, f"sheet-{n // 30 + 1}.jpg")
        page.save(out, "JPEG", quality=80)
        print(out)
    print(len(keys), "photos;", len(got), "in the manifest")


# ---------------------------------------------------------------- API
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
    for i in range(6):
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                t = r.read().decode()
                return json.loads(t) if t and r.headers.get("content-type", "").startswith("application/json") else t
        except urllib.error.HTTPError as e:
            msg = e.read().decode()[:400]
            if e.code == 429 and i < 5:
                time.sleep(30); continue
            raise RuntimeError(f"{method} {path} -> {e.code}: {msg}")


def phone():
    if not a.phone or not a.password:
        sys.exit("pass --phone and --password (or SEED_PASSWORD)")
    return a.phone if a.phone.startswith("+") else "+2" + a.phone.lstrip("+")


def shop_base(slug):
    u = urlparse(a.base)
    return f"{u.scheme}://{slug}.{u.netloc}"


def onboard(shop):
    s = SHOPS[shop]
    try:
        r = call("POST", a.base, "/api/v1/onboarding/tenants", {"type": "STORE", "name": s["name"], "slug": s["slug"], "ownerFirstName": "صاحب المحل", "phone": phone(), "password": a.password, "locale": "ar", "categories": s["categories"]})
        print("created", r.get("host"))
    except RuntimeError as e:
        if "SLUG_TAKEN" not in str(e):
            raise
        print(s["slug"], "already exists; seeding it")


def logo(initials, c1, c2):
    im = Image.new("RGB", (512, 512), c1)
    d = ImageDraw.Draw(im)
    d.ellipse([40, 40, 472, 472], fill=c2)
    font = None
    for p in ("/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(p):
            font = ImageFont.truetype(p, 190); break
    d.text((256, 256), initials, fill=c1, font=font, anchor="mm") if font else d.text((220, 240), initials, fill=c1)
    b = io.BytesIO()
    im.save(b, "PNG")
    return b.getvalue()


def wide(key, w=1600, h=640):
    p = os.path.join(a.cache, key + "-orig.jpg")
    im = Image.open(p).convert("RGB")
    iw, ih = im.size
    scale = max(w / iw, h / ih)
    im = im.resize((int(iw * scale) + 1, int(ih * scale) + 1), Image.LANCZOS)
    iw, ih = im.size
    im = im.crop(((iw - w) // 2, (ih - h) // 2, (iw - w) // 2 + w, (ih - h) // 2 + h))
    b = io.BytesIO()
    im.save(b, "JPEG", quality=82)
    return b.getvalue()


def drawn_banner(keys, w=1600, h=640, bg=(31, 58, 95)):
    im = Image.new("RGB", (w, h), bg)
    items = {it[0]: it for it in D.FASHION}
    size = int(h * .78)
    for i, k in enumerate(keys):
        it = items[k]
        pic = Image.open(io.BytesIO(G.picture(k, it[2], it[4][0]))).resize((size, size))
        im.paste(pic, (int(w * .04) + i * (size + int(w * .02)), (h - size) // 2))
    b = io.BytesIO()
    im.save(b, "JPEG", quality=86)
    return b.getvalue()


def device_banner(keys, w=1600, h=640, bg=(15, 42, 68)):
    im = Image.new("RGB", (w, h), bg)
    items = {it[0]: it for it in D.ELECTRONICS}
    size = int(h * .78)
    for i, k in enumerate(keys):
        it = items[k]
        pic = Image.open(io.BytesIO(V.picture(k, it[2], (it[4] or [None])[0]))).resize((size, size))
        im.paste(pic, (int(w * .03) + i * (size + int(w * .015)), (h - size) // 2))
    b = io.BytesIO()
    im.save(b, "JPEG", quality=86)
    return b.getvalue()


def credit(meta):
    return f"صورة: {meta['author']} · {meta['license']} · Wikimedia Commons"[:250]


def seed(shop):
    s = SHOPS[shop]
    base = shop_base(s["slug"])
    tok = [None]

    def api(m, path, body=None):   # logs in again when the access token runs out during a long run
        for attempt in (0, 1):
            if tok[0] is None:
                tok[0] = call("POST", base, "/api/v1/auth/login", {"identifier": phone(), "password": a.password})["accessToken"]
            try:
                return call(m, base, "/api/v1" + path, body, tok[0])
            except RuntimeError as e:
                if " -> 401:" not in str(e) or attempt:
                    raise
                tok[0] = None
    got = load(PHOTOS, {})
    manifest_path = os.path.join(a.cache, f"manifest-{s['slug']}.json")
    manifest = load(manifest_path, {"products": {}})

    last = [0.0]

    def up(data, cat="PRODUCT_IMAGE", ctype="image/jpeg"):
        time.sleep(max(0.0, last[0] + 3.1 - time.time()))   # uploads are limited to 20 a minute per user
        last[0] = time.time()
        p = api("POST", "/files/presign", {"filename": "photo.jpg" if ctype == "image/jpeg" else "logo.png", "contentType": ctype, "size": len(data), "category": cat})
        call("PUT", base, p["uploadUrl"], raw=data, ctype=ctype)
        return p["fileId"]

    restaurant = shop == "restaurant"
    electronics = shop == "electronics"
    prof = D.RESTAURANT_PROFILE if restaurant else D.ELECTRONICS_PROFILE if electronics else D.FASHION_PROFILE
    if restaurant:
        hours = {d: {"open": "12:00", "close": "02:00", "closed": False} for d in ["sat", "sun", "mon", "tue", "wed", "thu", "fri"]}
    elif electronics:
        hours = {d: {"open": "15:00" if d == "fri" else "11:00", "close": "23:00", "closed": False} for d in ["sat", "sun", "mon", "tue", "wed", "thu", "fri"]}
    else:
        hours = {d: {"open": "14:00" if d == "fri" else "10:00", "close": "23:00", "closed": False} for d in ["sat", "sun", "mon", "tue", "wed", "thu", "fri"]}
    cover_key = "gr-mix" if restaurant else "w-coat"
    if not manifest.get("profile"):
        api("PATCH", "/tenant/branding", {"primaryColor": s["colors"][0], "secondaryColor": s["colors"][1], "logoFileId": up(logo(s["initials"], *s["colors"]), "LOGO", "image/png"), "locale": "ar"})
        body = {**prof, "workingHours": hours}
        if electronics:
            body["coverFileId"] = up(device_banner(["ph-x12-pro", "lp-book14", "tv-4k", "au-buds-pro"], 1600, 700))
        elif not restaurant:
            body["coverFileId"] = up(drawn_banner(["w-coat", "m-puffer", "w-satin-evening", "b-leather-hand"], 1600, 700))
        elif os.path.exists(os.path.join(a.cache, cover_key + "-orig.jpg")):
            body["coverFileId"] = up(wide(cover_key, 1600, 700))
        api("PATCH", "/store/profile", body)
        branch = {"name": "فرع مدينة نصر", "code": "NC", "phone": prof["supportPhone"], "address": "١٥ شارع عباس العقاد", "city": "القاهرة", "governorateCode": "CAI", "area": "مدينة نصر", "isPickup": True, "workingHours": hours} if restaurant else \
                 {"name": "فرع المهندسين", "code": "MOH", "phone": prof["supportPhone"], "address": "٢٢ شارع شهاب", "city": "الجيزة", "governorateCode": "GIZ", "area": "المهندسين", "isPickup": True, "workingHours": hours} if electronics else \
                 {"name": "فرع مول العرب", "code": "MOA", "phone": prof["supportPhone"], "address": "مول العرب، الدور الأول", "city": "٦ أكتوبر", "governorateCode": "GIZ", "area": "٦ أكتوبر", "isPickup": True, "workingHours": hours}
        existing = api("GET", "/store/branches")
        if existing:
            api("PATCH", f"/store/branches/{existing[0]['id']}", branch)
        else:
            api("POST", "/store/branches", branch)
        api("POST", "/store/shipping-methods", {"type": "ZONES", "name": "توصيل" if restaurant else "شحن حسب المحافظة", "feeMinor": 0})
        if restaurant:
            api("POST", "/store/shipping-zones", {"name": "القاهرة والجيزة", "governorateCodes": ["CAI", "GIZ"], "feeMinor": 2500, "codFeeMinor": 0, "etaMinDays": 0, "etaMaxDays": 0, "freeAboveMinor": 40000})
        elif electronics:
            for name, codes, fee, emin, emax in D.ELECTRONICS_ZONES:
                api("POST", "/store/shipping-zones", {"name": name, "governorateCodes": codes, "feeMinor": fee, "codFeeMinor": 0, "etaMinDays": emin, "etaMaxDays": emax, "freeAboveMinor": 500000})
        else:
            for name, codes, fee, emin, emax in D.FASHION_ZONES:
                api("POST", "/store/shipping-zones", {"name": name, "governorateCodes": codes, "feeMinor": fee, "codFeeMinor": 1000, "etaMinDays": emin, "etaMaxDays": emax, "freeAboveMinor": 150000})
        try:
            api("POST", "/store/coupons", {"code": "SHAWARMA15", "discountType": "PERCENT", "value": 15, "minOrderMinor": 30000} if restaurant else
                {"code": "TECH5", "discountType": "PERCENT", "value": 5, "minOrderMinor": 300000} if electronics else {"code": "NILE10", "discountType": "PERCENT", "value": 10, "minOrderMinor": 100000})
        except RuntimeError as e:
            print("coupon skipped:", str(e)[:160])
        manifest["profile"] = True
        json.dump(manifest, open(manifest_path, "w"), ensure_ascii=False, indent=1)
        print("profile, branch, delivery and coupon done")

    branch_id = api("GET", "/store/branches")[0]["id"]
    have, page = {}, 1
    while True:   # products a failed earlier run created but did not record
        r = api("GET", f"/store/products?pageSize=100&page={page}")
        have.update({x["name"]: x["id"] for x in r.get("items", r.get("data", []))})
        if page * 100 >= r.get("meta", {}).get("total", 0):
            break
        page += 1
    rng = random.Random(7 if restaurant else 13 if electronics else 11)
    items = s["items"][: a.limit] if a.limit else s["items"]
    for n, it in enumerate(items):
        key = it[0]
        if key in manifest["products"]:
            continue
        if electronics:
            key, name, cat, price, colors, opts, specs, badge, cmp_, tags, brand = it
            options = ([{"name": "اللون", "attribute": "COLOR", "values": colors}] if colors else []) + [{"name": o, "values": [v for v, _ in vals]} for o, vals in opts]
            combos = [({"اللون": c} if colors else {}, 0) for c in (colors or [None])]
            for o, vals in opts:
                combos = [({**c, o: v}, p + add) for c, p in combos for v, add in vals]
            variants = []
            for j, (cv, add) in enumerate(combos):
                big = cat in ("phones", "laptops", "tvs", "tablets")
                q = 0 if rng.random() < 0.08 else rng.choice([1, 2, 3, 4, 5, 6, 8] if big else [5, 8, 10, 15, 20, 30, 50])
                if cv.get("الحالة") == "أوبن بوكس":
                    q = rng.choice([0, 1, 1, 2])
                variants.append({"sku": f"TZ-{n + 1:03d}-{j + 1:02d}", "priceMinor": price + add, "compareAtPriceMinor": (cmp_ + add) if cmp_ else None, "optionValues": cv, "stock": [{"branchId": branch_id, "quantity": q}]})
            if all(v["stock"][0]["quantity"] == 0 for v in variants):
                variants[0]["stock"][0]["quantity"] = 3
            extra_specs = [{"k": k, "v": v} for k, v in specs]
            desc = f"{name} من {brand}.\n" + (f"الألوان: {'، '.join(colors)}\n" if colors else "") + "\n".join(f"• {k}: {v}" for k, v in specs)
            short = "، ".join(v for k, v in specs[:2])
            audience, guide = None, ""
            tags = tags + (["تخفيضات"] if cmp_ else [])
        elif restaurant:
            key, name, cat, price, opts, specs, badge, cmp_, _q, tags = it
            options = [{"name": o, "values": [v for v, _ in vals]} for o, vals in opts]
            combos = [({}, 0)]
            for o, vals in opts:
                combos = [({**c, o: v}, p + add) for c, p in combos for v, add in vals]
            variants = [{"sku": f"BS-{n + 1:02d}-{j + 1}", "priceMinor": price + add, "compareAtPriceMinor": (cmp_ + add) if cmp_ else None, "optionValues": cv, "stock": [{"branchId": branch_id, "quantity": 300}]} for j, (cv, add) in enumerate(combos)]
            desc = f"{name} من بيت الشاورما، بيتعمل طازة لما تطلب.\n" + "\n".join(f"• {x['k']}: {x['v']}" for x in specs if x["v"] != "-")
            short = specs[0]["v"] if specs else name
            audience, guide, brand = None, "", "بيت الشاورما"
            extra_specs = specs
        else:
            key, name, cat, audience, colors, sizes, price, sale, _q, badge, extra = it
            options = [{"name": "اللون", "attribute": "COLOR", "values": colors}]
            if sizes:
                options.append({"name": "المقاس", "attribute": "SIZE", "sizeScale": sizes[0], "values": sizes[1]})
            if extra:
                options.append({"name": extra[0], "values": [v for v, _ in extra[1]]})
            combos = [({}, 0)]
            for o in options:
                adds = dict(extra[1]) if extra and o["name"] == extra[0] else {}
                combos = [({**c, o["name"]: v}, p + adds.get(v, 0)) for c, p in combos for v in o["values"]]
            now, before = (sale, price) if sale else (price, None)
            variants = []
            for j, (cv, add) in enumerate(combos):
                q = 0 if rng.random() < 0.08 else rng.choice([2, 3, 4, 5, 6, 8, 10, 12, 15])
                variants.append({"sku": f"NS-{n + 1:03d}-{j + 1:02d}", "priceMinor": now + add, "compareAtPriceMinor": (before + add) if before else None, "optionValues": cv, "stock": [{"branchId": branch_id, "quantity": q}]})
            if all(v["stock"][0]["quantity"] == 0 for v in variants):
                variants[0]["stock"][0]["quantity"] = 4
            extra_specs = [{"k": k, "v": v} for k, v in D.SPECS.get(cat, [])] + [{"k": "بلد الصنع", "v": "مصر"}]
            who = {"MEN": "رجالي", "WOMEN": "حريمي", "BOYS": "ولادي", "GIRLS": "بناتي", "BABY": "بيبي", "ALL": "للجميع"}[audience]
            extra_specs.insert(0, {"k": "لمين", "v": who})
            scale = sizes[0] if sizes else ""
            guide = D.SIZE_GUIDE["SHOE"] if scale == "SHOE" else D.SIZE_GUIDE["KIDS"] if scale == "KIDS" else D.SIZE_GUIDE.get(audience, "") if sizes and sizes[1] != ["فري سايز"] else ""
            desc = f"{name} من نايل ستايل.\nمتوفر بالألوان: {'، '.join(colors)}" + (f"\nالمقاسات: {'، '.join(sizes[1])}" if sizes else "") + "\n" + "\n".join(f"• {x['k']}: {x['v']}" for x in extra_specs)
            short = f"{who} · " + "، ".join(v for k, v in D.SPECS.get(cat, [])[:1])
            tags = [who] + (["تخفيضات"] if sale else []) + (["شتوي"] if cat in ("jackets-coats", "knitwear-hoodies", "boots") else [])
            brand = "نايل ستايل"
        media = []
        meta = got.get(key)
        if electronics:   # a drawn picture per colour (one picture when the product has no colours)
            media = [{"fileId": up(V.picture(key, cat, c)), "altText": f"{name} — {c}", "optionValue": c} for c in colors] if colors else [{"fileId": up(V.picture(key, cat, None)), "altText": name}]
        elif not restaurant:   # a drawn picture per colour; choosing a colour shows its picture
            media = [{"fileId": up(G.picture(key, cat, c)), "altText": f"{name} — {c}", "optionValue": c} for c in colors]
        elif meta and os.path.exists(os.path.join(a.cache, key + ".jpg")):
            media = [{"fileId": up(open(os.path.join(a.cache, key + ".jpg"), "rb").read()), "altText": f"{name} — {credit(meta)}"}]
        if name in have:
            api("PATCH", f"/store/products/{have[name]}/extras", {"badge": badge, "isFeatured": badge == "BEST_SELLER", "tags": tags, "specs": extra_specs, "sizeGuide": guide})
            manifest["products"][key] = have[name]
            json.dump(manifest, open(manifest_path, "w"), ensure_ascii=False, indent=1)
            print("kept", key)
            continue
        body = {"name": name, "brand": brand, "taxonomySlug": cat, "audience": audience, "description": desc, "shortDescription": short[:200], "options": options, "variants": variants, "media": media}
        try:
            pid = api("POST", "/store/products", body)["id"]
        except RuntimeError as e:
            print("FAILED", key, str(e)[:300]); continue
        api("PATCH", f"/store/products/{pid}/extras", {"badge": badge, "isFeatured": badge == "BEST_SELLER", "tags": tags, "specs": extra_specs, "sizeGuide": guide})
        manifest["products"][key] = pid
        json.dump(manifest, open(manifest_path, "w"), ensure_ascii=False, indent=1)
        print(f"{len(manifest['products'])}/{len(s['items'])}", key, len(variants), "variants")
    if not manifest.get("banners") and electronics:
        for ks, (t, sub), bg in zip([["ph-x12", "ph-x12-pro", "tb-tab10"], ["lp-gamer16", "au-max", "gm-pad"]], [("موبايلات نوفا الجديدة", "X12 برو بكاميرا 200 ميجا · ضمان سنة"), ("كود TECH5", "خصم ٥٪ على الطلبات من ٣٠٠٠ جنيه")], [(15, 42, 68), (40, 40, 48)]):
            api("POST", "/store/banners", {"imageFileId": up(device_banner(ks, bg=bg)), "title": t, "subtitle": sub, "linkUrl": "/products"})
        manifest["banners"] = True
        json.dump(manifest, open(manifest_path, "w"), ensure_ascii=False, indent=1)
    if not manifest.get("banners"):
        keys = ["ml-friends", "fc-bucket"] if restaurant else ["w-coat", "m-puffer"]
        texts = [("عرض وجبة الأصحاب", "٤ شاورما + ٢ بطاطس كبير + ٤ مشروب بـ ٥٢٠ بدل ٦٠٠"), ("بوكس العيلة بروستد", "١٢ قطعة بالبطاطس والكول سلو بـ ٤٨٠")] if restaurant else \
                [("تشكيلة الشتاء وصلت", "جواكت وبلوفرات وبوت · خصومات لحد ٢٠٪"), ("كود NILE10", "خصم ١٠٪ على الطلبات من ١٠٠٠ جنيه")]
        if not restaurant:
            for ks, (t, sub), bg in zip([["m-puffer", "w-coat", "s-m-boots"], ["w-mom-jeans", "m-hoodie", "s-w-sneakers"]], texts, [(31, 58, 95), (122, 82, 52)]):
                api("POST", "/store/banners", {"imageFileId": up(drawn_banner(ks, bg=bg)), "title": t, "subtitle": sub, "linkUrl": "/products?onSale=true"})
            keys = []
        for k, (t, sub) in zip(keys, texts):
            if os.path.exists(os.path.join(a.cache, k + "-orig.jpg")):
                api("POST", "/store/banners", {"imageFileId": up(wide(k)), "title": t, "subtitle": sub, "linkUrl": "/products?onSale=true"})
        manifest["banners"] = True
        json.dump(manifest, open(manifest_path, "w"), ensure_ascii=False, indent=1)
    print("done", base, len(manifest["products"]), "products")


if a.stage == "photos":
    photos()
elif a.stage == "sheet":
    sheet()
else:
    for shop in chosen:
        if a.stage in ("onboard", "all"):
            onboard(shop)
        if a.stage in ("seed", "all"):
            seed(shop)
