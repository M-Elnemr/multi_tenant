"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";
import { money } from "@/lib/format";
import type { CategoryNode } from "./types";

export type Facets = {
  brands: { brand: string; n: number }[]; priceMinMinor: number | null; priceMaxMinor: number | null; total: number;
  audiences?: { audience: string; n: number }[]; colors?: { code: string; nameAr: string; nameEn: string; hex: string | null; n: number }[];
  sizes?: { scale: string; code: string; nameAr: string; nameEn: string; n: number }[]; conditions?: { condition: string; n: number }[];
};

/** URL-driven filters: every change rewrites the query string (page reset to 1), so results are shareable and the back button works. */
function useFilterParams() {
  const router = useRouter();
  const path = usePathname();
  const sp = useSearchParams();
  const set = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(sp.toString());
    for (const [k, v] of Object.entries(patch)) { if (v === null || v === "") next.delete(k); else next.set(k, v); }
    next.delete("page");
    router.push(`${path}${next.toString() ? `?${next}` : ""}`, { scroll: false });
  };
  return { sp, set };
}

export function SortSelect() {
  const { t } = useI18n();
  const { sp, set } = useFilterParams();
  return (
    <select value={sp.get("sort") ?? "newest"} onChange={(e) => set({ sort: e.target.value === "newest" ? null : e.target.value })} aria-label={t("shop.sortBy")} className="s-input !w-auto !rounded-full !py-2 !pe-8 text-sm font-semibold">
      {["newest", "popular", "price_asc", "price_desc", "discount"].map((s) => <option key={s} value={s}>{t(`sort.${s}`)}</option>)}
    </select>
  );
}

function CatTree({ nodes, activeSlug, depth = 0 }: { nodes: CategoryNode[]; activeSlug: string; depth?: number }) {
  return (
    <ul className="space-y-0.5">
      {nodes.map((n) => {
        const open = n.slug === activeSlug || containsSlug(n, activeSlug);
        return (
          <li key={n.id}>
            <Link href={`/c/${n.slug}`} className={`flex items-center justify-between rounded-xl px-3 py-1.5 text-sm transition hover:bg-[var(--s-soft)] ${n.slug === activeSlug ? "font-extrabold" : "font-medium"}`} style={{ paddingInlineStart: 12 + depth * 14, color: n.slug === activeSlug ? "var(--brand)" : undefined }}>
              <span>{n.name}</span><span className="text-xs text-[var(--s-mute)]">{n.productCount}</span>
            </Link>
            {n.children.length > 0 && open && <CatTree nodes={n.children} activeSlug={activeSlug} depth={depth + 1} />}
          </li>
        );
      })}
    </ul>
  );
}
const containsSlug = (n: CategoryNode, slug: string): boolean => n.children.some((c) => c.slug === slug || containsSlug(c, slug));

