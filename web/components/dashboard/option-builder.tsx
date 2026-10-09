"use client";

import { useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Button, Input, Select } from "../ui";

export type OptionDraft = { kind: "COLOR" | "SIZE" | "CUSTOM"; scale: string; name: string; values: string[]; custom: string };
type Val = { code: string; nameAr: string; nameEn: string; name: string; hex?: string | null };
type Attrs = { colors: Val[]; sizes: Record<string, Val[]> };

/** What goes to the API: standard options carry their type; custom ones are plain name + values. */
export function toRequest(o: OptionDraft) {
  if (o.kind === "COLOR") return { name: "اللون", attribute: "COLOR", values: o.values };
  if (o.kind === "SIZE") return { name: "المقاس", attribute: "SIZE", sizeScale: o.scale, values: o.values };
  return { name: o.name.trim(), values: o.custom.split(/[,،]/).map((v) => v.trim()).filter(Boolean) };
}

/** Colour and size are picked from the standard lists (swatches, size chips); anything else is free text. */
export function OptionBuilder({ options, onChange, scales }: { options: OptionDraft[]; onChange: (o: OptionDraft[]) => void; scales: string[] }) {
  const { t, locale } = useI18n();
  const attrs = useApi<Attrs>(`store/taxonomy/attributes?lang=${locale}`);
  const set = (i: number, patch: Partial<OptionDraft>) => onChange(options.map((o, k) => (k === i ? { ...o, ...patch } : o)));
  const allScales = scales.length ? scales : ["APPAREL", "SHOE", "KIDS", "BED"];
  const toggle = (i: number, v: string) => { const cur = options[i].values; set(i, { values: cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v] }); };
  const add = (kind: OptionDraft["kind"]) => onChange([...options, { kind, scale: allScales[0] ?? "APPAREL", name: "", values: [], custom: "" }]);
  const used = new Set(options.map((o) => o.kind));
  return (
    <div className="space-y-4">
      {options.map((o, i) => (
        <div key={i} className="space-y-2 rounded-xl border p-3">
          <div className="flex items-center justify-between"><p className="text-sm font-semibold">{o.kind === "COLOR" ? t("options.color") : o.kind === "SIZE" ? t("options.size") : t("options.custom")}</p><Button type="button" variant="ghost" size="sm" onClick={() => onChange(options.filter((_, k) => k !== i))}>✕</Button></div>
          {o.kind === "COLOR" && (
            <div className="flex flex-wrap gap-2">{attrs.data?.colors.map((c) => {
              const on = o.values.includes(c.nameAr);
              return <button key={c.code} type="button" onClick={() => toggle(i, c.nameAr)} className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-sm transition ${on ? "border-brand bg-brand-soft font-semibold" : "border-slate-300 hover:border-brand"}`}>
                <span className="h-4 w-4 rounded-full border border-black/15" style={{ background: c.hex ?? "conic-gradient(red, yellow, lime, aqua, blue, magenta, red)" }} />{c.name}</button>;
            })}</div>
          )}
          {o.kind === "SIZE" && (
            <>
              {allScales.length > 1 && <Select className="max-w-60" value={o.scale} onChange={(e) => set(i, { scale: e.target.value, values: [] })}>{allScales.map((sc) => <option key={sc} value={sc}>{t(`scale.${sc}`)}</option>)}</Select>}
              <div className="flex flex-wrap gap-2">{(attrs.data?.sizes[o.scale] ?? []).map((z) => {
                const on = o.values.includes(z.nameAr);
                return <button key={z.code} type="button" onClick={() => toggle(i, z.nameAr)} className={`rounded-full border px-3.5 py-1.5 text-sm transition ${on ? "border-brand bg-brand text-white" : "border-slate-300 hover:border-brand"}`}>{z.name}</button>;
              })}</div>
            </>
          )}
          {o.kind === "CUSTOM" && (
            <div className="grid gap-3 sm:grid-cols-[1fr_2fr]">
              <Input placeholder={t("products.optionName")} value={o.name} onChange={(e) => set(i, { name: e.target.value })} />
              <Input placeholder={t("products.optionValues")} value={o.custom} onChange={(e) => set(i, { custom: e.target.value })} />
            </div>
          )}
        </div>
      ))}
      <div className="flex flex-wrap gap-2">
        {!used.has("COLOR") && <Button type="button" variant="secondary" size="sm" onClick={() => add("COLOR")}>+ {t("options.color")}</Button>}
        {!used.has("SIZE") && <Button type="button" variant="secondary" size="sm" onClick={() => add("SIZE")}>+ {t("options.size")}</Button>}
        <Button type="button" variant="ghost" size="sm" onClick={() => add("CUSTOM")}>+ {t("options.custom")}</Button>
      </div>
    </div>
  );
}
