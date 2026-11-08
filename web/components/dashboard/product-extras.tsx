"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { Switch } from "../overlay";
import { useToast } from "../toast";
import { Button, ErrorText, Field, Input, Select, Textarea } from "../ui";
import { EditorSection } from "./product-editor-bits";

export type Extras = { badge?: string; tags?: string[]; specs?: { k: string; v: string }[]; sizeGuide?: string; isFeatured?: boolean };

/** What makes a product sell: a badge, search tags, a specifications table, a size guide and "featured on the home page". Saved on its own. */
export function ProductExtras({ id, initial, onSaved }: { id: string; initial: Extras; onSaved: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const [f, setF] = useState<Extras>({ badge: initial.badge ?? "", tags: initial.tags ?? [], specs: initial.specs ?? [], sizeGuide: initial.sizeGuide ?? "", isFeatured: !!initial.isFeatured });
  const [dirty, setDirty] = useState(false);
  const save = useAction(async () => { await api(`store/products/${id}/extras`, { method: "PATCH", body: { ...f, specs: (f.specs ?? []).filter((s) => s.k.trim() && s.v.trim()) } }); setDirty(false); toast(t("common.saved")); onSaved(); });
  const specs = f.specs ?? [];
  const edit = (patch: Partial<Extras>) => { setF({ ...f, ...patch }); setDirty(true); };
  return (
    <EditorSection icon="sparkles" title={t("extras.title")} subtitle={t("cat.extrasSub")}>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t("extras.badge")}><Select value={f.badge ?? ""} onChange={(e) => edit({ badge: e.target.value })}><option value="">{t("extras.noBadge")}</option>{["NEW", "SALE", "BEST_SELLER", "LIMITED"].map((b) => <option key={b} value={b}>{t(`badge.${b}`)}</option>)}</Select></Field>
        <Field label={t("extras.tags")} hint={t("extras.tagsHint")}><Input value={(f.tags ?? []).join(", ")} onChange={(e) => edit({ tags: e.target.value.split(/[,،]/).map((x) => x.trim()).filter(Boolean) })} /></Field>
      </div>
      <div className="flex items-center justify-between gap-3 rounded-[12px] bg-surface-2 px-4 py-3">
        <span className="flex items-center gap-2 text-[13px] font-semibold text-ink"><Icon name="star" className="h-4 w-4 text-amber-500" />{t("extras.featured")}</span>
        <Switch checked={!!f.isFeatured} onChange={(v) => edit({ isFeatured: v })} label={t("extras.featured")} />
      </div>
      <div className="space-y-2">
        <p className="text-[13px] font-semibold text-ink-2">{t("extras.specs")}</p>
        {specs.map((s, i) => (
          <div key={i} className="grid grid-cols-[1fr_1fr_auto] gap-2">
            <Input placeholder={t("extras.specName")} value={s.k} onChange={(e) => edit({ specs: specs.map((x, j) => (j === i ? { ...x, k: e.target.value } : x)) })} />
            <Input placeholder={t("extras.specValue")} value={s.v} onChange={(e) => edit({ specs: specs.map((x, j) => (j === i ? { ...x, v: e.target.value } : x)) })} />
            <button type="button" onClick={() => edit({ specs: specs.filter((_, j) => j !== i) })} aria-label={t("common.remove")} className="grid h-10 w-10 place-items-center rounded-[10px] text-muted hover:bg-red-50 hover:text-red-600"><Icon name="close" className="h-4 w-4" /></button>
          </div>
        ))}
        <Button type="button" variant="secondary" size="sm" onClick={() => edit({ specs: [...specs, { k: "", v: "" }] })}><Icon name="plus" className="h-3.5 w-3.5" />{t("extras.addSpec")}</Button>
      </div>
      <Field label={t("product.sizeGuide")}><Textarea rows={3} value={f.sizeGuide ?? ""} onChange={(e) => edit({ sizeGuide: e.target.value })} /></Field>
      <ErrorText error={save.error} />
      <div className="flex justify-end"><Button type="button" variant="secondary" loading={save.loading} disabled={!dirty} onClick={() => void save.run()}>{t("cat.saveExtras")}</Button></div>
    </EditorSection>
  );
}
