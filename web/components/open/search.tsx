"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { oapi } from "@/lib/open-client";
import { money } from "@/lib/format";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";

type Suggest = {
  products: { id: string; name: string; slug: string; minPriceMinor: number; currency: string; shop: { slug: string; name: string }; image?: { thumb: string } | null }[];
  categories: { nameAr: string; nameEn: string; slug: string; icon?: string }[];
  shops: { id: string; slug: string; name: string }[];
};

/** Search-as-you-type across every shop: products, categories and shops under the box; Enter opens the full results. */
export function OpenSearch({ className = "" }: { className?: string }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [q, setQ] = useState("");
  const [res, setRes] = useState<Suggest | null>(null);
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (q.trim().length < 2) { const id = setTimeout(() => setRes(null), 0); return () => clearTimeout(id); }
    let alive = true;
    const id = setTimeout(() => { oapi<Suggest>(`suggest?q=${encodeURIComponent(q.trim())}`).then((r) => alive && setRes(r)).catch(() => alive && setRes(null)); }, 200);
    return () => { alive = false; clearTimeout(id); };
  }, [q]);

  useEffect(() => {
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  const go = (e: React.FormEvent) => { e.preventDefault(); setOpen(false); router.push(q.trim() ? `/search?q=${encodeURIComponent(q.trim())}` : "/search"); };
  const has = res && (res.products.length + res.categories.length + res.shops.length > 0);
  return (
    <div ref={box} className={`relative ${className}`}>
      <form onSubmit={go} role="search" className="flex overflow-hidden rounded-2xl bg-white shadow-[0_0_0_2px_transparent] transition focus-within:shadow-[0_0_0_3px_var(--o-gold)]">
        <input value={q} onChange={(e) => { setQ(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)} placeholder={t("open.searchPlaceholder")} aria-label={t("open.searchPlaceholder")} autoComplete="off" enterKeyHint="search"
          className="min-w-0 flex-1 bg-transparent px-4 py-3 text-[15px] text-[var(--o-ink)] outline-none placeholder:text-[var(--o-mute)]" />
        <button type="submit" aria-label={t("open.search")} className="m-1 grid w-12 place-items-center rounded-xl bg-gradient-to-br from-[var(--o-accent)] to-[var(--o-accent-2)] text-white transition hover:brightness-110"><Icon name="search" className="h-5 w-5" /></button>
      </form>
      {open && has && (
        <div className="o-pop absolute inset-x-0 top-full z-50 mt-2 overflow-hidden rounded-3xl border border-[var(--o-line)] bg-white text-[var(--o-ink)] shadow-[var(--o-shadow-lg)]">
          {res.categories.length > 0 && (
            <div className="flex flex-wrap gap-2 border-b border-[var(--o-line)] p-3">
              {res.categories.map((c) => <Link key={c.slug} href={`/c/${c.slug}`} onClick={() => setOpen(false)} className="o-chip">{c.icon} {locale === "ar" ? c.nameAr : c.nameEn}</Link>)}
            </div>
          )}
          {res.shops.length > 0 && (
            <div className="flex flex-wrap items-center gap-2 border-b border-[var(--o-line)] p-3 text-sm">
              <Icon name="bag" className="h-4 w-4 text-[var(--o-mute)]" />
              {res.shops.map((s) => <Link key={s.id} href={`/shop/${s.slug}`} onClick={() => setOpen(false)} className="o-chip">{s.name}</Link>)}
            </div>
          )}
          <ul className="max-h-[60dvh] overflow-y-auto">
            {res.products.map((p) => (
              <li key={p.id}>
                <Link href={`/p/${p.shop.slug}/${p.slug}`} onClick={() => setOpen(false)} className="flex items-center gap-3 px-3 py-2.5 transition hover:bg-[var(--o-soft)]">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  {p.image ? <img src={p.image.thumb} alt="" width={48} height={48} className="h-12 w-12 shrink-0 rounded-xl bg-[var(--o-soft)] object-cover" /> : <span className="grid h-12 w-12 shrink-0 place-items-center rounded-xl bg-[var(--o-soft)] text-xl">🛍️</span>}
                  <span className="min-w-0 flex-1">
                    <span className="line-clamp-1 text-sm font-bold">{p.name}</span>
                    <span className="block text-xs text-[var(--o-mute)]">{p.shop.name}</span>
                  </span>
                  <span className="shrink-0 text-sm font-extrabold text-[var(--o-accent)]">{money(p.minPriceMinor, p.currency, locale)}</span>
                </Link>
              </li>
            ))}
          </ul>
          <button type="button" onClick={go} className="block w-full border-t border-[var(--o-line)] bg-[var(--o-soft)] px-4 py-3 text-center text-sm font-extrabold text-[var(--o-accent)]">{t("open.seeAllResults", { q: q.trim() })}</button>
        </div>
      )}
    </div>
  );
}
