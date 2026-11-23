"use client";

import { useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import type { Facets } from "@/components/shop/filters";
import type { CategoryNode } from "@/components/shop/types";
import { usePanel } from "../chrome";
import { ActiveFilters, FacetPanel, SortControl, useFilterParams } from "../kit";

/** Filters always open in a side drawer (on every screen size), with a live "show N" button; sort is a row of chunky buttons. */
export function BoldListingShell({ tree, activeSlug, facets, currency, total, children }: { tree: CategoryNode[]; activeSlug: string; facets: Facets; currency: string; total: number; children: React.ReactNode }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const { active } = useFilterParams();
  const close = () => setOpen(false);
  usePanel(open, close);
  return (
    <>
      <div className="bd-tools">
        <button className="bd-btn bd-filter-btn" onClick={() => setOpen(true)}><Icon name="filter" className="h-5 w-5" />{t("filter.title")}{active.length > 0 && <b>{active.length}</b>}</button>
        <span className="bd-count" aria-live="polite">{t("lg.results", { n: total })}</span>
        <SortControl mode="links" />
      </div>
      <ActiveFilters currency={currency} />
      <div className="bd-grid bd-grid-list">{children}</div>
      {open && (
        <div className="bd-drawer-wrap" role="dialog" aria-modal="true" aria-label={t("filter.title")}>
          <button className="bd-scrim" onClick={close} aria-label={t("common.close")} />
          <aside className="bd-drawer bd-drawer-f">
            <div className="bd-drawer-head"><b>{t("filter.title")}</b><button className="bd-box" onClick={close} aria-label={t("common.close")}><Icon name="close" className="h-5 w-5" /></button></div>
            <div className="bd-drawer-body"><FacetPanel tree={tree} activeSlug={activeSlug} facets={facets} currency={currency} /></div>
            <div className="bd-drawer-foot"><button className="bd-btn" onClick={close}>{t("lg.showResults", { n: total })}</button></div>
          </aside>
        </div>
      )}
    </>
  );
}
