"use client";

import { useRouter } from "next/navigation";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { oapi } from "@/lib/open-client";
import { money } from "@/lib/format";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";
import { OImg } from "./media";

type Suggest = {
  products: { id: string; name: string; slug: string; minPriceMinor: number; currency: string; shop: { slug: string; name: string }; image?: { thumb: string } | null }[];
  categories: { nameAr: string; nameEn: string; slug: string; icon?: string }[];
  shops: { id: string; slug: string; name: string }[];
};

type Row = { key: string; kind: "category" | "shop" | "product" | "recent"; href: string; label: string; sub?: string; price?: string; img?: string | null; icon?: string };

const RECENT = "open:recent-searches";
const readRecent = (): string[] => { try { return (JSON.parse(localStorage.getItem(RECENT) ?? "[]") as string[]).slice(0, 6); } catch { return []; } };
const writeRecent = (list: string[]) => { try { localStorage.setItem(RECENT, JSON.stringify(list.slice(0, 6))); } catch { /* private mode */ } };

/** Search-as-you-type across every shop: categories, shops and products under the box (arrow keys + Enter work), recent searches when the box is empty; Enter opens the full results. */
export function OpenSearch({ className = "" }: { className?: string }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const listId = useId();
  const [q, setQ] = useState("");
  const [res, setRes] = useState<Suggest | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false);
  const [recent, setRecent] = useState<string[]>([]);
  const [cursor, setCursor] = useState(-1);
  const box = useRef<HTMLDivElement>(null);
  const term = q.trim();

  useEffect(() => {
    if (term.length < 2) { const id = setTimeout(() => { setRes(null); setBusy(false); }, 0); return () => clearTimeout(id); }
    let alive = true;
    const id = setTimeout(() => {
      setBusy(true);
      oapi<Suggest>(`suggest?q=${encodeURIComponent(term)}`).then((r) => { if (alive) { setRes(r); setCursor(-1); } }).catch(() => alive && setRes(null)).finally(() => alive && setBusy(false));
    }, 200);
    return () => { alive = false; clearTimeout(id); };
  }, [term]);

  useEffect(() => {
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  const rows: Row[] = useMemo(() => {
    if (term.length < 2) return recent.map((r) => ({ key: `r-${r}`, kind: "recent" as const, href: `/search?q=${encodeURIComponent(r)}`, label: r }));
    if (!res) return [];
    return [
      ...res.categories.map((c) => ({ key: `c-${c.slug}`, kind: "category" as const, href: `/c/${c.slug}`, label: locale === "ar" ? c.nameAr : c.nameEn, icon: c.icon })),
      ...res.shops.map((s) => ({ key: `s-${s.id}`, kind: "shop" as const, href: `/shop/${s.slug}`, label: s.name })),
      ...res.products.map((p) => ({ key: `p-${p.id}`, kind: "product" as const, href: `/p/${p.shop.slug}/${p.slug}`, label: p.name, sub: p.shop.name, price: money(p.minPriceMinor, p.currency, locale), img: p.image?.thumb ?? null })),
    ];
  }, [res, term, recent, locale]);

  const remember = (v: string) => { if (v) writeRecent([v, ...readRecent().filter((x) => x !== v)]); };
  const submit = (v: string) => { setOpen(false); remember(v); router.push(v ? `/search?q=${encodeURIComponent(v)}` : "/search"); };
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") { setOpen(false); setCursor(-1); return; }
    if (!open || rows.length === 0) return;
    if (e.key === "ArrowDown") { e.preventDefault(); setCursor((c) => (c + 1) % rows.length); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setCursor((c) => (c <= 0 ? rows.length - 1 : c - 1)); }
    else if (e.key === "Enter" && cursor >= 0) { e.preventDefault(); const r = rows[cursor]; setOpen(false); if (r.kind === "recent") remember(r.label); router.push(r.href); }
  };

  const showPanel = open && (rows.length > 0 || (term.length >= 2 && !busy && res !== null) || busy);
  const section = (kind: Row["kind"], title: string) => rows.some((r) => r.kind === kind) && <p className="o-eyebrow !text-[var(--o-mute)] px-4 pb-1 pt-3">{title}</p>;
  const renderRow = (r: Row) => {
    const idx = rows.indexOf(r);
    const on = idx === cursor;
    return (
      <li key={r.key} role="option" aria-selected={on}>
        <a href={r.href} onMouseEnter={() => setCursor(idx)} onClick={(e) => { e.preventDefault(); setOpen(false); if (r.kind === "recent") remember(r.label); router.push(r.href); }}
          className={`flex items-center gap-3 px-4 py-2.5 transition ${on ? "bg-[var(--o-soft)]" : ""}`}>
          {r.kind === "product" ? (
            <span className="o-frame h-11 w-11 shrink-0 rounded-lg"><OImg src={r.img} name={r.label} width={44} height={44} /></span>
          ) : (
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-[var(--o-soft)] text-[var(--o-mute)]">{r.kind === "category" && r.icon ? <span className="text-lg">{r.icon}</span> : <Icon name={r.kind === "shop" ? "bag" : r.kind === "recent" ? "clock" : "search"} className="h-[18px] w-[18px]" />}</span>
          )}
          <span className="min-w-0 flex-1"><span className="line-clamp-1 text-sm font-semibold">{r.label}</span>{r.sub && <span className="block text-xs text-[var(--o-mute)]">{r.sub}</span>}</span>
          {r.price && <span className="o-num shrink-0 text-sm font-bold">{r.price}</span>}
        </a>
      </li>
    );
  };

  return (
    <div ref={box} className={`relative ${className}`}>
      <form onSubmit={(e) => { e.preventDefault(); submit(term); }} role="search" className="flex items-center overflow-hidden rounded-full border border-[var(--o-line-2)] bg-white transition focus-within:border-[var(--o-ink)] focus-within:shadow-[0_0_0_4px_rgb(23_19_14/.08)]">
        <span className="ps-4 text-[var(--o-mute)]"><Icon name="search" className="h-[18px] w-[18px]" /></span>
        <input value={q} onChange={(e) => { setQ(e.target.value); setCursor(-1); setOpen(true); }} onFocus={() => { setRecent(readRecent()); setOpen(true); }} onKeyDown={onKey}
          placeholder={t("open.searchPlaceholder")} aria-label={t("open.searchPlaceholder")} role="combobox" aria-expanded={showPanel} aria-controls={listId} aria-autocomplete="list" autoComplete="off" enterKeyHint="search"
          className="min-h-11 min-w-0 flex-1 bg-transparent px-3 py-2.5 text-[15px] text-[var(--o-ink)] outline-none placeholder:text-[var(--o-faint)]" />
        {q && <button type="button" onClick={() => { setQ(""); setRes(null); }} aria-label={t("open.search.clear")} className="grid h-9 w-9 place-items-center rounded-full text-[var(--o-mute)] hover:bg-[var(--o-soft)]"><Icon name="close" className="h-4 w-4" /></button>}
        <button type="submit" className="m-1 min-h-9 rounded-full bg-[var(--o-ink)] px-4 text-sm font-bold text-white transition hover:bg-black max-md:hidden">{t("open.search")}</button>
      </form>
      {showPanel && (
        <div className="o-pop absolute inset-x-0 top-full z-50 mt-2 overflow-hidden rounded-3xl border border-[var(--o-line)] bg-[var(--o-paper)] text-[var(--o-ink)] shadow-[var(--o-shadow-lg)]">
          <ul id={listId} role="listbox" className="max-h-[65dvh] overflow-y-auto py-1" aria-label={t("open.searchPlaceholder")}>
            {term.length < 2 && rows.length > 0 && (
              <li role="presentation" className="flex items-center justify-between px-4 pb-1 pt-3"><span className="o-eyebrow !text-[var(--o-mute)]">{t("open.search.recent")}</span><button type="button" className="text-xs font-bold text-[var(--o-mute)] hover:text-[var(--o-accent)]" onClick={() => { writeRecent([]); setRecent([]); }}>{t("open.search.clearRecent")}</button></li>
            )}
            {term.length >= 2 && busy && !res && <li role="presentation" className="px-4 py-4 text-sm text-[var(--o-mute)]">{t("open.search.searching")}</li>}
            {term.length >= 2 && res && rows.length === 0 && <li role="presentation" className="px-4 py-5 text-sm text-[var(--o-mute)]">{t("open.search.noMatch", { q: term })}</li>}
            {term.length >= 2 && (
              <>
                {section("category", t("open.f.categories"))}
                {rows.filter((r) => r.kind === "category").map(renderRow)}
                {section("shop", t("open.f.shops"))}
                {rows.filter((r) => r.kind === "shop").map(renderRow)}
                {section("product", t("open.allProducts"))}
                {rows.filter((r) => r.kind === "product").map(renderRow)}
              </>
            )}
            {term.length < 2 && rows.map(renderRow)}
          </ul>
          {term.length >= 2 && (
            <button type="button" onClick={() => submit(term)} className="flex w-full items-center justify-between border-t border-[var(--o-line)] bg-[var(--o-soft)] px-4 py-3 text-sm font-bold">{t("open.seeAllResults", { q: term })}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></button>
          )}
        </div>
      )}
    </div>
  );
}
