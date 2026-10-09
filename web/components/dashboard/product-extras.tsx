"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, Select, Textarea } from "../ui";

export type Extras = { badge?: string; tags?: string[]; specs?: { k: string; v: string }[]; sizeGuide?: string; isFeatured?: boolean };

/** What makes a product sell: a badge, search tags, a specifications table, a size guide and "featured on the home page". */
export function ProductExtras({ id, initial, onSaved }: { id: string; initial: Extras; onSaved: () => void }) {
  const { t } = useI18n();
  const [f, setF] = useState<Extras>({ badge: initial.badge ?? "", tags: initial.tags ?? [], specs: initial.specs ?? [], sizeGuide: initial.sizeGuide ?? "", isFeatured: !!initial.isFeatured });
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => { await api(`store/products/${id}/extras`, { method: "PATCH", body: { ...f, specs: (f.specs ?? []).filter((s) => s.k.trim() && s.v.trim()) } }); setSaved(true); onSaved(); });
  const specs = f.specs ?? [];
  const edit = (patch: Partial<Extras>) => { setF({ ...f, ...patch }); setSaved(false); };
  return (
    <Card className="space-y-4">
      <h2 className="font-medium">{t("extras.title")}</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t("extras.badge")}><Select value={f.badge ?? ""} onChange={(e) => edit({ badge: e.target.value })}><option value="">{t("extras.noBadge")}</option>{["NEW", "SALE", "BEST_SELLER", "LIMITED"].map((b) => <option key={b} value={b}>{t(`badge.${b}`)}</option>)}</Select></Field>
        <Field label={t("extras.tags")} hint={t("extras.tagsHint")}><Input value={(f.tags ?? []).join(", ")} onChange={(e) => edit({ tags: e.target.value.split(/[,،]/).map((x) => x.trim()).filter(Boolean) })} /></Field>
      </div>
      <label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" className="h-5 w-5" checked={!!f.isFeatured} onChange={(e) => edit({ isFeatured: e.target.checked })} />⭐ {t("extras.featured")}</label>
      <div className="space-y-2">
        <p className="text-sm font-semibold text-slate-700">{t("extras.specs")}</p>
        {specs.map((s, i) => (
          <div key={i} className="grid grid-cols-[1fr_1fr_auto] gap-2">
            <Input placeholder={t("extras.specName")} value={s.k} onChange={(e) => edit({ specs: specs.map((x, j) => (j === i ? { ...x, k: e.target.value } : x)) })} />
            <Input placeholder={t("extras.specValue")} value={s.v} onChange={(e) => edit({ specs: specs.map((x, j) => (j === i ? { ...x, v: e.target.value } : x)) })} />
            <Button type="button" variant="ghost" size="sm" onClick={() => edit({ specs: specs.filter((_, j) => j !== i) })}>✕</Button>
          </div>
        ))}
        <Button type="button" variant="secondary" size="sm" onClick={() => edit({ specs: [...specs, { k: "", v: "" }] })}>+ {t("extras.addSpec")}</Button>
      </div>
      <Field label={t("product.sizeGuide")}><Textarea rows={3} value={f.sizeGuide ?? ""} onChange={(e) => edit({ sizeGuide: e.target.value })} /></Field>
      <ErrorText error={save.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
      <Button type="button" loading={save.loading} onClick={() => save.run()}>{t("common.save")}</Button>
    </Card>
  );
}
