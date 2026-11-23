"use client";
/* eslint-disable @next/next/no-img-element */

/**
 * Headless/shared client pieces of the large-catalog templates: URL-driven filter state, the facet panel contents, search suggestions, the buy
 * state of a product (same rules as the shop's ProductBuy: variant by option combo, stock, running offer price), gallery modes and the recently
 * viewed list. The look (layout, type, colour) is each template's own CSS; this file only carries behaviour, with plain `lg-*` hook classes.
 * Nothing here changes cart, checkout or payment logic: it calls the same lib/cart and API helpers the rest of the shop uses.
 */
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { addToCart } from "@/lib/cart";
import { api } from "@/lib/client";
import { picturesFor } from "@/lib/color-media";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import { Icon } from "@/components/icons";
import { useAction, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { PriceRange } from "@/components/price-range";
import type { Facets } from "@/components/shop/filters";
import type { CategoryNode } from "@/components/shop/types";
import type { ProductDetail } from "@/app/t-store/(shop)/(page)/products/[slug]/buy";

/* ------------------------------------------------------------------ filters */

export const FILTER_KEYS = ["q", "minPrice", "maxPrice", "brand", "inStock", "onSale", "minRating", "audience", "color", "size", "condition"] as const;

/** Every filter change rewrites the query string (page reset), so results stay shareable and the back button works. */
export function useFilterParams() {
  const router = useRouter();
  const path = usePathname();
  const sp = useSearchParams();
  const set = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(sp.toString());
    for (const [k, v] of Object.entries(patch)) { if (v === null || v === "") next.delete(k); else next.set(k, v); }
    next.delete("page");
    router.push(`${path}${next.toString() ? `?${next}` : ""}`, { scroll: false });
  };
  const list = (k: string) => (sp.get(k) ?? "").split(",").filter(Boolean);
  const toggle = (k: string, v: string) => { const cur = list(k); set({ [k]: (cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v]).join(",") }); };
  const active = FILTER_KEYS.filter((k) => k !== "q" && sp.get(k));
  const clear = () => set(Object.fromEntries(FILTER_KEYS.filter((k) => k !== "q").map((k) => [k, null])));
  return { sp, set, list, toggle, active, clear };
}

const containsSlug = (n: CategoryNode, slug: string): boolean => n.children.some((c) => c.slug === slug || containsSlug(c, slug));

function CatTree({ nodes, activeSlug, depth = 0 }: { nodes: CategoryNode[]; activeSlug: string; depth?: number }) {
  return (
    <ul className="lg-f-cats">
      {nodes.map((n) => (
        <li key={n.id}>
          <Link href={`/c/${n.slug}`} className="lg-f-cat" data-active={n.slug === activeSlug || undefined} style={{ paddingInlineStart: depth * 14 }}>
            <span>{n.name}</span><span className="lg-f-n">{n.productCount}</span>
          </Link>
          {n.children.length > 0 && (n.slug === activeSlug || containsSlug(n, activeSlug)) && <CatTree nodes={n.children} activeSlug={activeSlug} depth={depth + 1} />}
        </li>
      ))}
    </ul>
  );
}

