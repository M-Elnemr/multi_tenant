"use client";

import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import type { Facets } from "@/components/shop/filters";
import type { CategoryNode } from "@/components/shop/types";
import { ActiveFilters, FacetPanel, SortControl, useFilterParams } from "../kit";

/** The filter panel opens in place above the products (no sidebar, no sheet): facets run in columns. Sort is a row of words. */
export function EditorialListingShell({ tree, activeSlug, facets, currency, total, children }: { tree: CategoryNode[]; activeSlug: string; facets: Facets; currency: string; total: number; children: React.ReactNode }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const { active } = useFilterParams();
  return (
    <>
      <div className="ed-tools">
        <button className="ed-word" onClick={() => setOpen(!open)} aria-expanded={open} aria-controls="ed-filters">{t("filter.title")} <span aria-hidden>{open ? "−" : "+"}</span>{active.length > 0 && <b> ({active.length})</b>}</button>
        <span className="ed-count" aria-live="polite">{t("lg.results", { n: total })}</span>
        <SortControl mode="links" />
      </div>
      {open && <div id="ed-filters" className="ed-filters"><FacetPanel tree={tree} activeSlug={activeSlug} facets={facets} currency={currency} hideCategories /></div>}
      <ActiveFilters currency={currency} />
      <div className="ed-grid ed-grid-list">{children}</div>
    </>
  );
}
