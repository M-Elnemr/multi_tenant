// Local-only stand-in for the shop API (/api/v1/...), used to verify large-catalog templates in a browser without the Spring backend.
// Usage: node mock-backend.mjs [port=8080]; then API_BASE_URL=http://localhost:8080 npm run dev and open http://shop.localhost:3000/?tpl=sn-market
// Synthetic test data only (placeholder SVG pictures). MOCK_SIZE=few|many (default many), MOCK_NOIMG=1 drops every picture.
import http from "node:http";
const PORT = +(process.argv[2] ?? 8080);
const MANY = (process.env.MOCK_SIZE ?? "many") !== "few";
const NOIMG = process.env.MOCK_NOIMG === "1";

const CATS = [
  { id: "c1", parentId: null, slug: "women", ar: "نسائي", en: "Women" },
  { id: "c2", parentId: null, slug: "men", ar: "رجالي", en: "Men" },
  { id: "c3", parentId: null, slug: "home", ar: "المنزل والديكور", en: "Home & decor" },
  { id: "c4", parentId: null, slug: "beauty", ar: "العناية والجمال", en: "Beauty" },
  { id: "c5", parentId: null, slug: "bags", ar: "الحقائب والإكسسوارات", en: "Bags & accessories" },
  { id: "c11", parentId: "c1", slug: "dresses", ar: "فساتين", en: "Dresses" },
  { id: "c12", parentId: "c1", slug: "tops", ar: "بلوزات", en: "Tops" },
  { id: "c21", parentId: "c2", slug: "shirts", ar: "قمصان", en: "Shirts" },
  { id: "c22", parentId: "c2", slug: "jackets", ar: "جاكيتات", en: "Jackets" },
  { id: "c31", parentId: "c3", slug: "candles", ar: "شموع", en: "Candles" },
  { id: "c32", parentId: "c3", slug: "textiles", ar: "مفروشات", en: "Textiles" },
];
const NAMES = [
  ["فستان كتان طويل بأكمام واسعة", "Long linen dress with wide sleeves", "dresses", "women", 189000, 0],
  ["فستان سهرة ساتان", "Satin evening dress", "dresses", "women", 349000, 20],
  ["بلوزة قطن عضوي", "Organic cotton blouse", "tops", "women", 89000, 0],
  ["بلوزة حرير بياقة مفتوحة", "Silk blouse with open collar", "tops", "women", 159000, 0],
  ["قميص أكسفورد كلاسيكي", "Classic oxford shirt", "shirts", "men", 119000, 0],
  ["قميص كتان صيفي", "Summer linen shirt", "shirts", "men", 99000, 15],
  ["جاكيت جلد طبيعي", "Genuine leather jacket", "jackets", "men", 899000, 0],
  ["جاكيت خفيف مقاوم للمطر", "Lightweight rain jacket", "jackets", "men", 279000, 0],
  ["شمعة صويا برائحة العود", "Soy candle, oud scent", "candles", "home", 45000, 0],
  ["شمعة معطرة بالفانيلا والخشب", "Vanilla and cedar scented candle", "candles", "home", 52000, 10],
  ["غطاء سرير قطني مطرز يدويا بتفاصيل دقيقة ومقاس كبير يناسب الأسرّة الكبيرة جدا", "Hand-embroidered cotton bedspread with fine detailing in a large size that fits very large beds", "textiles", "home", 429000, 0],
  ["وسادة مخمل", "Velvet cushion", "textiles", "home", 38000, 0],
  ["سيروم فيتامين سي", "Vitamin C serum", "beauty", "beauty", 69000, 0],
  ["كريم مرطب للوجه", "Face moisturiser", "beauty", "beauty", 54000, 0],
  ["حقيبة يد جلد", "Leather handbag", "bags", "bags", 459000, 25],
  ["حقيبة ظهر قماش", "Canvas backpack", "bags", "bags", 139000, 0],
  ["حزام جلد مضفر", "Braided leather belt", "bags", "bags", 49000, 0],
  ["وشاح صوف", "Wool scarf", "bags", "bags", 59000, 0],
];
const COLORS = [["black", "أسود", "Black", "#161616"], ["sand", "رملي", "Sand", "#d8c3a0"], ["olive", "زيتي", "Olive", "#5f6b3a"], ["rust", "قرميدي", "Rust", "#b4552d"], ["navy", "كحلي", "Navy", "#1d2a4a"]];
const PAL = [["#e9dccb", "#b08d57"], ["#d6e0d4", "#4a5d3a"], ["#e5d3d0", "#a4553f"], ["#d3dbe7", "#2d3f66"], ["#efe6d2", "#8a6a3b"], ["#e1e1e1", "#333"]];
const total = MANY ? 40 : 3;
const PRODUCTS = Array.from({ length: total }, (_, i) => {
  const b = NAMES[i % NAMES.length], rep = Math.floor(i / NAMES.length), idx = i + 1;
  const colorSet = COLORS.slice(0, 2 + (i % 3));
  const sizes = i % 4 === 3 ? [] : ["S", "M", "L", "XL"];
  const variants = [];
  for (const c of colorSet) for (const s of sizes.length ? sizes : [null]) {
    const combo = [`اللون=${c[1]}`, ...(s ? [`المقاس=${s}`] : [])].join("|");
    variants.push({ id: `v${idx}-${c[0]}-${s}`, sku: `SKU${idx}${c[0]}${s}`, priceMinor: b[4] + rep * 5000, comboKey: combo, available: i === 5 ? 0 : c[0] === "sand" && s === "L" ? 3 : 12 });
  }
  return { idx, ar: b[0] + (rep ? ` ${rep + 1}` : ""), en: b[1] + (rep ? ` ${rep + 1}` : ""), slug: `p${idx}-${b[1].toLowerCase().replace(/[^a-z]+/g, "-").slice(0, 28)}`, cat: b[2], top: b[3], price: b[4] + rep * 5000, off: b[5], brand: ["Nile House", "Delta Atelier", "Siwa Home", "Luxor"][i % 4], inStock: i !== 5, hasImg: !NOIMG && i !== 9, pal: PAL[i % PAL.length], badge: i % 7 === 0 ? "NEW" : i % 11 === 0 ? "BEST_SELLER" : "", rating: i % 3 === 0 ? 4.5 : null, ratingCount: i % 3 === 0 ? 12 : 0, colors: colorSet, sizes, variants, flash: i === 1 };
});
const img = (p, n = 0) => (p.hasImg ? `http://localhost:${PORT}/img/${p.idx}-${n}.svg` : null);
const rnd = (v) => Math.round(v / 100) * 100;
const card = (p, lang) => ({
  id: `p${p.idx}`, name: lang === "en" ? p.en : p.ar, slug: p.slug, brand: p.brand, currency: "EGP", minPriceMinor: p.price, compareAtMinor: p.off && !p.flash ? rnd((p.price * 100) / (100 - p.off)) : null, discountPct: p.off && !p.flash ? p.off : null,
  badge: p.badge || undefined, offer: p.flash ? { type: "FLASH_SALE", badge: lang === "en" ? "Flash sale" : "تخفيض سريع", percent: p.off, salePriceMinor: rnd((p.price * (100 - p.off)) / 100), endsAt: new Date(Date.now() + 172800000).toISOString() } : null,
  rating: p.rating, ratingCount: p.ratingCount, imageUrl: img(p), inStock: p.inStock,
});
const catList = (lang) => CATS.map((c, i) => {
  const kids = CATS.filter((k) => k.parentId === c.id).map((k) => k.slug);
  const n = PRODUCTS.filter((p) => p.cat === c.slug || (!c.parentId && p.top === c.slug) || kids.includes(p.cat)).length;
  return { id: c.id, parentId: c.parentId, name: lang === "en" ? c.en : c.ar, nameAr: c.ar, nameEn: c.en, slug: c.slug, level: c.parentId ? 2 : 1, productCount: n, sortOrder: i, imageFileId: null };
});
const profile = (lang) => ({
  storeName: lang === "en" ? "Nile House" : "بيت النيل", shortDescription: lang === "en" ? "Clothing, home and gifts chosen with care." : "ملابس ومنزل وهدايا مختارة بعناية.",
  about: lang === "en" ? "We started as one small workshop and now ship across Egypt. Every piece is checked by hand before it leaves us." : "بدأنا من ورشة صغيرة ونشحن الآن لكل أنحاء مصر. كل قطعة بتتراجع بإيدينا قبل ما تخرج.",
  supportPhone: "+20 100 000 0000", supportEmail: "hello@example.com", addressText: lang === "en" ? "12 Example Street, Cairo" : "١٢ شارع المثال، القاهرة", whatsapp: "201000000000", isOpen: true, returnWindowDays: 14,
  announcement: lang === "en" ? "Free delivery on orders above 1,500 EGP" : "شحن مجاني للطلبات فوق ١٥٠٠ جنيه",
  workingHours: { sat: { open: "10:00", close: "22:00", closed: false } }, shippingMethods: [{ type: "FREE_ABOVE", freeAboveMinor: 150000 }], shippingZones: [],
  instagramUrl: "https://instagram.com/example", facebookUrl: "https://facebook.com/example",
});
const sorters = { newest: (a, b) => b.idx - a.idx, popular: (a, b) => (b.rating ?? 0) - (a.rating ?? 0), price_asc: (a, b) => a.price - b.price, price_desc: (a, b) => b.price - a.price, discount: (a, b) => b.off - a.off };
const pick = (n, off = 0) => PRODUCTS.slice(off, off + n);
const svg = (p, n) => { const [a, b] = p.pal; return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 480 600"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${n % 2 ? "#fff" : a}" stop-opacity=".6"/></linearGradient></defs><rect width="480" height="600" fill="url(#g)"/><circle cx="${240 + (n - 1) * 40}" cy="${240 - n * 10}" r="${120 - n * 14}" fill="${b}" opacity=".85"/><rect x="${120 + n * 15}" y="330" width="${260 - n * 20}" height="150" rx="${n * 30}" fill="${b}" opacity=".5"/><text x="30" y="570" font-size="26" font-family="sans-serif" fill="${b}" opacity=".7">${p.idx}.${n + 1}</text></svg>`; };
const json = (res, data, status = 200) => { res.writeHead(status, { "content-type": "application/json" }); res.end(JSON.stringify(data)); };
const inH = (n) => new Date(Date.now() + n * 3600000).toISOString();

http.createServer((req, res) => {
  const u = new URL(req.url, `http://localhost:${PORT}`), q = u.searchParams, lang = q.get("lang") === "en" ? "en" : "ar", path = u.pathname;
  const im = path.match(/^\/img\/(\d+)-(\d+)\.svg$/);
  if (im) { res.writeHead(200, { "content-type": "image/svg+xml", "cache-control": "max-age=3600" }); return res.end(svg(PRODUCTS[+im[1] - 1], +im[2])); }
  if (!path.startsWith("/api/v1/")) return json(res, { code: "NOT_FOUND" }, 404);
  const r = path.slice(7);
  if (r === "/tenant/resolve") return json(res, { kind: "TENANT", id: "t1", slug: "demo", name: lang === "en" ? "Nile House" : "بيت النيل", type: "STORE", status: "ACTIVE", locale: process.env.MOCK_LOCALE ?? "ar", currency: "EGP", timezone: "Africa/Cairo", host: "shop.localhost", branding: {} });
  if (r === "/shop/profile") return json(res, { profile: profile(lang) });
  if (r === "/shop/categories") return json(res, catList(lang));
  if (r === "/shop/branches") return json(res, [{ id: "b1", name: lang === "en" ? "Cairo flagship" : "الفرع الرئيسي - القاهرة", code: "CAI", phone: "+20 100 000 0001", addressLine1: lang === "en" ? "12 Example Street" : "١٢ شارع المثال", city: "Cairo", governorateCode: "C", isPickup: true }]);
  if (r === "/shop/home") return json(res, {
    offerBanners: [{ id: "o1", name: lang === "en" ? "Weekend flash sale" : "تخفيض نهاية الأسبوع", type: "FLASH_SALE", badge: lang === "en" ? "20% off selected pieces" : "خصم ٢٠٪ على قطع مختارة", endsAt: inH(48), config: {} }],
    featured: pick(8, 0).map((p) => card(p, lang)), newest: pick(10, 6).map((p) => card(p, lang)), offers: PRODUCTS.filter((p) => p.off).slice(0, 8).map((p) => card(p, lang)), bestSellers: pick(8, 12).map((p) => card(p, lang)),
    banners: NOIMG ? [] : [{ id: "bn1", imageFileId: "x", title: lang === "en" ? "Spring collection" : "تشكيلة الربيع", subtitle: lang === "en" ? "Light fabrics, easy colours" : "أقمشة خفيفة وألوان هادئة", linkUrl: "/products?sort=newest" }],
  });
  if (r === "/shop/products/facets") return json(res, { brands: ["Nile House", "Delta Atelier", "Siwa Home", "Luxor"].map((brand, i) => ({ brand, n: 10 - i })), priceMinMinor: 38000, priceMaxMinor: 899000, total: PRODUCTS.length, audiences: [{ audience: "WOMEN", n: 20 }, { audience: "MEN", n: 12 }], colors: COLORS.map((c, i) => ({ code: c[0], nameAr: c[1], nameEn: c[2], hex: c[3], n: 10 - i })), sizes: ["S", "M", "L", "XL"].map((s) => ({ scale: "CLOTHING", code: s, nameAr: s, nameEn: s, n: 12 })), conditions: [] });
  if (r === "/shop/products") {
    let l = PRODUCTS.slice(); const cat = q.get("category"), t = (q.get("q") ?? "").trim().toLowerCase();
    if (cat) { const kids = CATS.filter((k) => k.parentId === CATS.find((c) => c.slug === cat)?.id).map((k) => k.slug); l = l.filter((p) => p.cat === cat || p.top === cat || kids.includes(p.cat)); }
    if (t) l = l.filter((p) => p.ar.includes(t) || p.en.toLowerCase().includes(t));
    if (q.get("brand")) { const bs = q.get("brand").split(","); l = l.filter((p) => bs.includes(p.brand)); }
    if (q.get("inStock") === "true") l = l.filter((p) => p.inStock);
    if (q.get("onSale") === "true") l = l.filter((p) => p.off);
    if (q.get("minPrice")) l = l.filter((p) => p.price >= +q.get("minPrice"));
    if (q.get("maxPrice")) l = l.filter((p) => p.price <= +q.get("maxPrice"));
    if (q.get("minRating")) l = l.filter((p) => (p.rating ?? 0) >= +q.get("minRating"));
    l.sort(sorters[q.get("sort")] ?? sorters.newest);
    const size = +(q.get("pageSize") ?? 12), page = +(q.get("page") ?? 1);
    return json(res, { data: l.slice((page - 1) * size, page * size).map((p) => card(p, lang)), meta: { page, pageSize: size, total: l.length, hasNext: page * size < l.length } });
  }
  if (r === "/shop/search/suggest") { const t = (q.get("q") ?? "").toLowerCase(); return json(res, { products: PRODUCTS.filter((p) => p.ar.includes(t) || p.en.toLowerCase().includes(t)).slice(0, 5).map((p) => ({ id: `p${p.idx}`, name: lang === "en" ? p.en : p.ar, slug: p.slug, minPriceMinor: p.price, currency: "EGP", imageUrl: img(p) })), categories: CATS.filter((c) => c.ar.includes(t) || c.en.toLowerCase().includes(t)).map((c) => ({ name: c.ar, nameEn: c.en, slug: c.slug })) }); }
  const m = r.match(/^\/shop\/products\/([^/]+)(\/related|\/together)?$/);
  if (m) {
    const slug = decodeURIComponent(m[1]), p = PRODUCTS.find((x) => x.slug === slug);
    if (!p) return json(res, { code: "NOT_FOUND", message: "not found" }, 404);
    if (m[2] === "/related") return json(res, PRODUCTS.filter((x) => x !== p && (x.cat === p.cat || x.top === p.top)).slice(0, 6).map((x) => card(x, lang)));
    if (m[2] === "/together") return json(res, PRODUCTS.filter((x) => x.top !== p.top).slice(0, 3).map((x) => ({ id: `p${x.idx}`, name: lang === "en" ? x.en : x.ar, slug: x.slug, currency: "EGP", priceMinor: x.price })));
    const opts = [{ name: "اللون", values: p.colors.map((c) => c[1]), attribute: "COLOR", labelAr: "اللون", labelEn: "Colour", valueMeta: p.colors.map((c) => ({ value: c[1], nameAr: c[1], nameEn: c[2], hex: c[3] })) }];
    if (p.sizes.length) opts.push({ name: "المقاس", values: p.sizes, attribute: "SIZE", labelAr: "المقاس", labelEn: "Size" });
    const variants = p.variants.map((v) => ({ ...v, salePriceMinor: p.off ? rnd((v.priceMinor * (100 - p.off)) / 100) : undefined }));
    return json(res, {
      id: `p${p.idx}`, name: lang === "en" ? p.en : p.ar, slug: p.slug, brand: p.brand, currency: "EGP", hasVariants: true, badge: p.badge || undefined,
      description: lang === "en" ? "Made from natural fibres and finished by hand.\nWashes well and keeps its shape.\nDesigned to be worn often." : "مصنوع من ألياف طبيعية ومشطب باليد.\nيتحمل الغسيل ويحافظ على شكله.\nمصمم علشان تلبسه كتير.",
      shortDescription: lang === "en" ? "Natural fibres, finished by hand." : "ألياف طبيعية ومشطب باليد.", specs: [{ k: lang === "en" ? "Material" : "الخامة", v: lang === "en" ? "Cotton / linen" : "قطن / كتان" }, { k: lang === "en" ? "Origin" : "المنشأ", v: lang === "en" ? "Egypt" : "مصر" }],
      sizeGuide: p.sizes.length ? (lang === "en" ? "S fits chest 90cm, M 96cm, L 102cm, XL 108cm." : "S صدر ٩٠، M ٩٦، L ١٠٢، XL ١٠٨ سم.") : undefined,
      options: opts, variants, offer: p.flash ? { type: "FLASH_SALE", badge: "Flash", percent: p.off, endsAt: inH(48) } : null, offerHints: [],
      media: p.hasImg ? [0, 1, 2].map((n) => ({ url: img(p, n), altText: "" })) : [],
      reviews: p.rating ? { average: p.rating, count: 2, reviews: [{ rating: 5, reviewText: lang === "en" ? "Good quality, as pictured." : "جودة ممتازة زي الصورة.", firstName: "Sara" }, { rating: 4, firstName: "Omar" }] } : { average: 0, count: 0, reviews: [] },
      taxonomy: [CATS.find((c) => c.slug === p.top), CATS.find((c) => c.slug === p.cat)].filter((c, i, a) => c && a.indexOf(c) === i).map((c) => ({ slug: c.slug, nameAr: c.ar, nameEn: c.en })),
    });
  }
  if (r === "/auth/me") return json(res, { code: "UNAUTHENTICATED" }, 401);
  return json(res, { code: "NOT_FOUND", message: r }, 404);
}).listen(PORT, () => console.log(`mock shop api on :${PORT} (${MANY ? "many" : "few"} products${NOIMG ? ", no images" : ""})`));