/** The filter groups (categories, price, brand, audience, colour, size, availability, rating). Each template wraps it in its own container. */
export function FacetPanel({ tree, activeSlug, facets, currency, hideCategories }: { tree: CategoryNode[]; activeSlug: string; facets: Facets; currency: string; hideCategories?: boolean }) {
  const { t, locale } = useI18n();
  const { sp, set, list, toggle, active, clear } = useFilterParams();
  const brands = list("brand");
  const sizeScales = [...new Set((facets.sizes ?? []).map((x) => x.scale))];
  const group = (title: string, body: React.ReactNode, open = true) => <details className="lg-f-group" open={open}><summary>{title}<Icon name="chevron" className="lg-f-chev h-4 w-4" /></summary><div className="lg-f-body">{body}</div></details>;
  return (
    <div className="lg-facets">
      {!hideCategories && tree.length > 0 && group(t("shop.categories"), <><Link href="/products" className="lg-f-cat" data-active={!activeSlug || undefined}><span>{t("shop.all")}</span></Link><CatTree nodes={tree} activeSlug={activeSlug} /></>)}
      {facets.priceMinMinor != null && facets.priceMaxMinor != null && facets.priceMaxMinor > facets.priceMinMinor && group(`${t("filter.price")} (${currency})`,
        <PriceRange key={`${sp.get("minPrice")}-${sp.get("maxPrice")}`} min={facets.priceMinMinor} max={facets.priceMaxMinor} currency={currency} locale={locale} accent="var(--lg-primary)"
          value={[sp.get("minPrice") ? Number(sp.get("minPrice")) : null, sp.get("maxPrice") ? Number(sp.get("maxPrice")) : null]}
          onCommit={(lo, hi) => set({ minPrice: lo === null ? null : String(lo), maxPrice: hi === null ? null : String(hi) })} labels={{ min: t("filter.min"), max: t("filter.max") }} />)}
      {facets.brands.length > 0 && group(t("filter.brand"), <ul className="lg-f-checks">{facets.brands.map((b) => (
        <li key={b.brand}><label className="lg-f-check"><input type="checkbox" checked={brands.includes(b.brand)} onChange={() => toggle("brand", b.brand)} /><span>{b.brand}</span><span className="lg-f-n">{b.n}</span></label></li>))}</ul>)}
      {(facets.audiences?.length ?? 0) > 0 && group(t("filter.for"), <div className="lg-f-chips">{facets.audiences!.map((a) => <button key={a.audience} onClick={() => toggle("audience", a.audience)} className="lg-chip" data-active={list("audience").includes(a.audience) || undefined}>{t(`audience.${a.audience}`)}</button>)}</div>)}
      {(facets.colors?.length ?? 0) > 0 && group(t("filter.color"), <div className="lg-f-swatches">{facets.colors!.map((c) => {
        const name = locale === "ar" ? c.nameAr : c.nameEn;
        return <button key={c.code} onClick={() => toggle("color", c.code)} title={`${name} (${c.n})`} aria-label={name} aria-pressed={list("color").includes(c.code)} className="lg-swatch" style={{ background: c.hex ?? "conic-gradient(red, yellow, lime, aqua, blue, magenta, red)" }} />;
      })}</div>)}
      {sizeScales.length > 0 && group(t("filter.size"), <>{sizeScales.map((sc) => <div key={sc} className="lg-f-chips">{facets.sizes!.filter((x) => x.scale === sc).map((z) => <button key={z.code} onClick={() => toggle("size", z.code)} className="lg-chip" data-active={list("size").includes(z.code) || undefined}>{locale === "ar" ? z.nameAr : z.nameEn}</button>)}</div>)}</>)}
      {(facets.conditions?.length ?? 0) > 1 && group(t("filter.condition"), <div className="lg-f-chips">{facets.conditions!.map((c) => <button key={c.condition} onClick={() => toggle("condition", c.condition)} className="lg-chip" data-active={list("condition").includes(c.condition) || undefined}>{t(`condition.${c.condition}`)}</button>)}</div>)}
      {group(t("lg.availability"), <div className="lg-f-checks">
        <label className="lg-f-check"><input type="checkbox" checked={sp.get("inStock") === "true"} onChange={(e) => set({ inStock: e.target.checked ? "true" : null })} /><span>{t("filter.inStock")}</span></label>
        <label className="lg-f-check"><input type="checkbox" checked={sp.get("onSale") === "true"} onChange={(e) => set({ onSale: e.target.checked ? "true" : null })} /><span>{t("filter.onSale")}</span></label>
      </div>)}
      {group(t("filter.rating"), <div className="lg-f-chips">{[4, 3].map((r) => <button key={r} onClick={() => set({ minRating: sp.get("minRating") === String(r) ? null : String(r) })} className="lg-chip" data-active={sp.get("minRating") === String(r) || undefined}>★ {r}+</button>)}</div>, false)}
      {active.length > 0 && <button onClick={clear} className="lg-f-clear">{t("filter.clear")}</button>}
    </div>
  );
}

