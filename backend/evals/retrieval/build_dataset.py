#!/usr/bin/env python3
"""Builds the FIXED retrieval dataset (backend/src/test/resources/retrieval/dataset.json): three synthetic shops of 30 products each and about 230 labelled queries.
Shop A is the catalog and the 80 queries of RetrievalBenchmarkTest (parsed from it, unchanged); shops B (electronics and home) and C (beauty, kids, food) are new, with
different vocabulary on purpose (the query normaliser's small dictionary was written for fashion words, so B and C show how far it generalises). Query generation is
deterministic (no randomness) and the output is committed: baseline and candidate runs always read the same file. Everything here is author-written synthetic data.
usage: python3 build_dataset.py"""
import json, os, re
here = os.path.dirname(os.path.abspath(__file__))
java = open(os.path.join(here, "../../src/test/java/com/platform/chat/RetrievalBenchmarkTest.java"), encoding="utf-8").read()

# ---- shop A: parsed from the existing benchmark (unchanged) ----
A_products = [{"id": int(i), "title": t, "body": b} for i, t, b in re.findall(r'new Doc\((\d+), "([^"]+)", "([^"]+)"\)', java)]
A_queries = []
for cat, text, ids in re.findall(r'add\("([^"]+)", "([^"]+)"((?:, \d+)*)\);', java):
    A_queries.append({"category": cat, "text": text, "relevant": [int(x) for x in re.findall(r"\d+", ids)]})
line = next(l for l in java.splitlines() if 'add("exact name", d.title(), d.id())' in l)
for idx in re.findall(r"CATALOG\.get\((\d+)\)", line):
    p = A_products[int(idx)]
    A_queries.insert(0, {"category": "exact name", "text": p["title"], "relevant": [p["id"]]})
assert len(A_products) == 30 and len(A_queries) == 68, (len(A_products), len(A_queries))

def shop(name, rows, hand):
    products = [{"id": i + 1, "title": t, "body": b} for i, (t, b) in enumerate(rows)]
    q = []
    def add(cat, text, *rel): q.append({"category": cat, "text": text, "relevant": list(rel)})
    pid = {p["title"]: p["id"] for p in products}
    for p in products[::3]: add("exact name", p["title"], p["id"])                                   # 10
    for p in products[1::4]:                                                                             # 8: partial, Egyptian wording
        w = p["title"].split(); add("egyptian", "عايز " + " ".join(w[:2]) if len(w) > 1 else "فيه " + w[0] + "؟", p["id"])
    def typo(t, k):
        ws = t.split(); i = max(range(len(ws)), key=lambda j: len(ws[j])); w = ws[i]
        if len(w) < 5: return None
        pos = len(w) // 2
        ws[i] = w[:pos] + w[pos] + w[pos:] if k % 2 == 0 else w[:pos] + w[pos + 1:]                      # a doubled letter, or one letter dropped
        return " ".join(ws)
    k = 0
    for p in products[2::4]:                                                                             # about 8: typos
        t = typo(p["title"], k); k += 1
        if t and t != p["title"]: add("typos", t, p["id"])
    for p in products[3::5]:                                                                             # 6: a name carried into a follow-up question
        add("follow-up (name carried)", p["title"] + " " + hand["followups"][len(q) % len(hand["followups"])], p["id"])
    for text, ids in hand["arabizi"]: add("arabizi", text, *[pid[t] for t in ids])
    for text, ids in hand["english"]: add("english", text, *[pid[t] for t in ids])
    for text, ids in hand["attribute"]: add("attribute", text, *[pid[t] for t in ids])
    for text, ids in hand["category"]: add("category (disambiguation)", text, *[pid[t] for t in ids])
    for text, ids in hand["paraphrase"]: add("paraphrase (semantic)", text, *[pid[t] for t in ids])
    for text in hand["notsold"]: add("not sold", text)
    return {"name": name, "products": products, "queries": q}

