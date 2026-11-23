// Scripted browser checks for the large-catalog templates (no browser extension needed): headless Chrome over the DevTools protocol.
// Usage: node drive.mjs <tpl> <lang-label> <mode: shots|flow|both> [outDir]
// Needs the dev server on :3000 (host shop.localhost) and the mock backend. Uses Node's built-in WebSocket and fetch.
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";

const [tpl, lang, mode = "both", outArg] = process.argv.slice(2);
const OUT = outArg ?? new URL("../shots/ws-c/", import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });
const BASE = "http://shop.localhost:3000";
const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PORT = 9400 + Math.floor(Math.random() * 400);
// per-template hooks (class names that exist in that template's markup)
const SEL = {
  "sn-market": { menu: ".mk-burger", find: ".mk-find", filter: ".mk-filter-btn", add: ".mk-add", closeOver: ".mk-scrim", overlay: ".mk-drawer-wrap" },
  "sn-editorial": { menu: ".ed-side .ed-word:nth-child(1)", find: ".ed-side .ed-word:nth-child(2)", filter: ".ed-tools .ed-word", add: ".ed-buyrow .ed-btn", closeOver: ".ed-over-top .ed-word", overlay: ".ed-over" },
  "sn-minimal": { menu: ".mn-menu", find: ".mn-find", filter: ".mn-filter-btn", add: ".mn-add", closeOver: ".mn-over-close", overlay: ".mn-over" },
  "sn-bold": { menu: ".bd-menu", find: ".bd-find", filter: ".bd-filter-btn", add: ".bd-add", closeOver: ".bd-scrim", overlay: ".bd-drawer-wrap" },
  "sn-lifestyle": { menu: ".lf-menu", find: ".lf-find", filter: ".lf-filter-btn", add: ".lf-add", closeOver: ".lf-over-close", overlay: ".lf-over" },
}[tpl];
if (!SEL) throw new Error("unknown template " + tpl);

