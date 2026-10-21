"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { GOVERNORATES } from "@/lib/governorates";
import { money } from "@/lib/format";
import type { OpenFacets } from "@/lib/open-types";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";

type Fixed = { category?: string; shop?: string };

/** Filters live in the URL (shareable, back-button friendly); every change resets to page 1. */
function useParamsEditor(basePath: string) {
  const router = useRouter();
  const sp = useSearchParams();
  const qs = (mut: (p: URLSearchParams) => void, path = basePath) => {
    const p = new URLSearchParams(sp.toString());
    mut(p);
    p.delete("page");
    const s = p.toString();
    return s ? `${path}?${s}` : path;
  };
  const set = (updates: Record<string, string | null>) => router.push(qs((p) => { for (const [k, v] of Object.entries(updates)) { if (v === null || v === "") p.delete(k); else p.set(k, v); } }), { scroll: false });
  const toggleCsv = (key: string, value: string) => {
    const cur = (sp.get(key) ?? "").split(",").filter(Boolean);
    const next = cur.includes(value) ? cur.filter((x) => x !== value) : [...cur, value];
    set({ [key]: next.join(",") || null });
  };
  return { sp, set, toggleCsv, qs };
}

const Group = ({ title, children, open = true }: { title: string; children: React.ReactNode; open?: boolean }) => (
  <details open={open} className="group border-b border-[var(--o-line)] py-4 last:border-0">
    <summary className="flex cursor-pointer list-none items-center justify-between text-sm font-extrabold">
      {title}
      <Icon name="plus" className="h-4 w-4 text-[var(--o-mute)] transition group-open:rotate-45" />
    </summary>
    <div className="mt-3">{children}</div>
  </details>
);