B_rows = [("موبايل سامسونج جالاكسي A55", "شاشة 6.6 بوصة بطارية 5000 ملي امبير كاميرا 50 ميجا بكسل ذاكرة 256 جيجا"), ("موبايل ايفون 15", "شاشة 6.1 بوصة كاميرا 48 ميجا شحن يو اس بي سي ذاكرة 128 جيجا"),
 ("لابتوب لينوفو ايديا باد", "معالج كور i5 رامات 16 جيجا هارد 512 SSD للدراسة والشغل"), ("لابتوب ديل انسبيرون", "معالج رايزن 7 رامات 16 جيجا شاشة 15.6 بوصة"),
 ("تابلت سامسونج تاب A9", "شاشة 8.7 بوصة بطارية 5100 ملي امبير للاطفال والمشاهدة"), ("سماعة بلوتوث ايرباودز", "سماعة اذن لاسلكية عزل ضوضاء بطارية 24 ساعة مع العلبة"),
 ("سماعة راس جيمنج", "سماعة بميكروفون صوت محيطي مريحة للعب الطويل"), ("شاحن لاسلكي سريع", "شحن لاسلكي 15 وات يدعم الايفون والاندرويد"),
 ("باور بانك 20000", "سعة 20000 ملي امبير منفذين شحن سريع"), ("كابل شحن تايب سي", "كابل مضفر طول متر ونص يتحمل شحن سريع"),
 ("تلفزيون سمارت 50 بوصة", "شاشة 4K اندرويد تي في واي فاي يدعم نتفليكس ويوتيوب"), ("ريسيفر اتش دي", "جهاز استقبال قنوات رقمية مع ريموت"),
 ("مروحة ستاند", "مروحة 16 بوصة ثلاث سرعات ارتفاع قابل للتعديل هادية"), ("مكنسة كهربائية", "شفط قوي 2000 وات كيس قابل للغسل خرطوم طويل"),
 ("خلاط كهربائي", "خلاط 1.5 لتر 600 وات بثلاث سرعات سكاكين ستانلس"), ("غلاية مياه كهربائية", "سعة 1.7 لتر فصل اوتوماتيكي سريعة الغليان"),
 ("ماكينة قهوة اسبريسو", "ضغط 15 بار بتعمل اسبريسو وكابتشينو مع رغاية"), ("مكواة بخار", "مكواة 2400 وات بخار مستمر نعل سيراميك"),
 ("سخان مياه كهربائي 50 لتر", "سخان بترموستات حماية من الصدأ تركيب سهل"), ("ثلاجة 16 قدم نوفروست", "ثلاجة بابين لا تحتاج إذابة ثلج موفرة للكهرباء"),
 ("غسالة اوتوماتيك 8 كيلو", "غسالة تحميل امامي 8 كيلو 1200 لفة"), ("ميكروويف 25 لتر", "ميكروويف مع شواية 900 وات"),
 ("شاشة كمبيوتر 24 بوصة", "شاشة فول اتش دي 75 هرتز منفذ HDMI"), ("كيبورد ميكانيكال", "كيبورد بإضاءة RGB مفاتيح زرقاء للكتابة والجيمنج"),
 ("ماوس لاسلكي", "ماوس بطارية تدوم سنة اتصال USB صغير"), ("راوتر واي فاي 6", "راوتر سرعة 3000 ميجا تغطية واسعة 4 هوائيات"),
 ("كاميرا مراقبة خارجية", "كاميرا 2 ميجا ليلي مقاومة للماء تسجيل على الموبايل"), ("سماعة بلوتوث محمولة", "سبيكر مقاوم للماء بطارية 12 ساعة صوت قوي"),
 ("ساعة ذكية رياضية", "ساعة تتابع النبض والخطوات بطارية 7 ايام مقاومة للماء"), ("بروجكتر صغير", "بروجكتر 1080p واي فاي للافلام في البيت")]
