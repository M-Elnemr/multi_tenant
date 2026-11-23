"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { IArrow } from "../shared/icons";
import type { LnNav } from "../shared/ctx";

type Area = { key: string; title: string };

/** Desktop navigation with a practice-area mega menu, and the full-screen mobile sheet. */
export function LgNav({ nav, areas, book, bookLabel, mobile }: { nav: LnNav[]; areas: Area[]; book: boolean; bookLabel: string; mobile?: boolean }) {
  const { t } = useI18n();
  const path = usePathname();
  const [openAt, setOpenAt] = useState<string | null>(null);
  const open = openAt === path;
  const setOpen = (v: boolean) => setOpenAt(v ? path : null);
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.body.style.overflow = "hidden"; window.addEventListener("keydown", k);
    return () => { document.body.style.overflow = ""; window.removeEventListener("keydown", k); };
  }, [open]);
  const cur = (href: string) => (path === href || path.startsWith(href + "/") ? "page" : undefined);

  if (!mobile) return (
    <ul className="lg-nav">
      {nav.map((n) => (
        <li key={n.key}>
          <Link href={n.href} aria-current={cur(n.href)}>{n.label}</Link>
          {n.key === "areas" && areas.length > 0 && (
            <div className="lg-mega">
              {areas.slice(0, 8).map((a, i) => <Link key={a.key} href={`/areas/${encodeURIComponent(a.key)}`}><span>{String(i + 1).padStart(2, "0")}</span>{a.title}</Link>)}
            </div>
          )}
        </li>
      ))}
    </ul>
  );
  return (
    <>
      <button type="button" className="lg-burger" aria-label={t("lnx.menu")} aria-expanded={open} onClick={() => setOpen(true)}>
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M3 8h18M3 16h18" /></svg>
      </button>
      {open && (
        <div className="lg-sheet" role="dialog" aria-modal="true" aria-label={t("lnx.menu")}>
          <div className="lg-sheet-top">
            <span className="lg-up">{t("lnx.menu")}</span>
            <button type="button" className="lg-burger" aria-label={t("lnx.close")} onClick={() => setOpen(false)}>
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M5 5l14 14M19 5L5 19" /></svg>
            </button>
          </div>
          <nav>
            <Link href="/"><span>00</span>{t("lnx.home")}</Link>
            {nav.map((n, i) => <Link key={n.key} href={n.href}><span>{String(i + 1).padStart(2, "0")}</span>{n.label}</Link>)}
          </nav>
          {book && <Link href="/book" className="lg-btn">{bookLabel}<IArrow className="lg-arrow" /></Link>}
        </div>
      )}
    </>
  );
}
