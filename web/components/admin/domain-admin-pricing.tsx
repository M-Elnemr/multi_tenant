"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Empty, ErrorText, Input, Loading, Select } from "@/components/ui";
import { toMinor } from "@/lib/format";

type PriceRule = {
  tld: string;
  markupKind: "FIXED" | "PERCENT";
  markupValue: number; // minor currency units when FIXED, a plain percent number (e.g. 20) when PERCENT
  currency: string;
  promoPriceMinor?: number | null;
};

/** One rule row being edited, kept in major currency units (EGP) in the form, converted at save time. */
type EditRow = { tld: string; markupKind: "FIXED" | "PERCENT"; markupValueText: string; currency: string; promoText: string };

const toEdit = (r: PriceRule): EditRow => ({
  tld: r.tld,
  markupKind: r.markupKind,
  markupValueText: r.markupKind === "FIXED" ? (r.markupValue / 100).toString() : r.markupValue.toString(),
  currency: r.currency,
  promoText: r.promoPriceMinor != null ? (r.promoPriceMinor / 100).toString() : "",
});

const toRule = (e: EditRow): PriceRule => ({
  tld: e.tld.trim().toLowerCase(),
  markupKind: e.markupKind,
  markupValue: e.markupKind === "FIXED" ? toMinor(e.markupValueText || "0") : Number(e.markupValueText || "0"),
  currency: e.currency.trim().toUpperCase() || "EGP",
  promoPriceMinor: e.promoText.trim() ? toMinor(e.promoText) : null,
});

/** Per-TLD markup editor over `commerce.domain_price_rules` (admin platform.tenant.manage). No code deploy needed to change pricing. */
export function DomainAdminPricing() {
  const { t } = useI18n();
  const { data, error, loading, reload } = useApi<PriceRule[]>("platform/domains/price-rules");
  // Same "edited overrides loaded" pattern as the branding form: no effect needed to seed local state from the fetch.
  const [edited, setEdited] = useState<EditRow[] | null>(null);
  const rows = edited ?? (data ? data.map(toEdit) : []);

  const save = useAction(async () => {
    await api("platform/domains/price-rules", { method: "PUT", body: { rules: rows.map(toRule) } });
    setEdited(null);
    await reload();
  });
  const [saved, setSaved] = useState(false);
  useEffect(() => { if (saved) { const id = setTimeout(() => setSaved(false), 3000); return () => clearTimeout(id); } }, [saved]);

  const update = (i: number, patch: Partial<EditRow>) => setEdited(rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));
  const removeRow = (i: number) => setEdited(rows.filter((_, idx) => idx !== i));
  const addRow = () => setEdited([...rows, { tld: "", markupKind: "FIXED", currency: "EGP", markupValueText: "", promoText: "" }]);

  if (loading && !data) return <Loading />;
  const edit = rows;
  return (
    <>
      <ErrorText error={error} />
      <p className="mb-4 text-sm text-slate-600">{t("domainBuy.pricingHint")}</p>
      {edit.length === 0 ? <Empty>{t("domainBuy.noRules")}</Empty> : (
        <div className="space-y-3">
          {edit.map((r, i) => (
            <div key={i} className="grid grid-cols-2 gap-3 rounded-xl bg-white p-4 shadow-card sm:grid-cols-5">
              <label className="space-y-1 text-xs font-semibold text-slate-600">{t("domainBuy.colTld")}
                <Input dir="ltr" value={r.tld} placeholder={t("domainBuy.tldPlaceholder")} onChange={(e) => update(i, { tld: e.target.value })} />
              </label>
              <label className="space-y-1 text-xs font-semibold text-slate-600">{t("domainBuy.colMarkupKind")}
                <Select value={r.markupKind} onChange={(e) => update(i, { markupKind: e.target.value as EditRow["markupKind"] })}>
                  <option value="FIXED">{t("domainBuy.markupFixed")}</option>
                  <option value="PERCENT">{t("domainBuy.markupPercent")}</option>
                </Select>
              </label>
              <label className="space-y-1 text-xs font-semibold text-slate-600">{t("domainBuy.colMarkupValue")}
                <Input dir="ltr" type="number" value={r.markupValueText} onChange={(e) => update(i, { markupValueText: e.target.value })} />
              </label>
              <label className="space-y-1 text-xs font-semibold text-slate-600">{t("domainBuy.colCurrency")}
                <Input dir="ltr" value={r.currency} onChange={(e) => update(i, { currency: e.target.value })} />
              </label>
              <div className="flex items-end gap-2">
                <label className="flex-1 space-y-1 text-xs font-semibold text-slate-600">{t("domainBuy.colPromo")}
                  <Input dir="ltr" type="number" value={r.promoText} onChange={(e) => update(i, { promoText: e.target.value })} />
                </label>
                <Button type="button" variant="ghost" size="sm" onClick={() => removeRow(i)}>{t("common.remove")}</Button>
              </div>
            </div>
          ))}
        </div>
      )}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button type="button" variant="secondary" onClick={addRow}>{t("domainBuy.addRule")}</Button>
        <Button type="button" loading={save.loading} onClick={() => void save.run().then(() => setSaved(true))}>{t("common.save")}</Button>
        {saved && <span className="text-sm font-semibold text-emerald-700">{t("domainBuy.rulesSaved")}</span>}
      </div>
      <ErrorText error={save.error} />
    </>
  );
}
