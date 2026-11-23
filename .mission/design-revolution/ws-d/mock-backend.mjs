// Tiny stand-in for the Spring backend, ONLY for visual QA of the small-catalog templates (no database needed).
// Host decides the catalog size: p0.localhost = 0 products, p1/p2/p3.localhost = 1/2/3 products, pe.localhost = backend error on the product list.
// Run: node mock-backend.mjs   (port 9099)   then   API_BASE_URL=http://localhost:9099 npm run dev -- -p 3200
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
const IMG = process.env.IMG_DIR ?? path.resolve(new URL(".", import.meta.url).pathname, "../../../../../../infra/scripts/.demo-images");
const PORT = 9099;
const img = (n) => `http://localhost:${PORT}/img/${n}.jpg`;

const P = [
  { id: "00000000-0000-0000-0000-000000000001", slug: "granite-cookware-set", img: "cookware", badge: "BEST_SELLER", brand: "Nile Kitchen",
    ar: { name: "طقم حلل جرانيت ٧ قطع", short: "طقم كامل للمطبخ اليومي، سطح غير لاصق وقاعدة تناسب كل أنواع المواقد.", desc: "سبع قطع تغطي الطبخ اليومي: حلتان وطاسة وغطاء زجاجي ومغرفة.\nسطح غير لاصق يسهّل التنظيف ويقلل الزيت.\nقاعدة سميكة توزّع الحرارة بالتساوي وتناسب الغاز والكهرباء." },
    en: { name: "Granite cookware set, 7 pieces", short: "A complete everyday kitchen set with a non-stick surface and a base that suits every hob.", desc: "Seven pieces for everyday cooking: two pots, a pan, a glass lid and a ladle.\nA non-stick surface that cleans easily and uses less oil.\nA thick base that spreads heat evenly and suits gas and electric hobs." },
    options: [{ name: "اللون", labelAr: "اللون", labelEn: "Colour", attribute: "COLOR", values: ["رمادي", "نحاسي"], valueMeta: [{ value: "رمادي", nameAr: "رمادي", nameEn: "Grey", hex: "#4b4f55" }, { value: "نحاسي", nameAr: "نحاسي", nameEn: "Copper", hex: "#b5835a" }] }],
    variants: [{ combo: "اللون=رمادي", available: 14, price: 189900 }, { combo: "اللون=نحاسي", available: 3, price: 199900 }],
    specs: [{ k: "عدد القطع", v: "٧" }, { k: "المادة", v: "جرانيت" }, { k: "الضمان", v: "سنة" }], specsEn: [{ k: "Pieces", v: "7" }, { k: "Material", v: "Granite" }, { k: "Warranty", v: "1 year" }],
    reviews: { average: 4.5, count: 2, reviews: [{ rating: 5, reviewText: "حلل ممتازة وسهلة التنظيف.", firstName: "منى" }, { rating: 4, reviewText: "جودة كويسة جدا.", firstName: "أحمد" }] } },
  { id: "00000000-0000-0000-0000-000000000002", slug: "chef-knife", img: "knife", badge: "", brand: "", offer: { sale: 49900 },
    ar: { name: "سكين الشيف المطروق يدويًا بشفرة ٢٠ سم", short: "شفرة حادة تدوم، مقبض خشبي مريح.", desc: "شفرة من الستانلس مطروقة يدويًا بطول ٢٠ سم.\nمقبض من الخشب المعالج يريح اليد." },
    en: { name: "Hand-forged chef's knife with a 20 cm blade", short: "A sharp blade that lasts, with a comfortable wooden handle.", desc: "A hand-forged stainless blade, 20 cm long.\nA treated wooden handle that rests in the hand." },
    options: [], variants: [{ combo: "", available: 2, price: 64900, sale: 49900 }], specs: [], specsEn: [], reviews: { average: 0, count: 0, reviews: [] } },
  { id: "00000000-0000-0000-0000-000000000003", slug: "cotton-bedding-set", img: "bedding", badge: "NEW", brand: "Nile Home",
    ar: { name: "طقم مفرش سرير قطن ١٠٠٪", short: "قطن ناعم يتحمل الغسيل.", desc: "طقم مفرش من القطن الخالص، ناعم على الجلد ويتحمل الغسيل المتكرر." },
    en: { name: "100% cotton bedding set", short: "Soft cotton that survives the wash.", desc: "A pure cotton bedding set, soft on the skin and made for frequent washing." },
    options: [{ name: "المقاس", labelAr: "المقاس", labelEn: "Size", values: ["١٦٠×٢٠٠", "١٨٠×٢٠٠"] }],
    variants: [{ combo: "المقاس=١٦٠×٢٠٠", available: 0, price: 124900 }, { combo: "المقاس=١٨٠×٢٠٠", available: 9, price: 139900 }], specs: [{ k: "الخامة", v: "قطن ١٠٠٪" }], specsEn: [{ k: "Fabric", v: "100% cotton" }], reviews: { average: 0, count: 0, reviews: [] } },
];
const count = (host) => { const m = /^p(\d)\./.exec(host); return m ? Number(m[1]) : host.startsWith("pe.") ? 2 : 3; };
const card = (x, lang) => { const L = lang === "en" ? x.en : x.ar; const min = Math.min(...x.variants.map((a) => a.price)); return { id: x.id, name: L.name, slug: x.slug, brand: x.brand || undefined, currency: "EGP", minPriceMinor: min, badge: x.badge || undefined, offer: x.offer ? { type: "PERCENT", salePriceMinor: x.offer.sale, percent: 23 } : null, imageUrl: img(x.img), inStock: x.variants.some((a) => a.available > 0) }; };
const detail = (x, lang) => { const L = lang === "en" ? x.en : x.ar; return { id: x.id, name: L.name, slug: x.slug, description: L.desc, shortDescription: L.short, brand: x.brand || undefined, currency: "EGP", hasVariants: x.options.length > 0, badge: x.badge || undefined,
  tags: [], specs: lang === "en" ? x.specsEn : x.specs, options: x.options, variants: x.variants.map((a, i) => ({ id: `${x.id.slice(0, -2)}${x.id.slice(-1)}${i}`, sku: `S${i}`, priceMinor: a.price, salePriceMinor: a.sale, comboKey: a.combo, available: a.available })),
  offer: x.offer ? { type: "PERCENT", percent: 23 } : null, media: [{ url: img(x.img) }, { url: img(x.img) }], reviews: x.reviews }; };
