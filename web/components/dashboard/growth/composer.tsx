"use client";

import { api } from "@/lib/client";
import { useAction } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { Button, ErrorText, Field, Input, Textarea } from "../../ui";
import MessageComposer from "../message-composer";
import { AI_SOFT, SwitchRow } from "../marketing/kit";

export type Draft = { msg: string; image: string | null; versions: string[]; push: boolean; pushTitle: string };

/** Step 2: the message, optional wording variants (AI can suggest them) and the optional phone notification. */
export default function ComposerStep({ draft, onDraft }: { draft: Draft; onDraft: (d: Draft) => void }) {
  const { t } = useI18n();
  const set = (p: Partial<Draft>) => onDraft({ ...draft, ...p });
  const suggest = useAction(async () => {
    const r = await api<{ variants: string[] }>("store/broadcasts/variants", { body: { message: draft.msg, count: 3 } });
    set({ versions: [...draft.versions, ...r.variants].slice(0, 5) });
  });
  return (
    <div className="space-y-4">
      <MessageComposer text={draft.msg} onText={(v) => set({ msg: v })} imageId={draft.image} onImage={(v) => set({ image: v })} />
      <div className={`space-y-3 rounded-[14px] p-4 ring-1 ring-violet-200/60 ${AI_SOFT}`}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div><p className="flex items-center gap-1.5 text-[13.5px] font-semibold text-ink"><Icon name="sparkles" className="h-4 w-4 text-violet-500" />{t("gr.versions")}</p><p className="mt-0.5 text-[12px] text-muted">{t("gr.versionsHint")}</p></div>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="secondary" disabled={draft.versions.length >= 5} onClick={() => set({ versions: [...draft.versions, ""] })}><Icon name="plus" className="h-4 w-4" />{t("gr.addVersion")}</Button>
            <Button size="sm" variant="ink" loading={suggest.loading} disabled={draft.msg.trim().length < 3 || draft.versions.length >= 5} onClick={() => void suggest.run()}><Icon name="sparkles" className="h-4 w-4" />{t("gr.suggestVersions")}</Button>
          </div>
        </div>
        {draft.versions.map((v, i) => (
          <div key={i} className="flex items-start gap-2">
            <span className="mt-2 grid h-6 w-6 shrink-0 place-items-center rounded-full bg-surface text-[11px] font-bold text-muted ring-1 ring-line">{i + 2}</span>
            <Textarea rows={2} maxLength={1000} value={v} onChange={(e) => set({ versions: draft.versions.map((x, k) => (k === i ? e.target.value : x)) })} />
            <button type="button" onClick={() => set({ versions: draft.versions.filter((_, k) => k !== i) })} className="mt-1 grid h-9 w-9 shrink-0 place-items-center rounded-[10px] text-faint hover:bg-red-50 hover:text-red-600" aria-label={t("common.delete")}><Icon name="close" className="h-4 w-4" /></button>
          </div>
        ))}
        <ErrorText error={suggest.error} />
      </div>
      <SwitchRow title={<span className="inline-flex items-center gap-1.5"><Icon name="mobile" className="h-4 w-4" />{t("gr.alsoPush")}</span>} checked={draft.push} onChange={(v) => set({ push: v })} />
      {draft.push && <Field label={t("gr.pushTitle")} hint={t("gr.pushTitleHint")}><Input maxLength={80} value={draft.pushTitle} onChange={(e) => set({ pushTitle: e.target.value })} /></Field>}
    </div>
  );
}