B_hand = {"followups": ["بيتشحن ازاي؟", "فيه ضمان؟", "حجمه كام؟", "بيستهلك كهربا كتير؟", "ممكن اركبه بنفسي؟", "بيدعم ايه؟"],
 "arabizi": [("3ayez mobile samsung galaxy", ["موبايل سامسونج جالاكسي A55"]), ("laptop lenovo bekam", ["لابتوب لينوفو ايديا باد"]), ("sama3a bluetooth airpods", ["سماعة بلوتوث ايرباودز"]),
   ("sha7en la sel2y sare3", ["شاحن لاسلكي سريع"]), ("tilifizion smart 50", ["تلفزيون سمارت 50 بوصة"]), ("makena qahwa espresso", ["ماكينة قهوة اسبريسو"]),
   ("thallaga 16 qadam", ["ثلاجة 16 قدم نوفروست"]), ("ghassala automatic", ["غسالة اوتوماتيك 8 كيلو"]), ("sa3a zakeya", ["ساعة ذكية رياضية"])],
 "english": [("samsung galaxy phone", ["موبايل سامسونج جالاكسي A55"]), ("iphone 15", ["موبايل ايفون 15"]), ("wireless charger", ["شاحن لاسلكي سريع"]), ("smart tv 50 inch", ["تلفزيون سمارت 50 بوصة"]),
   ("washing machine", ["غسالة اوتوماتيك 8 كيلو"]), ("gaming headset", ["سماعة راس جيمنج"]), ("mechanical keyboard", ["كيبورد ميكانيكال"]), ("security camera", ["كاميرا مراقبة خارجية"])],
 "attribute": [("البطارية كام ساعة في الايرباودز", ["سماعة بلوتوث ايرباودز"]), ("كام جيجا الرامات في لينوفو", ["لابتوب لينوفو ايديا باد"]), ("الكاميرا كام ميجا في الايفون", ["موبايل ايفون 15"]),
   ("المكنسة بتشفط بكام وات", ["مكنسة كهربائية"]), ("الغسالة كام كيلو", ["غسالة اوتوماتيك 8 كيلو"]), ("السبيكر بيتحمل الماء", ["سماعة بلوتوث محمولة"])],
 "category": [("عايز لابتوب", ["لابتوب لينوفو ايديا باد", "لابتوب ديل انسبيرون"]), ("عايز موبايل", ["موبايل سامسونج جالاكسي A55", "موبايل ايفون 15"]), ("عايز سماعة", ["سماعة بلوتوث ايرباودز", "سماعة راس جيمنج", "سماعة بلوتوث محمولة"]), ("عايز شاحن", ["شاحن لاسلكي سريع", "باور بانك 20000", "كابل شحن تايب سي"])],
 "paraphrase": [("حاجة اسمع بيها اغاني وانا ماشي", ["سماعة بلوتوث ايرباودز", "سماعة بلوتوث محمولة"]), ("عايز ابرد الاوضة", ["مروحة ستاند"]), ("حاجة اشرب بيها قهوة زي الكافيه", ["ماكينة قهوة اسبريسو"]),
   ("اشاهد افلام على الحيطة", ["بروجكتر صغير"]), ("احفظ الاكل بارد", ["ثلاجة 16 قدم نوفروست"])],
 "notsold": ["بلايستيشن 5", "سيارة", "تكييف شارب", "طابعة ليزر"]}
C_rows = [("عطر مسك رجالي", "عطر مسك ثابت يدوم 12 ساعة رائحة دافية"), ("عطر ورد حريمي", "عطر ورد ناعم برائحة الياسمين وزجاجة انيقة"), ("كريم ترطيب للوجه", "كريم بالالوفيرا لجميع انواع البشرة بدون عطر"),
 ("غسول وجه بالصبار", "غسول لطيف للبشرة الدهنية ينظف المسام"), ("شامبو للشعر الجاف", "شامبو بزيت الارجان يغذي الشعر الجاف والمتقصف"), ("بلسم للشعر", "بلسم بالكيراتين يفك التشابك ويلمع الشعر"),
 ("زيت شعر طبيعي", "زيت جوز الهند وزيت الخروع يقوي الشعر"), ("احمر شفاه مطفي", "احمر شفاه ثابت لون وردي مطفي لا يلطخ"), ("ماسكارا مقاومة للماء", "ماسكارا تطويل ورموش كثيفة تقاوم الدموع والماء"),
 ("كريم اساس سائل", "كريم اساس تغطية كاملة درجات متعددة"), ("لعبة مكعبات للاطفال", "مكعبات خشب ملونة لتنمية الذكاء من سنتين"), ("عروسة قماش ناعمة", "عروسة قطن امنة للاطفال تتغسل في الغسالة"),
 ("سيارة تحكم عن بعد", "سيارة بريموت وبطارية قابلة للشحن للاولاد"), ("كراسة تلوين مع الوان", "كراسة 40 صفحة مع علبة الوان خشب"), ("شنطة مدرسة للاطفال", "شنطة ظهر خفيفة بجيوب للكراسات مقاومة للماء"),
 ("حذاء مدرسي اسود", "جزمة مدرسية جلد صناعي نعل مريح للمشي"), ("بيجامة اطفال قطن", "بيجامة قطن 100% مريحة للنوم تتغسل بالماء الدافي"), ("حفاضات اطفال مقاس 4", "حفاضات امتصاص عالي حماية 12 ساعة"),
 ("لبن اطفال مرحلة اولى", "لبن مجفف للرضع من الشهر الاول بالحديد"), ("زجاجة رضاعة 240 مل", "زجاجة خالية من البيسفينول مع حلمة سيليكون"), ("عسل نحل طبيعي", "عسل سدر طبيعي 100% بدون اضافات برطمان 500 جرام"),
 ("تمر سكري", "تمر سكري فاخر طري علبة كيلو"), ("زيت زيتون بكر", "زيت زيتون بكر ممتاز عصرة اولى زجاجة 500 مل"), ("قهوة تركي بالهيل", "قهوة تركي محمصة مطحونة مع الهيل 250 جرام"),
 ("شاي اخضر", "شاي اخضر 25 كيس بدون سكر لحرق الدهون"), ("مكسرات مشكلة", "لوز وكاجو وبندق محمص علبة 500 جرام"), ("شوكولاتة داكنة", "شوكولاتة داكنة 70% كاكاو بدون لبن"),
 ("عصير مانجو طبيعي", "عصير مانجو بدون سكر مضاف علبة لتر"), ("بسكويت بالشوفان", "بسكويت شوفان وعسل صحي للاطفال والكبار"), ("مربى فراولة", "مربى فراولة طبيعي برطمان 350 جرام")]