function Panel({ facets, fixed, basePath }: { facets: OpenFacets; fixed: Fixed; basePath: string }) {
  const { t, locale } = useI18n();
  const { sp, set, toggleCsv, qs } = useParamsEditor(basePath);
  const [min, setMin] = useState(sp.get("minPrice") ? String(Number(sp.get("minPrice")) / 100) : "");
  const [max, setMax] = useState(sp.get("maxPrice") ? String(Number(sp.get("maxPrice")) / 100) : "");
  useEffect(() => { const id = setTimeout(() => { setMin(sp.get("minPrice") ? String(Number(sp.get("minPrice")) / 100) : ""); setMax(sp.get("maxPrice") ? String(Number(sp.get("maxPrice")) / 100) : ""); }, 0); return () => clearTimeout(id); }, [sp]);
  const csv = (k: string) => (sp.get(k) ?? "").split(",").filter(Boolean);
  const nm = (o: { nameAr: string; nameEn: string }) => (locale === "ar" ? o.nameAr : o.nameEn || o.nameAr);
  const applyPrice = () => set({ minPrice: min ? String(Math.round(Number(min) * 100)) : null, maxPrice: max ? String(Math.round(Number(max) * 100)) : null });
  const catHref = (slug: string | null) => qs((p) => { p.delete("category"); }, slug ? `/c/${slug}` : "/search");
  const check = "h-[18px] w-[18px] shrink-0 rounded-md border border-[var(--o-line)] accent-[var(--o-accent)]";

  return (
    <div className="text-sm">
      {!fixed.shop && (
        <Group title={t("open.f.categories")}>
          <ul className="grid gap-0.5">
            {facets.trail.length > 0 && <li><Link href={catHref(null)} className="flex items-center gap-1 rounded-lg px-2 py-1.5 font-semibold text-[var(--o-mute)] hover:text-[var(--o-accent)]">‹ {t("open.f.allCategories")}</Link></li>}
            {facets.trail.map((c, i) => (
              <li key={c.slug}><Link href={catHref(c.slug)} className={`block rounded-lg py-1.5 font-bold ${i === facets.trail.length - 1 ? "text-[var(--o-accent)]" : "text-[var(--o-ink)] hover:text-[var(--o-accent)]"}`} style={{ paddingInlineStart: `${0.5 + i * 0.75}rem` }}>{nm(c)}</Link></li>
            ))}
            {facets.categories.map((c) => (
              <li key={c.slug}><Link href={catHref(c.slug)} className="flex items-center justify-between rounded-lg py-1.5 pe-2 hover:bg-[var(--o-soft)] hover:text-[var(--o-accent)]" style={{ paddingInlineStart: `${0.5 + facets.trail.length * 0.75}rem` }}><span>{c.icon} {nm(c)}</span><span className="text-xs text-[var(--o-mute)]">{c.n}</span></Link></li>
            ))}
          </ul>
        </Group>
      )}

      <Group title={t("open.f.deliverTo")} open={!!sp.get("governorate")}>
        <select value={sp.get("governorate") ?? ""} onChange={(e) => set({ governorate: e.target.value || null })} className="o-input !min-h-10 !py-2" aria-label={t("open.f.deliverTo")}>
          <option value="">{t("open.f.anywhere")}</option>
          {GOVERNORATES.map((g) => <option key={g.code} value={g.code}>{locale === "ar" ? g.ar : g.en}</option>)}
        </select>
      </Group>

      <Group title={t("open.f.price")}>
        <div className="flex items-center gap-2">
          <input value={min} onChange={(e) => setMin(e.target.value.replace(/[^0-9.]/g, ""))} inputMode="decimal" placeholder={facets.priceMinMinor !== null ? String(Math.floor(facets.priceMinMinor / 100)) : "0"} aria-label={t("open.f.min")} className="o-input !min-h-10 !px-3 !py-2 text-center" onKeyDown={(e) => e.key === "Enter" && applyPrice()} />
          <span className="text-[var(--o-mute)]">–</span>
          <input value={max} onChange={(e) => setMax(e.target.value.replace(/[^0-9.]/g, ""))} inputMode="decimal" placeholder={facets.priceMaxMinor !== null ? String(Math.ceil(facets.priceMaxMinor / 100)) : "∞"} aria-label={t("open.f.max")} className="o-input !min-h-10 !px-3 !py-2 text-center" onKeyDown={(e) => e.key === "Enter" && applyPrice()} />
          <button type="button" onClick={applyPrice} className="o-btn o-btn-dark o-btn-sm shrink-0 !px-3.5" aria-label={t("open.f.apply")}><Icon name="check" className="h-4 w-4" /></button>
        </div>
        {facets.priceMinMinor !== null && facets.priceMaxMinor !== null && <p className="mt-2 text-xs text-[var(--o-mute)]">{money(facets.priceMinMinor, "EGP", locale)} – {money(facets.priceMaxMinor, "EGP", locale)}</p>}
      </Group>

      <Group title={t("open.f.rating")}>
        <div className="grid gap-1.5">
          {[4, 3].map((r) => (
            <button key={r} type="button" onClick={() => set({ minRating: sp.get("minRating") === String(r) ? null : String(r) })} data-active={sp.get("minRating") === String(r)} className="o-chip w-fit">
              <span className="o-stars">{"★".repeat(r)}</span>{t("open.f.andUp")}
            </button>
          ))}
        </div>
      </Group>

      <Group title={t("open.f.offers")}>
        {([["onSale", t("open.f.onSale")], ["freeShipping", t("open.f.freeShipping")], ["inStock", t("open.f.inStock")]] as const).map(([k, label]) => (
          <label key={k} className="flex cursor-pointer items-center gap-2.5 py-1.5 font-medium">
            <input type="checkbox" className={check} checked={sp.get(k) === "true"} onChange={(e) => set({ [k]: e.target.checked ? "true" : null })} />{label}
          </label>
        ))}
      </Group>

      {!fixed.shop && facets.shops.length > 1 && (
        <Group title={t("open.f.shops")} open={facets.shops.length < 9 || !!sp.get("shop")}>
          <ul className="grid max-h-56 gap-0.5 overflow-y-auto pe-1">
            {facets.shops.map((s) => (
              <li key={s.shopId}>
                <label className="flex cursor-pointer items-center gap-2.5 rounded-lg py-1.5 font-medium">
                  <input type="checkbox" className={check} checked={sp.get("shop") === s.shopSlug} onChange={(e) => set({ shop: e.target.checked ? s.shopSlug : null })} />
                  <span className="flex-1 truncate">{s.shopName}</span><span className="text-xs text-[var(--o-mute)]">{s.n}</span>
                </label>
              </li>
            ))}
          </ul>
        </Group>
      )}

      {facets.brands.length > 0 && (
        <Group title={t("open.f.brands")} open={facets.brands.length < 9 || csv("brand").length > 0}>
          <ul className="grid max-h-56 gap-0.5 overflow-y-auto pe-1">
            {facets.brands.map((b) => (
              <li key={b.brand}><label className="flex cursor-pointer items-center gap-2.5 py-1.5 font-medium"><input type="checkbox" className={check} checked={csv("brand").includes(b.brand)} onChange={() => toggleCsv("brand", b.brand)} /><span className="flex-1 truncate">{b.brand}</span><span className="text-xs text-[var(--o-mute)]">{b.n}</span></label></li>
            ))}
          </ul>
        </Group>
      )}

      {facets.colors.length > 0 && (
        <Group title={t("open.f.colors")}>
          <div className="flex flex-wrap gap-2.5">
            {facets.colors.map((c) => {
              const on = csv("color").includes(c.code);
              return (
                <button key={c.code} type="button" title={nm(c)} aria-label={nm(c)} aria-pressed={on} onClick={() => toggleCsv("color", c.code)}
                  className={`relative h-9 w-9 rounded-full border-2 transition hover:scale-110 ${on ? "border-[var(--o-accent)] ring-2 ring-[var(--o-accent)]/30" : "border-[var(--o-line)]"}`} style={{ background: c.hex || "#ccc" }}>
                  {on && <Icon name="check" className="absolute inset-0 m-auto h-4 w-4 text-white mix-blend-difference" />}
                </button>
              );
            })}
          </div>
        </Group>
      )}

      {facets.sizes.length > 0 && (
        <Group title={t("open.f.sizes")} open={false}>
          <div className="flex flex-wrap gap-2">
            {facets.sizes.map((s) => <button key={s.scale + s.code} type="button" data-active={csv("size").includes(s.code)} onClick={() => toggleCsv("size", s.code)} className="o-chip !min-h-9 !px-3">{nm(s)}</button>)}
          </div>
        </Group>
      )}
    </div>
  );
}

