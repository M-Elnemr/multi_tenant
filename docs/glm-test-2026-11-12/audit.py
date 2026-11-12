import json,re,sys
cat=json.load(open('catalog.json')); rep=json.load(open(sys.argv[1] if len(sys.argv)>1 else 'glm-report.json'))
ar=str.maketrans('٠١٢٣٤٥٦٧٨٩','0123456789')
COLORS="أسود|اسود|أبيض|ابيض|كحلي|أزرق|ازرق|أحمر|احمر|أخضر|اخضر|بيج|بني|رمادي|وردي|زيتي|أصفر|اصفر|برتقالي|بنفسجي|فضي|ذهبي|سماوي|نبيتي|خمري|جملي|كاكي|عنابي|تركواز|ليموني|لافندر|موف"
def norm(s): return s.replace('أ','ا').replace('إ','ا').replace('ى','ي').replace('ة','ه')
V={};A={}
for shop,items in cat.items():
    V[shop]=set();A[shop]=set()
    for p in items:
        for n,vs in p['options']: V[shop]|={norm(v) for v in vs}
        for v in p['variants']:
            if v['available'] is not None: A[shop].add(int(v['available']))
for c in rep['cells']:
    s=c['store']
    for x in c['results']:
        for t in x['transcript']:
            raw=t['seller']; txt=raw.translate(ar)
            for m in re.finditer(r'(\d+)\s*(?:قطع|قطعة|قطعه|قطعتين|وحدات|حبة|حبات)|[×✕]\s*(\d+)',txt):
                n=int(m.group(1) or m.group(2)); flag='' if n in A[s] else '  <-- not a stock number of any variant in the catalog'
                print(f"{s} {x['id']} qty {n}: …{txt[max(0,m.start()-45):m.end()+25]!r}{flag}")
            for m in re.finditer(r'(?<![؀-ۿ])(?:ال)?('+COLORS+')(?![؀-ۿ])',raw):
                if norm(m.group(1)) not in V[s]: print(f"{s} {x['id']} COLOR '{m.group(1)}' not an option value anywhere in the shop: …{raw[max(0,m.start()-40):m.end()+20]!r}")
