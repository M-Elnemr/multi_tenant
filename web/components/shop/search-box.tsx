"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";

type Suggest = {
  products: { id: string; name: string; slug: string; minPriceMinor: number; currency: string; imageUrl?: string; imageMediaBase?: string; imageMediaExt?: string }[];
  categories: { name: string; slug: string }[];
};

/** Search-as-you-type: a few matching products and categories under the box; Enter goes to the full results page. */
export function SearchBox({ className = "" }: { className?: string }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [q, setQ] = useState("");
  const [res, setRes] = useState<Suggest | null>(null);
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (q.trim().length < 2) { const id = setTimeout(() => setRes(null), 0); return () => clearTimeout(id); }
    let alive = true;
    const id = setTimeout(() => { api<Suggest>(`shop/search/suggest?q=${encodeURIComponent(q.trim())}`).then((r) => alive && setRes(r)).catch(() => alive && setRes(null)); }, 220);
    return () => { alive = false; clearTimeout(id); };
  }, [q]);

  useEffect(() => {
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  const go = (e: React.FormEvent) => { e.preventDefault(); setOpen(false); router.push(q.trim() ? `/products?q=${encodeURIComponent(q.trim())}` : "/products"); };
  const has = res && (res.products.length > 0 || res.categories.length > 0);
  return (
    <div ref={box} className={`relative ${className}`}>
      <form onSubmit={go} role="search" className="relative">
        <Icon name="search" className="pointer-events-none absolute start-4 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-[var(--s-mute)]" />
        <input value={q} onChange={(e) => { setQ(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)} placeholder={t("shop.search")} aria-label={t("shop.search")} autoComplete="off"
          className="s-input !rounded-full !bg-[var(--s-soft)] !py-3 !ps-11 !pe-4" />
      </form>
      {open && has && (
        <div className="s-dropdown absolute inset-x-0 top-full z-50 mt-2 overflow-hidden rounded-3xl border border-[var(--s-line)] bg-white shadow-[var(--s-shadow-lg)]">
          {res.categories.length > 0 && (
            <div className="flex flex-wrap gap-2 border-b border-[var(--s-line)] p-3">
              {res.categories.map((c) => <a key={c.slug} href={`/c/${c.slug}`} className="s-chip">{c.name}</a>)}
            </div>
          )}
          <ul>
            {res.products.map((p) => (
              <li key={p.id}>
                <a href={`/products/${p.slug}`} className="flex items-center gap-3 px-3 py-2.5 transition hover:bg-[var(--s-soft)]">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={mediaUrl({ url: p.imageUrl, mediaBase: p.imageMediaBase, mediaExt: p.imageMediaExt }, "thumb") ?? ""} alt="" className="h-12 w-12 rounded-xl bg-[var(--s-soft)] object-cover" />
                  <span className="min-w-0 flex-1 truncate text-sm font-semibold">{p.name}</span>
                  <span className="text-sm font-bold" style={{ color: "var(--brand)" }}>{money(p.minPriceMinor, p.currency, locale)}</span>
                </a>
              </li>
            ))}
          </ul>
          <button onClick={go} className="block w-full border-t border-[var(--s-line)] px-4 py-3 text-start text-sm font-bold transition hover:bg-[var(--s-soft)]" style={{ color: "var(--brand)" }}>{t("shop.seeAllResults")} →</button>
        </div>
      )}
    </div>
  );
}
