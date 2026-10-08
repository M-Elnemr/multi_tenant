"use client";

import { useMemo, useState } from "react";
import { useI18n } from "@/components/i18n-provider";

export type CategoryOption = { code: string; nameAr: string; nameEn: string; popular?: boolean };

/** True when the selection is usable: 1 to `max` choices, and a typed name if "other" is among them. */
export function categoriesValid(codes: string[], other: string, max = 5) {
  return codes.length >= 1 && codes.length <= max && (!codes.includes("other") || other.trim().length >= 2);
}

/**
 * Pick one or more entries from a long list (a clinic's specialties, a shop's product categories). The most common ones come first, a search box
 * narrows the rest, and "Other" opens a field for a name that is not on the list. The server re-validates everything.
 */
export function CategoryPicker({ options, value, onChange, other, onOther, max = 5 }: {
  options: CategoryOption[];
  value: string[];
  onChange: (codes: string[]) => void;
  other: string;
  onOther: (v: string) => void;
  max?: number;
}) {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const label = (o: CategoryOption) => (locale === "ar" ? o.nameAr : o.nameEn);
  const sub = (o: CategoryOption) => (locale === "ar" ? o.nameEn : o.nameAr);
  const byCode = useMemo(() => new Map(options.map((o) => [o.code, o])), [options]);
  const needle = q.trim().toLowerCase();
  const matches = (o: CategoryOption) => !needle || o.nameAr.toLowerCase().includes(needle) || o.nameEn.toLowerCase().includes(needle);
  const popular = options.filter((o) => o.popular && matches(o));
  const rest = options.filter((o) => !o.popular && o.code !== "other" && matches(o));
  const otherOpt = byCode.get("other");
  const toggle = (code: string) => {
    if (value.includes(code)) onChange(value.filter((c) => c !== code));
    else if (value.length < max) onChange([...value, code]);
  };
  const chip = (o: CategoryOption) => {
    const on = value.includes(o.code);
    const full = !on && value.length >= max;
    return (
      <button type="button" key={o.code} onClick={() => toggle(o.code)} disabled={full} aria-pressed={on} title={sub(o)}
        className={`rounded-full border px-3 py-1.5 text-sm transition ${on ? "border-brand bg-brand text-white" : "bg-white hover:border-brand"} ${full ? "opacity-40" : ""}`}>
        {on && "✓ "}{label(o)}
      </button>
    );
  };
  return (
    <div className="space-y-3">
      {value.length > 0 && (
        <div className="flex flex-wrap gap-2" aria-label={t("picker.selected")}>
          {value.map((c) => { const o = byCode.get(c); return o ? <span key={c} className="inline-flex items-center gap-1 rounded-full bg-brand/10 px-3 py-1 text-sm text-brand-700">{label(o)}<button type="button" aria-label={t("common.remove")} onClick={() => toggle(c)} className="px-1">×</button></span> : null; })}
        </div>
      )}
      <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("picker.search")} className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm" />
      <p className="text-xs text-slate-500">{t("picker.max").replace("{n}", String(max))}</p>
      <div className="max-h-64 space-y-3 overflow-y-auto rounded-lg border border-slate-200 p-3">
        {popular.length > 0 && <div><p className="mb-1.5 text-xs font-medium text-slate-500">{t("picker.popular")}</p><div className="flex flex-wrap gap-2">{popular.map(chip)}</div></div>}
        {rest.length > 0 && <div><p className="mb-1.5 text-xs font-medium text-slate-500">{t("picker.all")}</p><div className="flex flex-wrap gap-2">{rest.map(chip)}</div></div>}
        {otherOpt && <div className="flex flex-wrap gap-2">{chip(otherOpt)}</div>}
        {popular.length + rest.length === 0 && <p className="text-sm text-slate-500">{t("picker.none")}</p>}
      </div>
      {value.includes("other") && (
        <input value={other} onChange={(e) => onOther(e.target.value)} maxLength={100} placeholder={t("picker.otherName")} className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm" />
      )}
    </div>
  );
}