const profile = (lang) => ({ profile: { storeName: lang === "en" ? "Nile Home" : "بيت النيل", shortDescription: lang === "en" ? "Everyday things for the home, made to last." : "حاجات البيت اليومية، معمولة تدوم.", about: lang === "en" ? "A small family workshop selling a few things we use ourselves. Every piece is chosen and tested before it goes on sale." : "ورشة عائلية صغيرة بنبيع فيها حاجات قليلة بنستخدمها بنفسنا. كل قطعة بتتختار وبتتجرب قبل ما تتباع.",
  supportPhone: "01012345678", supportEmail: "hello@nilehome.example", addressText: lang === "en" ? "12 Nile St, Giza" : "١٢ شارع النيل، الجيزة", whatsapp: "201012345678", instagramUrl: "https://instagram.com/nilehome", facebookUrl: "https://facebook.com/nilehome", isOpen: true, returnWindowDays: 14, vatIncluded: true,
  shippingPolicy: lang === "en" ? "We ship within 2-4 working days across Egypt." : "بنشحن خلال ٢-٤ أيام عمل لكل محافظات مصر.", returnPolicy: "",
  workingHours: { sat: { open: "10:00", close: "20:00", closed: false }, sun: { open: "10:00", close: "20:00", closed: false }, fri: { open: "00:00", close: "00:00", closed: true } }, branding: { logoFileId: null } },
  paymentMethods: [{ method: "CASH_ON_DELIVERY" }], shippingMethods: [{ id: "m1", type: "ZONES", name: "Delivery", feeMinor: 0 }, { id: "m2", type: "PICKUP", name: "Pickup", feeMinor: 0 }],
  shippingZones: [{ id: "z1", name: "Cairo", governorateCodes: ["C", "GZ"], feeMinor: 5000, freeAboveMinor: 150000, codFeeMinor: 0, etaMinDays: 1, etaMaxDays: 3 }, { id: "z2", name: "Delta", governorateCodes: ["DK"], feeMinor: 8000, codFeeMinor: 0, etaMinDays: 2, etaMaxDays: 4 }] });