function Panel({ tree, activeSlug, facets, currency }: { tree: CategoryNode[]; activeSlug: string; facets: Facets; currency: string }) {
  const { t, locale } = useI18n();
  const { sp, set } = useFilterParams();
  const [min, setMin] = useState(sp.get("minPrice") ? String(Number(sp.get("minPrice")) / 100) : "");
  const [max, setMax] = useState(sp.get("maxPrice") ? String(Number(sp.get("maxPrice")) / 100) : "");
  const brands = (sp.get("brand") ?? "").split(",").filter(Boolean);
  const toggleBrand = (b: string) => set({ brand: (brands.includes(b) ? brands.filter((x) => x !== b) : [...brands, b]).join(",") });
  const list = (key: string) => (sp.get(key) ?? "").split(",").filter(Boolean);
  const toggle = (key: string, v: string) => { const cur = list(key); set({ [key]: (cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v]).join(",") }); };
  const minor = (v: string) => (v.trim() && !Number.isNaN(Number(v)) ? String(Math.round(Number(v) * 100)) : null);
  return (
    <div className="space-y-7">
      <div>
        <h3 className="mb-2 text-sm font-extrabold">{t("shop.categories")}</h3>
        <Link href="/products" className={`mb-1 block rounded-xl px-3 py-1.5 text-sm hover:bg-[var(--s-soft)] ${activeSlug ? "font-medium" : "font-extrabold"}`} style={!activeSlug ? { color: "var(--brand)" } : undefined}>{t("shop.all")}</Link>
        <CatTree nodes={tree} activeSlug={activeSlug} />
      </div>
      <div>
        <h3 className="mb-2 text-sm font-extrabold">{t("filter.price")} ({currency})</h3>
        <div className="flex items-center gap-2">
          <input value={min} onChange={(e) => setMin(e.target.value)} inputMode="decimal" dir="ltr" placeholder={facets.priceMinMinor != null ? String(Math.floor(facets.priceMinMinor / 100)) : "0"} className="s-input !py-2 text-center" aria-label={t("filter.min")} />
          <span className="text-[var(--s-mute)]">–</span>
          <input value={max} onChange={(e) => setMax(e.target.value)} inputMode="decimal" dir="ltr" placeholder={facets.priceMaxMinor != null ? String(Math.ceil(facets.priceMaxMinor / 100)) : "∞"} className="s-input !py-2 text-center" aria-label={t("filter.max")} />
        </div>
        {facets.priceMinMinor != null && facets.priceMaxMinor != null && <p className="mt-1.5 text-xs text-[var(--s-mute)]">{money(facets.priceMinMinor, currency, locale)} – {money(facets.priceMaxMinor, currency, locale)}</p>}
        <button onClick={() => set({ minPrice: minor(min), maxPrice: minor(max) })} className="s-btn s-btn-ghost s-btn-sm mt-2 w-full">{t("filter.apply")}</button>
      </div>
      {facets.brands.length > 0 && (
        <div>
          <h3 className="mb-2 text-sm font-extrabold">{t("filter.brand")}</h3>
          <ul className="max-h-56 space-y-1 overflow-y-auto">
            {facets.brands.map((b) => (
              <li key={b.brand}><label className="flex cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm hover:bg-[var(--s-soft)]"><input type="checkbox" checked={brands.includes(b.brand)} onChange={() => toggleBrand(b.brand)} className="h-4 w-4 accent-[var(--brand)]" /><span className="flex-1">{b.brand}</span><span className="text-xs text-[var(--s-mute)]">{b.n}</span></label></li>
            ))}
          </ul>
        </div>
      )}
      {(facets.audiences?.length ?? 0) > 0 && (
        <div>
          <h3 className="mb-2 text-sm font-extrabold">{t("filter.for")}</h3>
          <div className="flex flex-wrap gap-1.5">{facets.audiences!.map((a) => <button key={a.audience} onClick={() => toggle("audience", a.audience)} className="s-chip" data-active={list("audience").includes(a.audience)}>{t(`audience.${a.audience}`)} <span className="opacity-60">{a.n}</span></button>)}</div>
        </div>
      )}
      {(facets.colors?.length ?? 0) > 0 && (
        <div>
          <h3 className="mb-2 text-sm font-extrabold">{t("filter.color")}</h3>
          <div className="flex flex-wrap gap-2">{facets.colors!.map((c) => {
            const on = list("color").includes(c.code);
            return (
              <button key={c.code} onClick={() => toggle("color", c.code)} title={`${locale === "ar" ? c.nameAr : c.nameEn} (${c.n})`} aria-label={locale === "ar" ? c.nameAr : c.nameEn} aria-pressed={on}
                className={`h-8 w-8 rounded-full border transition ${on ? "ring-2 ring-[var(--brand)] ring-offset-2" : "hover:scale-110"} ${c.code === "white" ? "border-slate-300" : "border-black/10"}`}
                style={{ background: c.hex ?? "conic-gradient(red, yellow, lime, aqua, blue, magenta, red)" }} />
            );
          })}</div>
        </div>
      )}
      {(facets.sizes?.length ?? 0) > 0 && (
        <div>
          <h3 className="mb-2 text-sm font-extrabold">{t("filter.size")}</h3>
          {[...new Set(facets.sizes!.map((x) => x.scale))].map((scale) => (
            <div key={scale} className="mb-2">
              {new Set(facets.sizes!.map((x) => x.scale)).size > 1 && <p className="mb-1 text-xs text-[var(--s-mute)]">{t(`scale.${scale}`)}</p>}
              <div className="flex flex-wrap gap-1.5">{facets.sizes!.filter((x) => x.scale === scale).map((z) => <button key={z.code} onClick={() => toggle("size", z.code)} className="s-chip !px-3" data-active={list("size").includes(z.code)}>{locale === "ar" ? z.nameAr : z.nameEn}</button>)}</div>
            </div>
          ))}
        </div>
      )}
      {(facets.conditions?.length ?? 0) > 1 && (
        <div>
          <h3 className="mb-2 text-sm font-extrabold">{t("filter.condition")}</h3>
          <div className="flex flex-wrap gap-1.5">{facets.conditions!.map((c) => <button key={c.condition} onClick={() => toggle("condition", c.condition)} className="s-chip" data-active={list("condition").includes(c.condition)}>{t(`condition.${c.condition}`)} <span className="opacity-60">{c.n}</span></button>)}</div>
        </div>
      )}
      <div className="space-y-2">
        <label className="flex cursor-pointer items-center gap-2.5 text-sm font-semibold"><input type="checkbox" checked={sp.get("inStock") === "true"} onChange={(e) => set({ inStock: e.target.checked ? "true" : null })} className="h-4 w-4 accent-[var(--brand)]" />{t("filter.inStock")}</label>
        <label className="flex cursor-pointer items-center gap-2.5 text-sm font-semibold"><input type="checkbox" checked={sp.get("onSale") === "true"} onChange={(e) => set({ onSale: e.target.checked ? "true" : null })} className="h-4 w-4 accent-[var(--brand)]" />{t("filter.onSale")}</label>
      </div>
      <div>
        <h3 className="mb-2 text-sm font-extrabold">{t("filter.rating")}</h3>
        <div className="flex flex-wrap gap-1.5">
          {[4, 3].map((r) => <button key={r} onClick={() => set({ minRating: sp.get("minRating") === String(r) ? null : String(r) })} className="s-chip" data-active={sp.get("minRating") === String(r)}>★ {r}+</button>)}
        </div>
      </div>
      {["q", "minPrice", "maxPrice", "brand", "inStock", "onSale", "minRating", "audience", "color", "size", "condition"].some((k) => sp.get(k)) && (
        <button onClick={() => set({ q: null, minPrice: null, maxPrice: null, brand: null, inStock: null, onSale: null, minRating: null, audience: null, color: null, size: null, condition: null })} className="w-full text-center text-sm font-bold text-[#e5484d] hover:underline">{t("filter.clear")}</button>
      )}
    </div>
  );
}