function useClear(basePath: string) {
  const router = useRouter();
  const sp = useSearchParams();
  return () => { const p = new URLSearchParams(); if (sp.get("q")) p.set("q", sp.get("q") as string); if (sp.get("sort")) p.set("sort", sp.get("sort") as string); const s = p.toString(); router.push(s ? `${basePath}?${s}` : basePath, { scroll: false }); };
}

/** Desktop: a sticky sidebar next to the results. */
export function FiltersSidebar({ facets, fixed, basePath, activeCount }: { facets: OpenFacets; fixed: Fixed; basePath: string; activeCount: number }) {
  const { t } = useI18n();
  const clear = useClear(basePath);
  return (
    <aside className="o-card sticky top-[8.5rem] hidden max-h-[calc(100dvh-10rem)] w-72 shrink-0 self-start overflow-y-auto px-5 py-2 lg:block" aria-label={t("open.f.title")}>
      <div className="flex items-center justify-between pt-3"><p className="font-extrabold">{t("open.f.title")}</p>{activeCount > 0 && <button type="button" onClick={clear} className="o-link">{t("open.f.clear")}</button>}</div>
      <Panel facets={facets} fixed={fixed} basePath={basePath} />
    </aside>
  );
}

/** Phone: a "Filters" button that opens the same panel as a bottom sheet. */
export function FiltersButton({ facets, fixed, basePath, activeCount, total }: { facets: OpenFacets; fixed: Fixed; basePath: string; activeCount: number; total: number }) {
  const { t } = useI18n();
  const clear = useClear(basePath);
  const [open, setOpen] = useState(false);
  useEffect(() => { document.body.style.overflow = open ? "hidden" : ""; return () => { document.body.style.overflow = ""; }; }, [open]);
  return (
    <>
      <div className="lg:hidden"><button type="button" onClick={() => setOpen(true)} className="o-btn o-btn-ghost o-btn-sm"><Icon name="settings" className="h-4 w-4" />{t("open.f.title")}{activeCount > 0 && <span className="grid h-5 min-w-5 place-items-center rounded-full bg-[var(--o-accent)] px-1 text-[11px] font-black text-white">{activeCount}</span>}</button></div>
      {open && (
        <>
          <div className="o-sheet-back" onClick={() => setOpen(false)} />
          <div className="o-sheet" role="dialog" aria-modal="true" aria-label={t("open.f.title")}>
            <div className="flex items-center justify-between border-b border-[var(--o-line)] px-5 py-4">
              <p className="text-lg font-extrabold">{t("open.f.title")}</p>
              <button type="button" onClick={() => setOpen(false)} aria-label={t("common.close")} className="grid h-10 w-10 place-items-center rounded-xl hover:bg-[var(--o-soft)]"><Icon name="close" className="h-5 w-5" /></button>
            </div>
            <div className="flex-1 overflow-y-auto px-5"><Panel facets={facets} fixed={fixed} basePath={basePath} /></div>
            <div className="grid grid-cols-[auto_1fr] gap-3 border-t border-[var(--o-line)] p-4">
              <button type="button" onClick={() => { clear(); setOpen(false); }} className="o-btn o-btn-ghost">{t("open.f.clear")}</button>
              <button type="button" onClick={() => setOpen(false)} className="o-btn">{t("open.f.show", { n: total })}</button>
            </div>
          </div>
        </>
      )}
    </>
  );
}

export function SortSelect({ basePath }: { basePath: string }) {
  const { t } = useI18n();
  const router = useRouter();
  const sp = useSearchParams();
  const hasQ = !!sp.get("q");
  const options = [...(hasQ ? ["relevance"] : ["recommended"]), "popular", "newest", "price_asc", "price_desc", "discount", "rating"];
  const cur = sp.get("sort") ?? options[0];
  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="hidden font-semibold text-[var(--o-mute)] sm:inline">{t("open.sort.label")}</span>
      <select value={options.includes(cur) ? cur : options[0]} onChange={(e) => { const p = new URLSearchParams(sp.toString()); p.set("sort", e.target.value); p.delete("page"); router.push(`${basePath}?${p.toString()}`, { scroll: false }); }}
        className="o-input !min-h-10 !w-auto !rounded-full !py-1.5 !pe-8 text-sm font-bold" aria-label={t("open.sort.label")}>
        {options.map((o) => <option key={o} value={o}>{t(`open.sort.${o}`)}</option>)}
      </select>
    </label>
  );
}