C_hand = {"followups": ["مناسب لبشرتي الحساسة؟", "فيه ضمان صلاحية؟", "كام جرام؟", "طبيعي ولا صناعي؟", "من سن كام؟", "بيتخزن ازاي؟"],
 "arabizi": [("3ayez 3atr misk regaly", ["عطر مسك رجالي"]), ("shampoo lel sha3r el gaf", ["شامبو للشعر الجاف"]), ("kareem tarteeb lel wesh", ["كريم ترطيب للوجه"]), ("3asal na7l tabe3y", ["عسل نحل طبيعي"]),
   ("zeit zeitoon bekr", ["زيت زيتون بكر"]), ("tamr sukkary", ["تمر سكري"]), ("shanta madrasa lel atfal", ["شنطة مدرسة للاطفال"]), ("lo3ba mokaab lel atfal", ["لعبة مكعبات للاطفال"])],
 "english": [("face moisturizer cream", ["كريم ترطيب للوجه"]), ("dry hair shampoo", ["شامبو للشعر الجاف"]), ("natural honey", ["عسل نحل طبيعي"]), ("olive oil", ["زيت زيتون بكر"]),
   ("waterproof mascara", ["ماسكارا مقاومة للماء"]), ("baby diapers size 4", ["حفاضات اطفال مقاس 4"]), ("green tea", ["شاي اخضر"]), ("dark chocolate", ["شوكولاتة داكنة"])],
 "attribute": [("الكريم ده فيه عطر", ["كريم ترطيب للوجه"]), ("العسل مصدره ايه", ["عسل نحل طبيعي"]), ("الشوكولاتة فيها لبن", ["شوكولاتة داكنة"]), ("العروسة بتتغسل", ["عروسة قماش ناعمة"]),
   ("الماسكارا بتقاوم الماء", ["ماسكارا مقاومة للماء"]), ("زجاجة الرضاعة فيها بيسفينول", ["زجاجة رضاعة 240 مل"])],
 "category": [("عايز عطر", ["عطر مسك رجالي", "عطر ورد حريمي"]), ("عايز حاجة للشعر", ["شامبو للشعر الجاف", "بلسم للشعر", "زيت شعر طبيعي"]), ("عايز لعبة للاطفال", ["لعبة مكعبات للاطفال", "عروسة قماش ناعمة", "سيارة تحكم عن بعد"]), ("عايز مكياج", ["احمر شفاه مطفي", "ماسكارا مقاومة للماء", "كريم اساس سائل"])],
 "paraphrase": [("حاجة تخلي شعري ناعم", ["بلسم للشعر", "شامبو للشعر الجاف", "زيت شعر طبيعي"]), ("هدية لطفل صغير", ["لعبة مكعبات للاطفال", "عروسة قماش ناعمة"]), ("حاجة حلوة وصحية", ["شوكولاتة داكنة", "بسكويت بالشوفان", "تمر سكري"]),
   ("رايحة المدرسة", ["شنطة مدرسة للاطفال", "حذاء مدرسي اسود"]), ("اشرب حاجة منعشة", ["عصير مانجو طبيعي", "شاي اخضر"])],
 "notsold": ["فستان سهرة", "لابتوب", "دواء للصداع", "كورس انجليزي"]}
shops = [{"name": "A-fashion", "products": A_products, "queries": A_queries}, shop("B-electronics-home", B_rows, B_hand), shop("C-beauty-kids-food", C_rows, C_hand)]
out = {"description": "synthetic, author-written, fixed; see build_dataset.py", "shops": shops}
dest = os.path.join(here, "../../src/test/resources/retrieval/dataset.json")
json.dump(out, open(dest, "w"), ensure_ascii=False, indent=1)
for s in shops:
    from collections import Counter
    c = Counter(q["category"] for q in s["queries"])
    print(s["name"], len(s["products"]), "products,", len(s["queries"]), "queries:", dict(c))
print("total queries:", sum(len(s["queries"]) for s in shops))