type PanelProps = { tree: CategoryNode[]; activeSlug: string; facets: Facets; currency: string };

/** Desktop: a sticky sidebar next to the results. */
export function FilterSidebar(props: PanelProps) {
  return <aside className="s-card sticky top-28 hidden max-h-[calc(100vh-8rem)] w-72 shrink-0 self-start overflow-y-auto p-5 lg:block"><Panel {...props} /></aside>;
}

/** Phones: a "Filters" button that opens the same panel as a bottom sheet. */
export function FilterSheet(props: PanelProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  return (
    <div className="lg:hidden">
      <button onClick={() => setOpen(true)} className="s-btn s-btn-ghost s-btn-sm"><Icon name="menu" className="h-4 w-4" />{t("filter.title")}</button>
      {open && (
        <div className="fixed inset-0 z-50" role="dialog" aria-modal>
          <div className="absolute inset-0 animate-fade-in bg-black/40" onClick={() => setOpen(false)} />
          <div className="animate-sheet-up absolute inset-x-0 bottom-0 max-h-[88vh] overflow-y-auto rounded-t-[28px] bg-[var(--s-bg)] p-5 pb-8">
            <div className="mb-4 flex items-center justify-between"><h2 className="text-lg font-extrabold">{t("filter.title")}</h2><button onClick={() => setOpen(false)} aria-label={t("common.close")} className="grid h-10 w-10 place-items-center rounded-full hover:bg-[var(--s-soft)]"><Icon name="close" /></button></div>
            <Panel {...props} />
            <button onClick={() => setOpen(false)} className="s-btn mt-6 w-full">{t("filter.show")}</button>
          </div>
        </div>
      )}
    </div>
  );
}
