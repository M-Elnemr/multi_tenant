"use client";

import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { GOVERNORATES } from "@/lib/governorates";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { useToast } from "../toast";
import { Button, ErrorText } from "../ui";
import { EditorSection } from "./product-editor-bits";

export type LimitVariant = { id: string; comboKey: string; status: string; deliveryOnly?: string | null };

const parts = (combo: string) => combo.split("|").filter(Boolean).map((kv) => { const i = kv.indexOf("="); return i > 0 ? [kv.slice(0, i), kv.slice(i + 1)] as const : ["", kv] as const; });
const label = (combo: string) => parts(combo).map(([, v]) => v).join(" · ") || "—";

/**
 * Where this product (or some of its sizes) is delivered when it is not everywhere the shop delivers: a TV of 50 inches and up by the shop's own truck to Cairo and Giza only.
 * The AI never offers it elsewhere, and the checkout refuses an address outside these governorates. Saved on its own.
 */
export function DeliveryLimit({ id, variants, onSaved }: { id: string; variants: LimitVariant[]; onSaved: () => void }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const active = useMemo(() => variants.filter((v) => v.status === "ACTIVE"), [variants]);
  const limited = active.filter((v) => v.deliveryOnly);
  const [areas, setAreas] = useState<string[]>(limited[0]?.deliveryOnly?.split(",").filter(Boolean) ?? []);
  const [scope, setScope] = useState<"all" | "some">(limited.length > 0 && limited.length < active.length ? "some" : "all");
  const [picked, setPicked] = useState<string[]>(limited.map((v) => v.id));
  const [dirty, setDirty] = useState(false);
  const many = active.length > 1;
  const values = useMemo(() => {   // every option value, to pick all its variants at once («65 بوصة»)
    const m = new Map<string, string[]>();
    for (const v of active) for (const [, val] of parts(v.comboKey)) m.set(val, [...(m.get(val) ?? []), v.id]);
    return [...m.entries()].filter(([, ids]) => ids.length < active.length);
  }, [active]);
  const toggle = <T,>(list: T[], x: T) => (list.includes(x) ? list.filter((y) => y !== x) : [...list, x]);
  const save = useAction(async () => {
    await api(`store/products/${id}/extras`, { method: "PATCH", body: { deliveryOnly: { areas, variantIds: many && scope === "some" ? picked : null } } });
    setDirty(false);
    toast(t("common.saved"));
    onSaved();
  });
  const chip = (on: boolean) => `rounded-full border px-3 py-1.5 text-[12.5px] font-semibold transition-colors ${on ? "border-brand bg-brand text-white" : "border-line bg-surface text-ink-2 hover:border-brand/50"}`;
  return (
    <EditorSection icon="truck" title={t("dlim.title")} subtitle={t("dlim.sub")}>
      <div className="space-y-2">
        <p className="text-[13px] font-semibold text-ink-2">{t("dlim.areas")}</p>
        <div className="flex flex-wrap gap-2">
          {GOVERNORATES.map((g) => (
            <button key={g.code} type="button" aria-pressed={areas.includes(g.code)} className={chip(areas.includes(g.code))} onClick={() => { setAreas(toggle(areas, g.code)); setDirty(true); }}>
              {locale === "ar" ? g.ar : g.en}
            </button>
          ))}
        </div>
        <p className="text-[12px] text-muted">{areas.length === 0 ? t("dlim.none") : t("dlim.some")}</p>
      </div>
      {many && areas.length > 0 && (
        <div className="space-y-3">
          <div className="flex flex-wrap gap-2">
            <button type="button" className={chip(scope === "all")} aria-pressed={scope === "all"} onClick={() => { setScope("all"); setDirty(true); }}>{t("dlim.allVariants")}</button>
            <button type="button" className={chip(scope === "some")} aria-pressed={scope === "some"} onClick={() => { setScope("some"); setDirty(true); }}>{t("dlim.someVariants")}</button>
          </div>
          {scope === "some" && (
            <>
              {values.length > 0 && (
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-[12px] text-muted">{t("dlim.quick")}</span>
                  {values.map(([val, ids]) => {
                    const on = ids.every((x) => picked.includes(x));
                    return (
                      <button key={val} type="button" aria-pressed={on} className={chip(on)} onClick={() => { setPicked(on ? picked.filter((x) => !ids.includes(x)) : [...new Set([...picked, ...ids])]); setDirty(true); }}>{val}</button>
                    );
                  })}
                </div>
              )}
              <div className="grid gap-2 sm:grid-cols-2">
                {active.map((v) => (
                  <label key={v.id} className="flex cursor-pointer items-center gap-2 rounded-[10px] bg-surface-2 px-3 py-2 text-[13px] text-ink">
                    <input type="checkbox" className="h-4 w-4 accent-[var(--brand)]" checked={picked.includes(v.id)} onChange={() => { setPicked(toggle(picked, v.id)); setDirty(true); }} />
                    <span className="truncate">{label(v.comboKey)}</span>
                  </label>
                ))}
              </div>
            </>
          )}
        </div>
      )}
      <ErrorText error={save.error} />
      <div className="flex justify-end">
        <Button type="button" variant="secondary" loading={save.loading} disabled={!dirty || (many && scope === "some" && areas.length > 0 && picked.length === 0)} onClick={() => void save.run()}>{t("dlim.save")}</Button>
      </div>
    </EditorSection>
  );
}