/** Removable chips for the filters that are on (market shows them above the grid, others inside their sheet). */
export function ActiveFilters({ currency }: { currency: string }) {
  const { t, locale } = useI18n();
  const { sp, set, active, clear } = useFilterParams();
  if (active.length === 0) return null;
  const label = (k: string) => {
    const v = sp.get(k) ?? "";
    if (k === "minPrice") return `≥ ${money(Number(v), currency, locale)}`;
    if (k === "maxPrice") return `≤ ${money(Number(v), currency, locale)}`;
    if (k === "inStock") return t("filter.inStock");
    if (k === "onSale") return t("filter.onSale");
    if (k === "minRating") return `★ ${v}+`;
    if (k === "audience") return v.split(",").map((a) => t(`audience.${a}`)).join(" · ");
    return v.split(",").join(" · ");
  };
  return (
    <div className="lg-active">
      {active.map((k) => <button key={k} className="lg-chip" data-active onClick={() => set({ [k]: null })} aria-label={`${t("common.delete")} ${label(k)}`}>{label(k)}<Icon name="close" className="h-3 w-3" /></button>)}
      <button className="lg-f-clear" onClick={clear}>{t("filter.clear")}</button>
    </div>
  );
}

const SORTS = ["newest", "popular", "price_asc", "price_desc", "discount"];
/** Sort as a native select (`select`) or as a row of links/buttons (`links`). */
export function SortControl({ mode = "select" }: { mode?: "select" | "links" }) {
  const { t } = useI18n();
  const { sp, set } = useFilterParams();
  const cur = sp.get("sort") ?? "newest";
  if (mode === "links") return <div className="lg-sort-links" role="group" aria-label={t("shop.sortBy")}>{SORTS.map((s) => <button key={s} onClick={() => set({ sort: s === "newest" ? null : s })} data-active={cur === s || undefined}>{t(`sort.${s}`)}</button>)}</div>;
  return <select className="lg-select" value={cur} onChange={(e) => set({ sort: e.target.value === "newest" ? null : e.target.value })} aria-label={t("shop.sortBy")}>{SORTS.map((s) => <option key={s} value={s}>{t(`sort.${s}`)}</option>)}</select>;
}

/* ------------------------------------------------------------------ search */

export type Suggest = { products: { id: string; name: string; slug: string; minPriceMinor: number; currency: string; imageUrl?: string; imageMediaBase?: string; imageMediaExt?: string }[]; categories: { name: string; nameEn?: string; slug: string }[] };

/** Search-as-you-type results for a query (debounced). The full results page is /products?q=. */
export function useSuggest(q: string) {
  const [res, setRes] = useState<Suggest | null>(null);
  useEffect(() => {
    if (q.trim().length < 2) { const id = setTimeout(() => setRes(null), 0); return () => clearTimeout(id); }
    let alive = true;
    const id = setTimeout(() => { api<Suggest>(`shop/search/suggest?q=${encodeURIComponent(q.trim())}`).then((r) => alive && setRes(r)).catch(() => alive && setRes(null)); }, 220);
    return () => { alive = false; clearTimeout(id); };
  }, [q]);
  return res;
}

