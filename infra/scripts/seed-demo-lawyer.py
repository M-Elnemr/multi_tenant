#!/usr/bin/env python3
"""Creates (or fills) the demo LAWYER «المستشار أحمد الحسيني» with a complete website and a working office (clients, cases, hearings, fees).

  python3 infra/scripts/seed-demo-lawyer.py --base https://elmanassa.shop --slug elhosseiny --phone 01001230000 --password '<choose one>' --stage onboard
  (add the host to the HTTPS certificate: infra/scripts/test-https-add-hosts.sh elmanassa.shop elhosseiny.elmanassa.shop)
  python3 infra/scripts/seed-demo-lawyer.py --base https://elmanassa.shop --slug elhosseiny --phone 01001230000 --password '<same>' --stage seed

Needs Pillow (pip install pillow) for the portrait and cover pictures. The password is only passed on the command line / env, never stored in the repo.
"""
import argparse, io, json, os, sys, urllib.error, urllib.request
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from PIL import Image, ImageDraw

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="https://elmanassa.shop")
ap.add_argument("--slug", default="elhosseiny")
ap.add_argument("--phone", required=True)
ap.add_argument("--password", default=os.environ.get("DEMO_PASSWORD"))
ap.add_argument("--connect", help="send requests here (e.g. http://localhost:8080) with the lawyer's Host header; for local runs")
ap.add_argument("--stage", choices=["onboard", "seed", "all"], default="all")
a = ap.parse_args()
if not a.password:
    sys.exit("pass --password or set DEMO_PASSWORD")
root = urlparse(a.base)
site = f"{root.scheme}://{a.slug}.{root.netloc}"
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
        sys.exit(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def onboard():
    r = call("POST", a.base, "/api/v1/onboarding/tenants", {"type": "LAWYER", "name": "المستشار أحمد الحسيني", "slug": a.slug, "ownerFirstName": "أحمد", "ownerLastName": "الحسيني",
                                                            "phone": PHONE, "password": a.password, "categories": ["criminal", "family", "commercial", "civil", "cassation"]})
    print("created", r["host"])


NAVY, NAVY2, GOLD = (11, 29, 58), (30, 58, 95), (201, 162, 39)


def grad(w, h, c1, c2):
    im = Image.new("RGB", (w, h), c1)
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3)))
    return im, d


def scales(d, cx, cy, s, color, width):
    """A scales-of-justice line drawing."""
    d.line([(cx, cy - s), (cx, cy + s)], fill=color, width=width)
    d.line([(cx - s * .55, cy + s), (cx + s * .55, cy + s)], fill=color, width=width)
    d.line([(cx - s * .9, cy - s * .6), (cx + s * .9, cy - s * .6)], fill=color, width=width)
    d.ellipse([cx - s * .08, cy - s * 1.08, cx + s * .08, cy - s * .92], outline=color, width=width)
    for sx in (-1, 1):
        x = cx + sx * s * .9
        d.line([(x, cy - s * .6), (x - s * .3, cy + s * .05)], fill=color, width=width)
        d.line([(x, cy - s * .6), (x + s * .3, cy + s * .05)], fill=color, width=width)
        d.chord([x - s * .32, cy - s * .15, x + s * .32, cy + s * .25], 0, 180, outline=color, width=width)


def png(im):
    b = io.BytesIO()
    im.save(b, "PNG")
    return b.getvalue()


def portrait():
    """An elegant portrait card (navy, gold frame, emblem) until the counsellor uploads a real photo."""
    w, h = 800, 1000
    im, d = grad(w, h, NAVY2, NAVY)
    d.rectangle([40, 40, w - 40, h - 40], outline=GOLD, width=4)
    d.rectangle([58, 58, w - 58, h - 58], outline=(150, 120, 40), width=1)
    scales(d, w / 2, h / 2 - 40, 200, GOLD, 9)
    d.line([(w / 2 - 160, h - 190), (w / 2 + 160, h - 190)], fill=GOLD, width=3)
    return png(im)


def cover():
    im, d = grad(1600, 700, NAVY, NAVY2)
    for i in range(0, 1600, 80):
        d.line([(i, 700), (i + 300, 0)], fill=(18, 40, 75), width=2)
    scales(d, 1250, 360, 230, (60, 80, 110), 7)
    return png(im)


