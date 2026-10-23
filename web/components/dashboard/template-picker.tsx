"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useT } from "../i18n-provider";
import { Alert, Button, Card, ErrorText } from "../ui";
import { TEMPLATE_IDS, type TemplateId } from "../shop/templates/ids";

/** Mini wireframes: header, hero and a row of cards in each template's own palette and proportions. */
const LOOK: Record<TemplateId, { bg: string; bar: string; hero: string; card: string; r: number; cols: number; center?: boolean; dark?: boolean }> = {
  original: { bg: "#f8fafc", bar: "#ffffff", hero: "#c7d2fe", card: "#ffffff", r: 8, cols: 4 },
  atelier: { bg: "#ffffff", bar: "#ffffff", hero: "#d6d3d1", card: "#e7e5e4", r: 0, cols: 3, center: true },
  souq: { bg: "#f1f5f9", bar: "#e11d48", hero: "#fecdd3", card: "#ffffff", r: 4, cols: 5 },
  midnight: { bg: "#0b1020", bar: "#151b2b", hero: "#3b2f7a", card: "#1a2133", r: 10, cols: 3, dark: true },
  bloom: { bg: "#fff1f5", bar: "#ffffff", hero: "#fbcfe8", card: "#ffffff", r: 16, cols: 3 },
  oud: { bg: "#faf5e9", bar: "#111111", hero: "#d9c28a", card: "#fffdf7", r: 3, cols: 2, center: true },
};

function Thumb({ id }: { id: TemplateId }) {
  const l = LOOK[id];
  return (
    <div className="flex aspect-[4/3] w-full flex-col gap-1 overflow-hidden rounded-lg border border-slate-200 p-1.5" style={{ background: l.bg }} aria-hidden>
      <div className={`flex h-2.5 items-center gap-1 px-1 ${l.center ? "justify-center" : "justify-between"}`} style={{ background: l.bar, borderRadius: Math.min(l.r, 6) }}>
        <i className="h-1 w-4 rounded-full" style={{ background: l.dark ? "#a5b4fc" : "#94a3b8" }} />
        {!l.center && <i className="h-1 w-6 rounded-full" style={{ background: l.dark ? "#334155" : "#e2e8f0" }} />}
      </div>
      <div className="h-[34%]" style={{ background: l.hero, borderRadius: l.r }} />
      <div className="grid flex-1 gap-1" style={{ gridTemplateColumns: `repeat(${l.cols}, 1fr)` }}>
        {Array.from({ length: l.cols }).map((_, i) => (
          <div key={i} style={{ background: l.card, borderRadius: Math.min(l.r, 8), border: id === "oud" ? "1px solid #c9a24a" : undefined }} />
        ))}
      </div>
    </div>
  );
}

/** Settings > Look of the store: pick one of the storefront templates, or preview it first. Content, cart and checkout are the same in all of them. */
export default function TemplatePicker() {
  const t = useT();
  const { data, reload } = useApi<{ template?: string }>("store/profile");
  const [saved, setSaved] = useState(false);
  const save = useAction(async (template: string) => { setSaved(false); await api("store/profile", { method: "PATCH", body: { template } }); setSaved(true); await reload(); });
  const current = (data?.template ?? "original") as TemplateId;
  if (!data) return null;
  return (
    <Card className="space-y-4">
      <div>
        <h2 className="font-medium">{t("tpl.title")}</h2>
        <p className="mt-1 text-sm text-slate-500">{t("tpl.hint")}</p>
      </div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        {TEMPLATE_IDS.map((id) => (
          <div key={id} className={`space-y-2 rounded-xl border p-2.5 ${id === current ? "border-brand ring-2 ring-brand/30" : "border-slate-200"}`}>
            <Thumb id={id} />
            <div>
              <div className="text-sm font-semibold">{t(`tpl.${id}`)}</div>
              <div className="text-xs text-slate-500">{t(`tpl.${id}.for`)}</div>
            </div>
            <div className="flex flex-wrap gap-2">
              {id === current ? (
                <span className="rounded-full bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-700">{t("tpl.current")}</span>
              ) : (
                <Button loading={save.loading} onClick={() => void save.run(id)}>{t("tpl.use")}</Button>
              )}
              <a href={`/?tpl=${id}`} target="_blank" rel="noreferrer" className="inline-flex items-center rounded-xl border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">{t("tpl.preview")}</a>
            </div>
          </div>
        ))}
      </div>
      <ErrorText error={save.error} />
      {saved && <Alert tone="green">{t("common.saved")}</Alert>}
    </Card>
  );
}