/** The search form + live results list. `className` styles the form; results render in `.lg-suggest` that the template positions. */
export function SearchField({ className = "", autoFocus, onDone, big }: { className?: string; autoFocus?: boolean; onDone?: () => void; big?: boolean }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [q, setQ] = useState("");
  const res = useSuggest(q);
  const has = res && (res.products.length > 0 || res.categories.length > 0);
  const go = (e?: React.FormEvent) => { e?.preventDefault(); onDone?.(); router.push(q.trim() ? `/products?q=${encodeURIComponent(q.trim())}` : "/products"); };
  return (
    <div className={`lg-search ${className}`} data-big={big || undefined}>
      <form onSubmit={go} role="search">
        <Icon name="search" className="lg-search-ico h-[18px] w-[18px]" />
        <input value={q} onChange={(e) => setQ(e.target.value)} autoFocus={autoFocus} placeholder={t("shop.search")} aria-label={t("shop.search")} autoComplete="off" />
        {q && <button type="button" className="lg-search-x" onClick={() => setQ("")} aria-label={t("common.close")}><Icon name="close" className="h-4 w-4" /></button>}
      </form>
      {q.trim().length >= 2 && (
        <div className="lg-suggest">
          {has ? <>
            {res.categories.length > 0 && <div className="lg-suggest-cats">{res.categories.map((c) => <Link key={c.slug} href={`/c/${c.slug}`} onClick={onDone} className="lg-chip">{locale === "en" && c.nameEn ? c.nameEn : c.name}</Link>)}</div>}
            <ul>{res.products.map((p) => (
              <li key={p.id}><Link href={`/products/${p.slug}`} onClick={onDone}>
                {(p.imageUrl || p.imageMediaBase) ? <img src={mediaUrl({ url: p.imageUrl, mediaBase: p.imageMediaBase, mediaExt: p.imageMediaExt }, "thumb") ?? ""} alt="" /> : <span className="lg-suggest-ph"><Icon name="bag" className="h-5 w-5" /></span>}
                <span className="lg-suggest-name">{p.name}</span><span className="lg-suggest-price">{money(p.minPriceMinor, p.currency, locale)}</span>
              </Link></li>))}</ul>
            <button className="lg-suggest-all" onClick={() => go()}>{t("shop.seeAllResults")}</button>
          </> : res ? <p className="lg-suggest-none">{t("shop.noResults")}</p> : null}
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ buying */

export type BuyState = ReturnType<typeof useBuy>;

/** Everything a product page needs to sell one item: option choice, variant, stock, price (offer-aware), quantity, add to cart / buy now, wishlist. */
export function useBuy(product: ProductDetail, isOpen: boolean) {
  const { locale } = useI18n();
  const router = useRouter();
  const { me } = useMe();
  const [choice, setChoice] = useState<Record<string, string>>(() => Object.fromEntries(product.options.map((o) => [o.name, o.values[0]])));
  const [qty, setQty] = useState(1);
  const [note, setNote] = useState("");
  const [added, setAdded] = useState(false);
  const combo = product.options.map((o) => `${o.name}=${choice[o.name]}`).join("|");
  const variant = useMemo(() => product.variants.find((v) => v.comboKey === combo), [product.variants, combo]);
  const available = variant?.available ?? 0;
  const inStock = available > 0;
  const sale = variant?.salePriceMinor !== undefined && variant.salePriceMinor < variant.priceMinor ? variant.salePriceMinor : null;
  const price = sale ?? variant?.priceMinor ?? 0;
  const was = sale !== null ? variant!.priceMinor : variant?.compareAtPriceMinor;
  const discount = was && was > price ? Math.round(((was - price) * 100) / was) : 0;
  const optLabel = (o: ProductDetail["options"][number]) => (locale === "ar" ? o.labelAr : o.labelEn) ?? o.name;
  const valLabel = (o: ProductDetail["options"][number], v: string) => { const m = o.valueMeta?.find((x) => x.value === v); return m ? (locale === "ar" ? m.nameAr : m.nameEn) : v; };
  const label = product.options.map((o) => valLabel(o, choice[o.name])).join(" / ");
  const shown = useMemo(() => picturesFor(product.media, Object.values(choice)), [product.media, choice]);
  const pick = (name: string, v: string) => { setChoice({ ...choice, [name]: v }); setQty(1); };
  /** whether a value can still be bought given the other chosen options (for struck-through sizes) */
  const valueAvailable = (name: string, v: string) => {
    const probe = product.options.map((o) => `${o.name}=${o.name === name ? v : choice[o.name]}`).join("|");
    const found = product.variants.find((x) => x.comboKey === probe);
    return !found || found.available > 0;
  };
  const wish = useAction(async () => {
    if (!me) { router.push(`/login?next=${encodeURIComponent(`/products/${product.slug}`)}`); return; }
    await api(`shop/wishlist/${product.id}`, { method: "PUT" });
  });
  const add = (go?: boolean) => {
    if (!variant || !isOpen) return;
    addToCart({ variantId: variant.id, productId: product.id, slug: product.slug, name: product.name, variantLabel: label, unitPriceMinor: variant.priceMinor, quantity: qty, imageUrl: mediaUrl(shown[0], "thumb"), note: note.trim() || undefined });
    setNote("");
    if (go) { router.push("/checkout"); return; }
    setAdded(true);
    setTimeout(() => setAdded(false), 2500);
  };
  return { choice, pick, qty, setQty, note, setNote, variant, available, inStock, sale, price, was, discount, optLabel, valLabel, valueAvailable, label, shown, wish, add, added, isOpen };
}

export function Qty({ value, onChange, max }: { value: number; onChange: (n: number) => void; max: number }) {
  const { t } = useI18n();
  return (
    <div className="lg-qty" role="group" aria-label={t("lg.quantity")}>
      <button type="button" onClick={() => onChange(Math.max(1, value - 1))} aria-label="-"><Icon name="minus" className="h-4 w-4" /></button>
      <span aria-live="polite">{value}</span>
      <button type="button" onClick={() => onChange(Math.min(Math.max(max, 1), value + 1))} aria-label="+"><Icon name="plus" className="h-4 w-4" /></button>
    </div>
  );
}

/** The option pickers (colour dots, size chips). Sold-out values stay selectable but are marked, so the shopper sees why the button is disabled. */
export function Options({ buy, product, shape = "pill" }: { buy: BuyState; product: ProductDetail; shape?: "pill" | "square" | "line" }) {
  return (
    <>
      {product.options.map((o) => (
        <div key={o.name} className="lg-opt">
          <p className="lg-opt-title">{buy.optLabel(o)}<span>{buy.valLabel(o, buy.choice[o.name])}</span></p>
          <div className="lg-opt-values" data-shape={shape}>
            {o.values.map((v) => {
              const hex = o.valueMeta?.find((x) => x.value === v)?.hex;
              const on = buy.choice[o.name] === v;
              const ok = buy.valueAvailable(o.name, v);
              return o.attribute === "COLOR"
                ? <button key={v} onClick={() => buy.pick(o.name, v)} title={buy.valLabel(o, v)} aria-label={buy.valLabel(o, v)} aria-pressed={on} data-sold={!ok || undefined} className="lg-dot" style={{ background: hex || "conic-gradient(red, yellow, lime, aqua, blue, magenta, red)" }} />
                : <button key={v} onClick={() => buy.pick(o.name, v)} aria-pressed={on} data-sold={!ok || undefined} className="lg-val">{buy.valLabel(o, v)}</button>;
            })}
          </div>
        </div>
      ))}
    </>
  );
}

/* ------------------------------------------------------------------ gallery */

type M = { url?: string | null; altText?: string; mediaBase?: string | null; mediaExt?: string | null };
export type GalleryMode = "stack" | "grid" | "thumbs" | "carousel" | "single";

/** Product pictures in one of five arrangements. `badge` sits on the first picture. A product without pictures shows a quiet placeholder. */
export function LgGallery({ media, name, mode, badge }: { media: M[]; name: string; mode: GalleryMode; badge?: React.ReactNode }) {
  const { t } = useI18n();
  const [i, setI] = useState(0);
  const [zoom, setZoom] = useState<{ x: number; y: number } | null>(null);
  const src = (m: M, v: "thumb" | "medium" | "original" = "medium") => mediaUrl(m, v) ?? "";
  if (media.length === 0) return <div className="lg-gal" data-mode={mode}><div className="lg-gal-ph"><Icon name="bag" className="h-12 w-12" /></div><div className="lg-gal-badge">{badge}</div></div>;
  if (mode === "stack" || mode === "grid") {
    return <div className="lg-gal" data-mode={mode}>{media.map((m, k) => <div key={k} className="lg-gal-item"><img src={src(m, "original")} alt={m.altText || name} loading={k === 0 ? "eager" : "lazy"} />{k === 0 && <div className="lg-gal-badge">{badge}</div>}</div>)}</div>;
  }
  if (mode === "carousel") {
    return (
      <div className="lg-gal" data-mode="carousel">
        <div className="lg-gal-track" onScroll={(e) => { const el = e.currentTarget; setI(Math.round(Math.abs(el.scrollLeft) / el.clientWidth)); }}>
          {media.map((m, k) => <div key={k} className="lg-gal-slide"><img src={src(m)} alt={m.altText || name} loading={k === 0 ? "eager" : "lazy"} /></div>)}
        </div>
        <div className="lg-gal-badge">{badge}</div>
        {media.length > 1 && <div className="lg-gal-dots" aria-hidden>{media.map((_, k) => <span key={k} data-on={k === i || undefined} />)}</div>}
      </div>
    );
  }
  const cur = media[Math.min(i, media.length - 1)];
  return (
    <div className="lg-gal" data-mode={mode}>
      {mode === "thumbs" && media.length > 1 && (
        <div className="lg-gal-thumbs">{media.map((m, k) => <button key={k} onClick={() => setI(k)} data-on={k === i || undefined} aria-label={`${t("lg.picture")} ${k + 1}`}><img src={src(m, "thumb")} alt="" /></button>)}</div>
      )}
      <div className="lg-gal-main" onMouseMove={mode === "thumbs" ? (e) => { const r = e.currentTarget.getBoundingClientRect(); setZoom({ x: ((e.clientX - r.left) / r.width) * 100, y: ((e.clientY - r.top) / r.height) * 100 }); } : undefined} onMouseLeave={() => setZoom(null)}>
        <img src={src(cur)} alt={cur.altText || name} style={zoom ? { transform: "scale(1.8)", transformOrigin: `${zoom.x}% ${zoom.y}%` } : undefined} />
        <div className="lg-gal-badge">{badge}</div>
        {media.length > 1 && mode === "single" && <>
          <button className="lg-gal-prev" onClick={() => setI((i - 1 + media.length) % media.length)} aria-label={t("lg.previous")}><Icon name="chevron" className="h-5 w-5 rotate-90 rtl:-rotate-90" /></button>
          <button className="lg-gal-next" onClick={() => setI((i + 1) % media.length)} aria-label={t("lg.next")}><Icon name="chevron" className="h-5 w-5 -rotate-90 rtl:rotate-90" /></button>
          <div className="lg-gal-dots">{media.map((_, k) => <button key={k} data-on={k === i || undefined} onClick={() => setI(k)} aria-label={`${t("lg.picture")} ${k + 1}`} />)}</div>
        </>}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ recently viewed */

type Seen = { slug: string; name: string; priceMinor: number; currency: string; img: string | null };
const RV_KEY = "rv:v1", RV_EVENT = "rv:changed";
const NONE: Seen[] = [];
let rvCache: { raw: string | null; items: Seen[] } = { raw: null, items: NONE };
function readSeen(): Seen[] {
  try {
    const raw = localStorage.getItem(RV_KEY);
    if (raw === rvCache.raw) return rvCache.items;
    rvCache = { raw, items: raw ? (JSON.parse(raw) as Seen[]) : NONE };
    return rvCache.items;
  } catch { return NONE; }
}
const subSeen = (cb: () => void) => { window.addEventListener(RV_EVENT, cb); return () => window.removeEventListener(RV_EVENT, cb); };

/** Records the product being viewed (this browser only) and lists the others viewed before it. Renders nothing until there is something to show. */
export function RecentlyViewed({ current, heading }: { current?: Seen; heading: string }) {
  const { locale } = useI18n();
  const items = useSyncExternalStore(subSeen, readSeen, () => NONE);
  const slug = current?.slug;
  useEffect(() => {
    if (!current) return;
    try {
      const next = [current, ...readSeen().filter((x) => x.slug !== current.slug)].slice(0, 12);
      localStorage.setItem(RV_KEY, JSON.stringify(next));
      window.dispatchEvent(new Event(RV_EVENT));
    } catch { /* private mode: nothing remembered */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slug]);
  const others = items.filter((x) => x.slug !== slug).slice(0, 8);
  if (others.length === 0) return null;
  return (
    <section className="lg-rv" aria-label={heading}>
      <h2 className="lg-h2">{heading}</h2>
      <ul className="lg-rv-list">{others.map((x) => (
        <li key={x.slug}><Link href={`/products/${x.slug}`} className="lg-rv-item">
          <span className="lg-rv-img">{x.img ? <img src={x.img} alt="" loading="lazy" /> : <Icon name="bag" className="h-6 w-6" />}</span>
          <span className="lg-rv-name">{x.name}</span><span className="lg-rv-price">{money(x.priceMinor, x.currency, locale)}</span>
        </Link></li>))}</ul>
    </section>
  );
}

export function Stars({ value, count }: { value: number; count?: number }) {
  const full = Math.round(value);
  return <span className="lg-stars" aria-label={`${value} / 5`}><span aria-hidden>{"★".repeat(full)}<i>{"★".repeat(5 - full)}</i></span>{count !== undefined && <small>({count})</small>}</span>;
}
