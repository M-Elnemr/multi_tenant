"use client";

import { useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import type { Facets } from "@/components/shop/filters";
import type { CategoryNode } from "@/components/shop/types";
import { usePanel } from "../chrome";
import { ActiveFilters, FacetPanel, SortControl, useFilterParams } from "../kit";

/** Filters: a rounded "Filters" pill that opens a centred modal-style sheet on desktop and a bottom sheet on phones; sort sits beside it as a rounded select. */
export function LifestyleListingShell({ tree, activeSlug, facets, currency, total, children }: { tree: CategoryNode[]; activeSlug: string; facets: Facets; currency: string; total: number; children: React.ReactNode }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const { active } = useFilterParams();
  const close = () => setOpen(false);
  usePanel(open, close);
  return (
    <>
      <div className="lf-tools">
        <button className="lf-btn lf-btn-soft lf-filter-btn" onClick={() => setOpen(true)}><Icon name="sliders" className="h-5 w-5" />{t("filter.title")}{active.length > 0 && <b>{active.length}</b>}</button>
        <span className="lf-count" aria-live="polite">{t("lg.results", { n: total })}</span>
        <SortControl />
      </div>
      <ActiveFilters currency={currency} />
      <div className="lf-grid lf-grid-list">{children}</div>
      {open && (
        <div className="lf-modal-wrap" role="dialog" aria-modal="true" aria-label={t("filter.title")}>
          <button className="lf-scrim" onClick={close} aria-label={t("common.close")} />
          <div className="lf-modal">
            <div className="lf-modal-head"><b>{t("filter.title")}</b><button className="lf-round" onClick={close} aria-label={t("common.close")}><Icon name="close" className="h-5 w-5" /></button></div>
            <div className="lf-modal-body"><FacetPanel tree={tree} activeSlug={activeSlug} facets={facets} currency={currency} /></div>
            <div className="lf-modal-foot"><button className="lf-btn" onClick={close}>{t("lg.showResults", { n: total })}</button></div>
          </div>
        </div>
      )}
    </>
  );
}
