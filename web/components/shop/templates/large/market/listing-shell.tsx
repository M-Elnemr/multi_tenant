"use client";

import { useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import type { Facets } from "@/components/shop/filters";
import type { CategoryNode } from "@/components/shop/types";
import { usePanel } from "../chrome";
import { ActiveFilters, FacetPanel, SortControl, useFilterParams } from "../kit";

/** Persistent facet sidebar on desktop, a bottom sheet on phones; toolbar with result count, sort and grid/list switch. The products themselves arrive as children. */
export function MarketListingShell({ tree, activeSlug, facets, currency, total, children }: { tree: CategoryNode[]; activeSlug: string; facets: Facets; currency: string; total: number; children: React.ReactNode }) {
  const { t } = useI18n();
  const [sheet, setSheet] = useState(false);
  const [view, setView] = useState<"grid" | "list">("grid");
  const { active } = useFilterParams();
  const close = () => setSheet(false);
  usePanel(sheet, close);
  const panel = <FacetPanel tree={tree} activeSlug={activeSlug} facets={facets} currency={currency} />;
  return (
    <div className="mk-list">
      <aside className="mk-side" aria-label={t("filter.title")}>{panel}</aside>
      <div className="mk-results">
        <div className="mk-toolbar">
          <button className="lg-chip mk-filter-btn" onClick={() => setSheet(true)}><Icon name="filter" className="h-4 w-4" />{t("filter.title")}{active.length > 0 && <b>{active.length}</b>}</button>
          <span className="mk-count" aria-live="polite">{t("lg.results", { n: total })}</span>
          <div className="mk-tools">
            <SortControl />
            <div className="mk-view" role="group" aria-label={t("lg.view")}>
              <button onClick={() => setView("grid")} aria-pressed={view === "grid"} aria-label={t("lg.viewGrid")}><Icon name="grid" className="h-[18px] w-[18px]" /></button>
              <button onClick={() => setView("list")} aria-pressed={view === "list"} aria-label={t("lg.viewList")}><Icon name="list" className="h-[18px] w-[18px]" /></button>
            </div>
          </div>
        </div>
        <ActiveFilters currency={currency} />
        <div className="mk-grid mk-grid-list" data-view={view}>{children}</div>
      </div>
      {sheet && (
        <div className="mk-drawer-wrap mk-sheet-wrap" role="dialog" aria-modal="true" aria-label={t("filter.title")}>
          <button className="mk-scrim" onClick={close} aria-label={t("common.close")} />
          <div className="mk-sheet">
            <div className="mk-drawer-head"><b>{t("filter.title")}</b><button className="lg-iconbtn" onClick={close} aria-label={t("common.close")}><Icon name="close" className="h-5 w-5" /></button></div>
            <div className="mk-sheet-body">{panel}</div>
            <div className="mk-sheet-foot"><button className="mk-btn" onClick={close}>{t("lg.showResults", { n: total })}</button></div>
          </div>
        </div>
      )}
    </div>
  );
}