const send = (res, code, body) => { res.writeHead(code, { "content-type": "application/json", "access-control-allow-origin": "*" }); res.end(body === undefined ? "" : JSON.stringify(body)); };
http.createServer((req, res) => {
  const u = new URL(req.url, "http://x"); const host = (req.headers["x-forwarded-host"] ?? req.headers.host ?? "").split(",")[0].trim(); const lang = u.searchParams.get("lang") ?? "ar";
  if (u.pathname.startsWith("/img/")) { const f = path.join(IMG, path.basename(u.pathname)); if (fs.existsSync(f)) { res.writeHead(200, { "content-type": "image/jpeg", "access-control-allow-origin": "*", "cache-control": "max-age=3600" }); return fs.createReadStream(f).pipe(res); } return send(res, 404, {}); }
  const p = u.pathname.replace("/api/v1", ""); const n = count(host); const list = P.slice(0, n);
  if (p === "/tenant/resolve") return send(res, 200, { kind: "TENANT", id: "t1", slug: "nile", name: "بيت النيل", type: "STORE", status: "ACTIVE", locale: "ar", currency: "EGP", timezone: "Africa/Cairo", host, branding: {} });
  if (p === "/shop/profile") return send(res, 200, profile(lang));
  if (p === "/shop/home") return send(res, 200, { featured: list.slice(0, 1).map((x) => card(x, lang)), newest: list.map((x) => card(x, lang)), offers: [], bestSellers: [], banners: [] });
  if (p === "/shop/products") { if (host.startsWith("pe.")) return send(res, 500, { code: "ERROR", message: "boom" }); return send(res, 200, { data: list.map((x) => card(x, lang)), meta: { page: 1, pageSize: 12, total: list.length, hasNext: false } }); }
  if (p === "/shop/products/facets") return send(res, 200, { brands: [], priceMinMinor: null, priceMaxMinor: null, total: n, audiences: [] });
  const m = /^\/shop\/products\/([^/]+)(\/related|\/together)?$/.exec(p);
  if (m) { const x = list.find((a) => a.slug === decodeURIComponent(m[1])); if (!x) return send(res, 404, { code: "NOT_FOUND", message: "no" }); if (m[2]) return send(res, 200, []); return send(res, 200, detail(x, lang)); }
  if (p === "/shop/categories" || p === "/shop/branches") return send(res, 200, []);
  if (p === "/shop/chat/config") return send(res, 200, { enabled: false });
  if (p === "/shop/experiments/active") return send(res, 200, {});
  if (p === "/analytics/collect") return send(res, 204);
  if (p === "/auth/me") return send(res, 401, { code: "UNAUTHORIZED" });
  if (p === "/shop/cart/quote") { req.resume(); req.on("end", () => send(res, 200, { subtotalMinor: 189900, discountMinor: 0, shippingMinor: 5000, codFeeMinor: 0, totalMinor: 194900, currency: "EGP", etaMinDays: 1, etaMaxDays: 3 })); return; }
  return send(res, 404, { code: "NOT_FOUND", message: p });
}).listen(PORT, () => console.log("mock backend on", PORT));
