"use client";

import { useEffect, useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Facets } from "@/components/shop/filters";
import type { CategoryNode } from "@/components/shop/types";
import { ActiveFilters, FacetPanel, SortControl, useFilterParams } from "../kit";

/** Filters live in a small popover under one word in the toolbar (click outside or Escape closes it); sort is a plain select. */
export function MinimalListingShell({ tree, activeSlug, facets, currency, total, children }: { tree: CategoryNode[]; activeSlug: string; facets: Facets; currency: string; total: number; children: React.ReactNode }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const { active } = useFilterParams();
  useEffect(() => {
    if (!open) return;
    const off = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", off); document.addEventListener("keydown", key);
    return () => { document.removeEventListener("mousedown", off); document.removeEventListener("keydown", key); };
  }, [open]);
  return (
    <>
      <div className="mn-tools" ref={ref}>
        <button className="mn-txt mn-filter-btn" onClick={() => setOpen(!open)} aria-expanded={open} aria-controls="mn-pop">{t("filter.title")}{active.length > 0 ? ` (${active.length})` : ""}</button>
        <span className="mn-count" aria-live="polite">{t("lg.results", { n: total })}</span>
        <SortControl />
        {open && <div id="mn-pop" className="mn-pop"><FacetPanel tree={tree} activeSlug={activeSlug} facets={facets} currency={currency} hideCategories /><button className="mn-done" onClick={() => setOpen(false)}>{t("lg.showResults", { n: total })}</button></div>}
      </div>
      <ActiveFilters currency={currency} />
      <div className="mn-grid mn-grid-list">{children}</div>
    </>
  );
}