def logo():
    im, d = grad(256, 256, NAVY2, NAVY)
    scales(d, 128, 136, 70, GOLD, 6)
    return png(im)


def seed():
    tok = call("POST", site, "/api/v1/auth/login", {"identifier": PHONE, "password": a.password})["accessToken"]

    def api(m, path, body=None):
        return call(m, site, "/api/v1" + path, body, tok)

    def up(data, cat="LOGO"):
        p = api("POST", "/files/presign", {"filename": "x.png", "contentType": "image/png", "size": len(data), "category": cat})
        call("PUT", site, p["uploadUrl"], raw=data, ctype="image/png")
        return p["fileId"]

    logo_id = up(logo())
    api("PATCH", "/tenant/branding", {"primaryColor": "#0b1d3a", "secondaryColor": "#c9a227", "logoFileId": logo_id, "locale": "ar"})
    photo = f"/api/bff/files/{up(portrait())}/content?variant=medium"
    cover_url = f"/api/bff/files/{up(cover())}/content?variant=original"
    api("PATCH", "/lawyer/profile", {
        "displayName": "أحمد الحسيني", "title": "المستشار", "template": "classic",
        "headline": "محامٍ بالنقض والإدارية العليا — أكثر من ٢٢ عامًا في الدفاع عن حقوقك بثقة وسرية تامة",
        "bio": "المستشار أحمد الحسيني محامٍ بالنقض والإدارية العليا، بدأ مسيرته عام ٢٠٠٣ وتولّى خلالها مئات القضايا الجنائية والمدنية والتجارية وقضايا الأحوال الشخصية أمام مختلف درجات التقاضي.\n\nيؤمن بأن كل قضية تبدأ بالاستماع الجيد: يدرس ملفك بعناية، يشرح لك موقفك القانوني بوضوح وبدون مصطلحات معقدة، ثم يضع معك خطة واضحة بخطوات ومواعيد محددة. يعمل معه فريق من المحامين والباحثين القانونيين لضمان متابعة دقيقة لكل جلسة وكل ميعاد.",
        "photoUrl": photo, "coverUrl": cover_url, "barLevel": "مقبول أمام محكمة النقض والإدارية العليا", "barNumber": "١٢٣٤٥٦", "yearsExperience": 22,
        "languages": ["العربية", "الإنجليزية"],
        "practiceAreas": [
            {"key": "criminal", "title": "القضايا الجنائية", "icon": "🛡️", "description": "الدفاع في الجنح والجنايات من التحقيق في النيابة حتى الحكم والطعن عليه."},
            {"key": "family", "title": "الأحوال الشخصية", "icon": "👪", "description": "الطلاق والخلع والنفقة والحضانة والرؤية — بحلول تحفظ الكرامة وتحمي الأطفال."},
            {"key": "commercial", "title": "الشركات والقانون التجاري", "icon": "🏢", "description": "تأسيس الشركات، العقود التجارية، الشيكات، والمنازعات بين الشركاء."},
            {"key": "civil", "title": "القضايا المدنية والتعويضات", "icon": "⚖️", "description": "دعاوى التعويض وفسخ العقود والمطالبات المالية وصحة التوقيع."},
            {"key": "real_estate", "title": "العقارات والإيجارات", "icon": "🏠", "description": "صحة ونفاذ العقود، الطرد، الإيجار القديم والجديد، ونزاعات الملكية."},
            {"key": "cassation", "title": "الطعن بالنقض", "icon": "🔨", "description": "إعداد مذكرات الطعن أمام محكمة النقض بخبرة تزيد على عشرين عامًا."},
        ],
        "achievements": [{"value": "٢٢+", "label": "عامًا من الخبرة"}, {"value": "٨٥٠+", "label": "قضية تم التعامل معها"}, {"value": "٩٤٪", "label": "رضا الموكلين"}],
        "education": [
            {"title": "ليسانس الحقوق", "place": "جامعة القاهرة", "year": "2002"},
            {"title": "دبلوم القانون الجنائي", "place": "جامعة عين شمس", "year": "2005"},
            {"title": "دبلوم القانون التجاري والبحري", "place": "جامعة القاهرة", "year": "2008"},
        ],
        "testimonials": [
            {"name": "م. خالد عبد الرحمن", "rating": 5, "text": "تعامل راقٍ ووضوح في كل خطوة. حصلت على حكم البراءة بعد قضية استمرت عامين، وكان المستشار يتابع معي أولًا بأول."},
            {"name": "أ. منى السيد", "rating": 5, "text": "في قضية الحضانة كان حريصًا على مصلحة أولادي قبل أي شيء. شكرًا على الإنسانية والاحترافية."},
            {"name": "شركة النيل للتوريدات", "rating": 5, "text": "يمثلنا في عقودنا ونزاعاتنا التجارية منذ ٨ سنوات. سرعة استجابة ودقة لا مثيل لهما."},
        ],
        "faq": [
            {"q": "كيف أحجز استشارة؟", "a": "اختر الخدمة والموعد المناسب من صفحة الحجز واكتب اسمك ورقم موبايلك، وسيصلك تأكيد الموعد. يمكنك متابعة حجزك بالكود."},
            {"q": "هل الاستشارة سرية؟", "a": "نعم تمامًا. كل ما تشاركه معنا يخضع لسرية المهنة، ولا يطّلع عليه أحد خارج المكتب."},
            {"q": "ماذا أحضر معي في الموعد؟", "a": "صورة بطاقة الرقم القومي، وأي عقود أو أحكام أو مستندات متعلقة بالموضوع."},
            {"q": "كيف تُحسب الأتعاب؟", "a": "تُحدد الأتعاب بعد دراسة القضية حسب طبيعتها ودرجة التقاضي، ويتم الاتفاق عليها كتابيًا قبل البدء."},
        ],
        "officeAddress": "١٢ شارع عبد الخالق ثروت، وسط البلد", "officeCity": "القاهرة", "mapUrl": "https://maps.google.com/?q=Abdel+Khalek+Tharwat+Cairo",
        "phone": PHONE, "whatsapp": PHONE, "email": "office@elhosseiny-law.com",
        "social": {"facebook": "https://facebook.com/", "linkedin": "https://linkedin.com/"},
        "slotMinutes": 30, "minNoticeHours": 3, "maxDaysAhead": 30, "reminderDaysBefore": 1, "dailyDigest": True, "bookingEnabled": True,
    })
    # services (replace the starter ones)
    for s in api("GET", "/lawyer/services"):
        api("DELETE", f"/lawyer/services/{s['id']}")
    for i, (n, d, dur, price, mode) in enumerate([
        ("استشارة قانونية بالمكتب", "جلسة ٣٠ دقيقة لدراسة موقفك القانوني ووضع خطة واضحة.", 30, 50000, "OFFICE"),
        ("استشارة أونلاين", "مكالمة فيديو من أي مكان في مصر أو خارجها.", 30, 40000, "ONLINE"),
        ("استشارة تليفونية سريعة", "إجابة سريعة على سؤال محدد.", 15, 20000, "PHONE"),
        ("مراجعة وصياغة عقد", "مراجعة العقد وبيان المخاطر وصياغة التعديلات المقترحة.", 60, 150000, "OFFICE"),
    ]):
        api("POST", "/lawyer/services", {"name": n, "description": d, "durationMinutes": dur, "priceMinor": price, "mode": mode, "sortOrder": i})
    api("PUT", "/lawyer/hours", {"hours": [{"weekday": w, "start": "11:00", "end": "15:00"} for w in (6, 7, 1, 2, 3)] + [{"weekday": w, "start": "18:00", "end": "21:00"} for w in (6, 7, 1, 2, 3, 4)]})

    # a working office: clients, cases, hearings, actions, payments
    now = datetime.now(timezone.utc)
    iso = lambda d: d.replace(microsecond=0).isoformat().replace("+00:00", "Z")
    c1 = api("POST", "/lawyer/clients", {"name": "شركة النيل للتوريدات", "phone": "01011110001", "kind": "COMPANY", "email": "legal@nile-supply.example", "tags": ["شركات"]})
    c2 = api("POST", "/lawyer/clients", {"name": "منى السيد إبراهيم", "phone": "01011110002", "nationalId": "28501010100000"})
    c3 = api("POST", "/lawyer/clients", {"name": "خالد عبد الرحمن", "phone": "01011110003"})
    k1 = api("POST", "/lawyer/cases", {"title": "نزاع توريد مع شركة الدلتا", "caseType": "COMMERCIAL", "court": "المحكمة الاقتصادية بالقاهرة", "circuit": "الدائرة الثالثة", "courtCaseNo": "1452 / 2026",
                                      "opponent": "شركة الدلتا للمقاولات", "clientRole": "PLAINTIFF", "agreedFeeMinor": 2500000, "clientIds": [c1["id"]], "description": "مطالبة بقيمة توريدات غير مسددة وتعويض عن التأخير."})
    k2 = api("POST", "/lawyer/cases", {"title": "دعوى حضانة ونفقة", "caseType": "FAMILY", "court": "محكمة الأسرة بمدينة نصر", "courtCaseNo": "873 / 2026", "clientRole": "PLAINTIFF", "agreedFeeMinor": 1200000, "clientIds": [c2["id"]]})
    k3 = api("POST", "/lawyer/cases", {"title": "جنحة شيك بدون رصيد", "caseType": "CRIMINAL", "court": "محكمة جنح قصر النيل", "status": "CLOSED", "outcome": "WON", "agreedFeeMinor": 800000, "clientIds": [c3["id"]], "clientRole": "ACCUSED"})
    k4 = api("POST", "/lawyer/cases", {"title": "دراسة عقد شراكة", "caseType": "CONTRACTS", "status": "UNDER_STUDY", "agreedFeeMinor": 300000, "clientIds": [c1["id"]]})
    for k, steps in [(k1, [(2, "HEARING", "الجلسة الثانية — تقديم مستندات التوريد", "المحكمة الاقتصادية — قاعة ٤"), (16, "DEADLINE", "آخر ميعاد لتقديم المذكرة الختامية", "")]),
                     (k2, [(1, "HEARING", "جلسة سماع الشهود", "محكمة الأسرة بمدينة نصر"), (9, "MEETING", "اجتماع مع الموكلة لمراجعة تقرير الأخصائي", "المكتب")])]:
        for days, kind, desc, loc in steps:
            api("POST", f"/lawyer/cases/{k['id']}/steps", {"kind": kind, "startsAt": iso((now + timedelta(days=days)).replace(hour=8, minute=30)), "description": desc, "location": loc})
    api("POST", f"/lawyer/cases/{k1['id']}/actions", {"title": "إعداد صحيفة الدعوى وإيداعها", "feeMinor": 300000, "notes": "تم الإيداع وقيد الدعوى."})
    api("POST", f"/lawyer/cases/{k1['id']}/actions", {"title": "حضور الجلسة الأولى", "feeMinor": 150000})
    api("POST", f"/lawyer/cases/{k2['id']}/actions", {"title": "استشارة وإعداد المستندات", "feeMinor": 100000})
    api("POST", f"/lawyer/cases/{k3['id']}/actions", {"title": "حضور جلسة الحكم — براءة", "notes": "صدر الحكم ببراءة الموكل."})
    api("POST", f"/lawyer/cases/{k1['id']}/payments", {"amountMinor": 1500000, "method": "TRANSFER", "clientId": c1["id"], "note": "دفعة أولى"})
    api("POST", f"/lawyer/cases/{k2['id']}/payments", {"amountMinor": 600000, "method": "INSTAPAY", "clientId": c2["id"]})
    api("POST", f"/lawyer/cases/{k3['id']}/payments", {"amountMinor": 800000, "method": "CASH", "clientId": c3["id"]})
    api("POST", "/lawyer/schedule/notes", {"startsAt": iso((now + timedelta(days=1)).replace(hour=6, minute=0)), "title": "تجهيز مذكرة الدفاع في قضية الدلتا", "caseId": k1["id"]})
    _ = k4
    print("seeded", site)


if a.stage in ("onboard", "all"):
    onboard()
if a.stage in ("seed", "all"):
    seed()
