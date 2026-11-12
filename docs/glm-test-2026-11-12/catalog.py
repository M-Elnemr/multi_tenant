import json,urllib.request,urllib.parse,time
from concurrent.futures import ThreadPoolExecutor
def get(shop,path):
    for i in range(8):
        try: return json.load(urllib.request.urlopen(f"https://{shop}.elmanassa.shop/api/v1/shop/"+path,timeout=30))
        except Exception: time.sleep(2)
    return None
def one(a):
    shop,p=a; d=get(shop,'products/'+urllib.parse.quote(p['slug']))
    if not d: return None
    return {'name':d['name'],'slug':d['slug'],'options':[(o['name'],o['values']) for o in d.get('options',[])],'variants':[{'combo':v.get('comboKey'),'price':v['priceMinor']/100,'available':v.get('available')} for v in d.get('variants',[])]}
out={}
for shop in ['tech-zone','nile-style','beit-elshawarma','misk-oud-soud']:
    lst=[];page=1
    while True:
        r=get(shop,f'products?pageSize=100&page={page}'); lst+=r['data']
        if not r['meta'].get('hasNext'): break
        page+=1
    with ThreadPoolExecutor(8) as ex: out[shop]=[x for x in ex.map(one,[(shop,p) for p in lst]) if x]
    print(shop,len(out[shop]),'of',len(lst),flush=True)
json.dump(out,open('catalog.json','w'),ensure_ascii=False)