const chrome = spawn(CHROME, ["--headless=new", "--disable-gpu", "--hide-scrollbars", `--remote-debugging-port=${PORT}`, "--user-data-dir=/tmp/claude-501/cdp-profile-" + PORT, "about:blank"], { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let targets;
for (let i = 0; i < 50; i++) { try { targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json(); if (targets.length) break; } catch { /* not up yet */ } await sleep(200); }
const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let id = 0; const waits = new Map(); const errors = [];
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && waits.has(d.id)) { waits.get(d.id)(d); waits.delete(d.id); }
  if (d.method === "Runtime.exceptionThrown") errors.push(d.params.exceptionDetails.exception?.description ?? d.params.exceptionDetails.text);
  if (d.method === "Runtime.consoleAPICalled" && d.params.type === "error") errors.push(d.params.args.map((a) => a.value ?? a.description).join(" ").slice(0, 300));
};
const send = (method, params = {}) => new Promise((r) => { const i = ++id; waits.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => { const r = await send("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true }); return r.result?.result?.value; };
await send("Page.enable"); await send("Runtime.enable");

const log = []; const check = (name, ok, extra = "") => { log.push(`${ok ? "PASS" : "FAIL"} ${name} ${extra}`); };
const view = (w, h = 900) => send("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 1, mobile: w < 700 });
const go = async (path) => { await send("Page.navigate", { url: `${BASE}${path}${path.includes("?") ? "&" : "?"}tpl=${tpl}` }); await sleep(2500); };
const shot = async (name, full = true) => {
  const r = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: full, fromSurface: true });
  writeFileSync(`${OUT}/${tpl}-${name}.png`, Buffer.from(r.result.data, "base64"));
};
const click = (sel) => ev(`(()=>{const e=document.querySelector(${JSON.stringify(sel)});if(!e)return false;e.click();return true})()`);
const exists = (sel) => ev(`!!document.querySelector(${JSON.stringify(sel)})`);
const setLang = async () => { await ev(`document.cookie="lang=${lang};path=/;max-age=3600"`); };

await send("Network.enable");
await go("/"); await setLang();
// a product that exists in the running mock (the few-products mode has only three)
const PDP = await fetch("http://localhost:18080/api/v1/shop/products?pageSize=40").then((r) => r.json()).then((d) => { const p = d.data.find((x) => x.slug.startsWith("p38-")) ?? d.data.find((x) => x.inStock) ?? d.data[0]; return "/products/" + p.slug; }).catch(() => "/products/p38-satin-evening-dress");
const PAGES =[["home", "/"], ["list", "/products"], ["cat", "/c/dresses"], ["pdp", PDP], ["cart", "/cart"], ["search", "/products?q=%D9%81%D8%B3%D8%AA%D8%A7%D9%86"], ["policy", "/p/returns"], ["contact", "/contact"]];

if (mode === "shots" || mode === "both") {
  // ONLY=home,list limits the pages; WIDTHS=390,1280 limits the widths; TAG=-few adds a suffix to the file names (edge-case runs)
  const only = process.env.ONLY?.split(",");
  const widths = (process.env.WIDTHS ?? "390,768,1280").split(",").map(Number);
  const tag = process.env.TAG ?? "";
  for (const w of widths) {
    await view(w);
    for (const [name, path] of PAGES.filter(([n]) => !only || only.includes(n))) { await go(path); await shot(`${name}${tag}-${lang}-${w}`); }
  }
  check("no page errors while shooting", errors.length === 0, errors.slice(0, 3).join(" | "));
}

if (mode === "flow" || mode === "both") {
  // phone: menu / search overlays
  await view(390); await ev("localStorage.clear()"); await go("/");
  check("menu button exists", await exists(SEL.menu));
  await click(SEL.menu); await sleep(500);
  check("menu overlay opens", await exists(SEL.overlay)); await shot(`flow-menu-${lang}-390`, false);
  await click(SEL.closeOver); await sleep(300);
  check("menu overlay closes", !(await exists(SEL.overlay)));
  if (await exists(SEL.find)) {
    await click(SEL.find); await sleep(400);
    await ev(`(()=>{const i=document.querySelector('.lg-search input');if(!i)return;const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(i,'${lang === "ar" ? "فستان" : "dress"}');i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
    await sleep(1500);
    check("search suggestions appear", await exists(".lg-suggest li"), String(await ev("document.querySelectorAll('.lg-suggest li').length")));
    await shot(`flow-suggest-${lang}-390`, false);
  } else check("search field visible in header", await exists(".lg-search input"));
  // listing: filter panel and sort
  await go("/products");
  const fb = await click(SEL.filter); await sleep(600);
  const vis = await ev(`(()=>{const e=[...document.querySelectorAll(".lg-facets")].find(x=>x.getBoundingClientRect().width>0&&x.getBoundingClientRect().height>0);if(!e)return "none";const r=e.getBoundingClientRect();return Math.round(r.top)+","+Math.round(r.width)})()`);
  check("filter control opens a VISIBLE facet panel", fb && vis !== "none", String(vis));
  await shot(`flow-filter-${lang}-390`, false);
  await ev(`(()=>{const c=document.querySelector('.lg-f-check input');if(c)c.click()})()`); await sleep(1800);
  check("brand filter changes url", (await ev("location.search")).includes("brand="), await ev("location.search"));
  await ev(`(()=>{const s=document.querySelector('select.lg-select');if(s){s.value='price_asc';s.dispatchEvent(new Event('change',{bubbles:true}))}else{const b=document.querySelectorAll('.lg-sort-links button')[2];b&&b.click()}})()`); await sleep(1800);
  check("sort changes url", (await ev("location.search")).includes("sort="), await ev("location.search"));
  // product: pick option, add to cart
  await go(PDP);
  const before = await ev("document.querySelector('.lg-price-now')?.textContent");
  await ev(`(()=>{const v=document.querySelectorAll('.lg-opt-values .lg-val');if(v[1])v[1].click()})()`); await sleep(400);
  check("size option selects", (await ev("document.querySelectorAll('.lg-val[aria-pressed=\"true\"]').length")) >= 1);
  await shot(`flow-pdp-${lang}-390`, false);
  await click(SEL.add); await sleep(800);
  const cart = await ev("JSON.stringify(Object.entries(localStorage).filter(([k])=>/cart/i.test(k)))");
  check("add to cart writes the cart", /variantId|"quantity"|quantity/.test(cart || ""), (cart || "").slice(0, 80));
  check("cart count shows in header", /\(?1\)?/.test((await ev("document.querySelector('.lg-cart, .ed-bag, .mn-bag, .bd-bag, .lf-bag')?.getAttribute('aria-label')")) || ""), String(await ev("document.querySelector('.lg-cart, .ed-bag, .mn-bag, .bd-bag, .lf-bag')?.getAttribute('aria-label')")));
  void before;
  // filled cart
  await go("/cart");
  check("cart lists the line", (await ev(`document.body.innerText.includes('${lang === "ar" ? "ساتان" : "Satin"}')`)));
  for (const w of [390, 1280]) { await view(w); await go("/cart"); await shot(`cart-filled-${lang}-${w}`); }
  check("no uncaught errors during flow", errors.length === 0, errors.slice(0, 3).join(" | "));
}

console.log(log.join("\n"));
ws.close(); chrome.kill();
process.exit(0);
