// TEST-ONLY mock of the Open Store API, for visual QA of the redesign (workstream F). Synthetic "Demo ..." data. Never deployed, never used in production.
// Run: node server.mjs   (port 8080)    Web: API_BASE_URL=http://localhost:8080 npm run dev, open http://open.localhost:3000
import http from "node:http";

const PORT = Number(process.env.PORT || 8080);
const palette = ["#e8d5c4", "#cfd8dc", "#d7e3d0", "#f1d9c9", "#d9d3ea", "#f3e6b8", "#c9dde6", "#ead0d6"];
const svg = (i) => {
  const c = palette[i % palette.length], d = palette[(i + 3) % palette.length];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="800" viewBox="0 0 800 800"><rect width="800" height="800" fill="${c}"/><circle cx="${300 + (i % 5) * 40}" cy="${340 + (i % 3) * 30}" r="${170 + (i % 4) * 20}" fill="${d}"/><rect x="${230 + (i % 4) * 30}" y="470" width="340" height="120" rx="60" fill="#fff" opacity=".55"/></svg>`;
};
const img = (i) => ({ thumb: `http://localhost:${PORT}/img/${i}.svg`, medium: `http://localhost:${PORT}/img/${i}.svg`, original: `http://localhost:${PORT}/img/${i}.svg` });

const cats = [
  { id: 1, parentId: null, level: 0, slug: "fashion", nameAr: "أزياء", nameEn: "Fashion", icon: "👗", n: 8, children: [{ id: 11, parentId: 1, level: 1, slug: "women", nameAr: "نسائي", nameEn: "Women", n: 4 }, { id: 12, parentId: 1, level: 1, slug: "men", nameAr: "رجالي", nameEn: "Men", n: 4 }] },
  { id: 2, parentId: null, level: 0, slug: "electronics", nameAr: "إلكترونيات", nameEn: "Electronics", icon: "🎧", n: 6, children: [{ id: 21, parentId: 2, level: 1, slug: "audio", nameAr: "صوتيات", nameEn: "Audio", n: 3 }] },
  { id: 3, parentId: null, level: 0, slug: "home", nameAr: "المنزل", nameEn: "Home", icon: "🛋️", n: 5, children: [] },
  { id: 4, parentId: null, level: 0, slug: "beauty", nameAr: "جمال", nameEn: "Beauty", icon: "💄", n: 5, children: [] },
];
const shops = [
  { id: "s1", slug: "demo-atelier", name: "Demo Atelier", shortDescription: "Test shop with synthetic products for visual QA.", isOpen: true, primaryColor: "#7a4b2a", logoUrl: null, host: "demo-atelier.localhost", url: "http://demo-atelier.localhost:3000", productCount: 9, rating: 4.6, ratingCount: 31, createdAt: "2026-01-01" },
  { id: "s2", slug: "demo-sound", name: "ديمو ساوند", shortDescription: "متجر تجريبي لاختبار التصميم.", isOpen: true, primaryColor: "#1f4e79", logoUrl: null, host: "demo-sound.localhost", url: "http://demo-sound.localhost:3000", productCount: 8, rating: 4.2, ratingCount: 12, createdAt: "2026-01-01" },
  { id: "s3", slug: "demo-home", name: "Demo Home", shortDescription: null, isOpen: false, closedMessage: "Closed until tomorrow", primaryColor: "#2f6b4f", logoUrl: null, host: "demo-home.localhost", url: "http://demo-home.localhost:3000", productCount: 7, rating: null, ratingCount: 0, createdAt: "2026-01-01" },
];
const names = [["Linen Overshirt", "قميص كتان"], ["Wide Trousers", "بنطلون واسع"], ["Leather Tote Bag", "حقيبة جلد"], ["Wireless Headphones", "سماعات لاسلكية"], ["Mini Speaker", "سماعة صغيرة"], ["Ceramic Vase Set", "طقم مزهريات"], ["Wool Throw Blanket", "بطانية صوف"], ["Rose Face Serum", "سيروم الورد"], ["Silk Scarf", "وشاح حرير"], ["Canvas Sneakers", "حذاء كانفاس"], ["Desk Lamp", "مصباح مكتب"], ["Matte Lipstick", "أحمر شفاه مطفي"], ["Cotton Tee", "تيشيرت قطن"], ["Smart Watch", "ساعة ذكية"], ["Scented Candle", "شمعة عطرية"], ["Denim Jacket", "جاكيت جينز"], ["Earbuds Case", "حافظة سماعات"], ["Oak Serving Board", "لوح تقديم خشب"], ["Hair Oil", "زيت شعر"], ["Wrap Dress", "فستان لف"], ["Bluetooth Keyboard", "كيبورد بلوتوث"], ["Linen Cushion", "وسادة كتان"], ["Body Butter", "زبدة الجسم"], ["Summer Hat", "قبعة صيف"]];
const catFor = (i) => (i % 8 < 3 ? "women" : i % 8 < 5 ? "audio" : i % 8 < 6 ? "home" : "beauty");
const topFor = { women: "fashion", men: "fashion", audio: "electronics", home: "home", beauty: "beauty" };
const products = names.map(([en, ar], i) => {
  const shop = shops[i % 3];
  const price = ((180 + i * 137) * 100) % 900000 + 15000;
  const sale = i % 4 === 0;
  return {
    id: `p${i}`, shopId: shop.id, name: i % 2 ? ar : en, slug: `item-${i}`, brand: i % 3 === 0 ? "Demo Brand" : null, currency: "EGP", minPriceMinor: price, compareAtMinor: sale ? Math.round(price * 1.3) : null, discountPct: sale ? 23 : 0,
    badge: i % 7 === 1 ? "NEW" : i % 7 === 3 ? "BEST_SELLER" : null, rating: i % 5 === 4 ? 0 : 3.8 + (i % 3) * 0.4, ratingCount: i % 5 === 4 ? 0 : 3 + i, inStock: i !== 6, freeShipping: i % 3 === 1, soldCount: 40 - i,
    image: i % 9 === 5 ? null : img(i), shop: { id: shop.id, slug: shop.slug, name: shop.name, host: shop.host }, categorySlug: catFor(i),
  };
});
const home = () => ({
  categories: cats, deals: products.filter((p) => p.discountPct > 0).slice(0, 6), trending: products.slice(0, 10), newArrivals: products.slice(8, 18), topRated: products.slice(2, 12), featured: products.slice(0, 3), shops,
  categoryRows: cats.slice(0, 2).map((c) => ({ ...c, products: products.filter((p) => topFor[p.categorySlug] === c.slug).slice(0, 6) })), stats: { products: products.length, shops: shops.length },
});
const page = (arr, q) => { const ps = +(q.get("pageSize") ?? 24), pg = +(q.get("page") ?? 1); return { data: arr.slice((pg - 1) * ps, pg * ps), meta: { page: pg, pageSize: ps, total: arr.length, hasNext: pg * ps < arr.length } }; };
const filt = (q) => {
  let a = products.slice();
  const s = q.get("q"); if (s) a = a.filter((p) => (p.name + p.shop.name).toLowerCase().includes(s.toLowerCase()));
  const c = q.get("category"); if (c) a = a.filter((p) => p.categorySlug === c || topFor[p.categorySlug] === c);
  const sh = q.get("shop"); if (sh) a = a.filter((p) => p.shop.slug === sh);
  if (q.get("onSale") === "true") a = a.filter((p) => p.discountPct > 0);
  if (q.get("inStock") === "true") a = a.filter((p) => p.inStock);
  if (q.get("maxPrice")) a = a.filter((p) => p.minPriceMinor <= +q.get("maxPrice"));
  const so = q.get("sort"); if (so === "price_asc") a.sort((x, y) => x.minPriceMinor - y.minPriceMinor); if (so === "price_desc") a.sort((x, y) => y.minPriceMinor - x.minPriceMinor);
  return a;
};
const detail = (shop, slug) => {
  const p = products.find((x) => x.shop.slug === shop && x.slug === slug); if (!p) return null;
  const sh = shops.find((s) => s.slug === shop);
  const multi = p.id === "p0" || p.id === "p3";
  const imgs = multi ? [0, 1, 2, 3].map((k) => ({ ...img(+p.id.slice(1) + k), optionValue: null })) : p.image ? [{ ...p.image, optionValue: null }] : [];
  return { ...p, shopId: sh.id, description: "This is synthetic product copy used only for visual QA of the Open Store redesign.\nSecond paragraph to check line height and wrapping in both languages.", shortDescription: "Short synthetic summary for layout checks.",
    specs: [{ k: "Material", v: "Cotton" }, { k: "Origin", v: "Egypt" }], sizeGuide: multi ? "S: 36-38\nM: 38-40" : undefined, itemCondition: "NEW",
    options: multi ? [{ name: "color", values: ["black", "sand"], valueMeta: [{ value: "black", nameAr: "أسود", nameEn: "Black", hex: "#111" }, { value: "sand", nameAr: "رملي", nameEn: "Sand", hex: "#d8c3a5" }], labelAr: "اللون", labelEn: "Color" }, { name: "size", values: ["S", "M", "L"], labelAr: "المقاس", labelEn: "Size" }] : [],
    variants: multi ? ["black", "sand"].flatMap((c, ci) => ["S", "M", "L"].map((s, si) => ({ id: `v-${p.id}-${c}-${s}`, sku: `${c}${s}`, priceMinor: p.minPriceMinor + si * 1000, compareAtPriceMinor: p.compareAtMinor, comboKey: `color=${c}|size=${s}`, available: ci === 1 && si === 2 ? 0 : 3 + si * 4 }))) : [{ id: `v-${p.id}`, sku: p.id, priceMinor: p.minPriceMinor, compareAtPriceMinor: p.compareAtMinor, comboKey: "", available: p.inStock ? 12 : 0 }],
    images: imgs, taxonomy: [{ slug: topFor[p.categorySlug], nameAr: "تصنيف", nameEn: "Category" }, { slug: p.categorySlug, nameAr: "فرعي", nameEn: "Sub" }], shop: sh,
    reviews: { average: p.rating, count: p.ratingCount ? 2 : 0, reviews: p.ratingCount ? [{ rating: 5, reviewText: "Synthetic review text for layout.", firstName: "Test", createdAt: "2026-01-02" }, { rating: 4, reviewText: "تقييم تجريبي للتصميم فقط.", firstName: "اختبار", createdAt: "2026-01-03" }] : [] },
    delivery: { shippingMethods: [], zones: [{ id: "z1", name: "Cairo", feeMinor: 5000, freeAboveMinor: null, codFeeMinor: 0, etaMinDays: 2, etaMaxDays: 4, governorateCodes: ["C"] }], paymentMethods: [{ method: "CASH_ON_DELIVERY" }] },
    moreFromShop: products.filter((x) => x.shop.slug === shop && x.id !== p.id).slice(0, 6), similar: products.filter((x) => x.categorySlug === p.categorySlug && x.id !== p.id).slice(0, 6) };
};
const facets = (q) => {
  const a = filt(new URLSearchParams([...q].filter(([k]) => ["q", "category", "shop"].includes(k))));
  const c = q.get("category");
  const top = c ? (topFor[c] ?? c) : null;
  const tc = top ? cats.find((x) => x.slug === top) : null;
  return { brands: [{ brand: "Demo Brand", n: 8 }], shops: shops.map((s) => ({ shopId: s.id, shopSlug: s.slug, shopName: s.name, n: a.filter((p) => p.shop.slug === s.slug).length })).filter((s) => s.n), audiences: [], colors: [{ code: "black", nameAr: "أسود", nameEn: "Black", hex: "#111", n: 4 }, { code: "sand", nameAr: "رملي", nameEn: "Sand", hex: "#d8c3a5", n: 3 }], sizes: [{ scale: "alpha", code: "S", nameAr: "S", nameEn: "S", n: 4 }, { scale: "alpha", code: "M", nameAr: "M", nameEn: "M", n: 4 }],
    categories: c ? (tc?.children ?? []).map((x) => ({ slug: x.slug, nameAr: x.nameAr, nameEn: x.nameEn, n: x.n })) : cats.map((x) => ({ slug: x.slug, nameAr: x.nameAr, nameEn: x.nameEn, icon: x.icon, n: x.n })), trail: tc ? [{ slug: tc.slug, nameAr: tc.nameAr, nameEn: tc.nameEn }] : [],
    priceMinMinor: 15000, priceMaxMinor: 90000, total: a.length };
};
const order = { id: "o1", shopId: "s1", shopSlug: "demo-atelier", shopName: "Demo Atelier", supportPhone: "+201000000000", orderNumber: "DA-1001", status: "PREPARING", paymentStatus: "PENDING", currency: "EGP", subtotalMinor: 60000, discountMinor: 0, shippingMinor: 5000, codFeeMinor: 0, totalMinor: 65000, createdAt: "2026-11-20T10:00:00Z", items: [{ orderId: "o1", productId: "p0", productSlug: "item-0", name: "Linen Overshirt", variantName: "Black / M", quantity: 2, unitPriceMinor: 30000, lineTotalMinor: 60000, image: img(0).thumb }], etaMinDays: 2, etaMaxDays: 4 };

const send = (res, code, body, type = "application/json") => { res.writeHead(code, { "content-type": type, "access-control-allow-origin": "*" }); res.end(typeof body === "string" ? body : JSON.stringify(body)); };
http.createServer((req, res) => {
  const u = new URL(req.url, `http://localhost:${PORT}`); const p = u.pathname; const q = u.searchParams;
  let m;
  if ((m = p.match(/^\/img\/(\d+)\.svg$/))) return send(res, 200, svg(+m[1]), "image/svg+xml");
  if (p === "/api/v1/tenant/resolve") return send(res, 200, { kind: "OPEN", rootDomain: "localhost" });
  const o = p.replace("/api/v1/open", "");
  if (o === "/categories") return send(res, 200, cats);
  if (o === "/home") return send(res, 200, home());
  if (o === "/products") return send(res, 200, page(filt(q), q));
  if (o === "/products/facets") return send(res, 200, facets(q));
  if (o === "/suggest") { const s = (q.get("q") ?? "").toLowerCase(); return send(res, 200, { products: products.filter((x) => x.name.toLowerCase().includes(s)).slice(0, 5), categories: cats.filter((c) => (c.nameEn + c.nameAr).toLowerCase().includes(s)), shops: shops.filter((x) => x.name.toLowerCase().includes(s)) }); }
  if (o === "/shops") { let a = shops; if (q.get("q")) a = a.filter((s) => s.name.toLowerCase().includes(q.get("q").toLowerCase())); return send(res, 200, page(a, q)); }
  if ((m = o.match(/^\/shops\/([^/]+)$/))) { const s = shops.find((x) => x.slug === decodeURIComponent(m[1])); return s ? send(res, 200, s) : send(res, 404, { code: "NOT_FOUND" }); }
  if ((m = o.match(/^\/products\/([^/]+)\/([^/]+)$/))) { const d = detail(decodeURIComponent(m[1]), decodeURIComponent(m[2])); return d ? send(res, 200, d) : send(res, 404, { code: "NOT_FOUND" }); }
  if (o === "/me") return send(res, 401, { code: "UNAUTHORIZED" });
  if (o === "/auth/config") return send(res, 200, { googleEnabled: false });
  if (o === "/cart/quote" || o === "/checkout") {
    let body = ""; req.on("data", (c) => (body += c)); req.on("end", () => {
      const b = JSON.parse(body || "{}"); const lines = (b.items ?? []).map((i) => ({ variantId: i.variantId, productName: "Linen Overshirt", unitPriceMinor: 30000, quantity: i.quantity }));
      if (o === "/checkout") return send(res, 200, { orderNumber: "DA-1001", shop: { slug: "demo-atelier", name: "Demo Atelier" } });
      const sub = lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0); const known = !!b.address;
      send(res, 200, { shop: { id: "s1", slug: "demo-atelier", name: "Demo Atelier", host: null }, currency: "EGP", subtotalMinor: sub, discountMinor: 0, shippingMinor: known ? 5000 : 0, codFeeMinor: 0, etaMinDays: known ? 2 : null, etaMaxDays: known ? 4 : null, totalMinor: sub + (known ? 5000 : 0), shippingKnown: known, lines });
    }); return;
  }
  if (o === "/orders/track") return send(res, 200, order);
  if (o === "/orders") return send(res, 200, page([order], q));
  send(res, 404, { code: "NOT_FOUND", message: p });
}).listen(PORT, () => console.log("mock open api on", PORT));
