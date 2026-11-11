"use client";

import { useState } from "react";
import { useApi } from "@/components/hooks";
import { api, ApiError } from "@/lib/client";
import { useI18n } from "@/components/i18n-provider";
import { useToast } from "@/components/toast";
import { ConfirmDialog } from "@/components/overlay";
import { Badge, Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, Section } from "@/components/ui";

type Option = { code: string; label: string; note: string | null; default: boolean };
type Choice = { options: Option[]; selected: string; default: string; isDefault: boolean };
type Key = { id: string; label: string; last4: string; fromEnvironment: boolean; enabled: boolean; requests: number; failures: number; lastStatus: string; restingSeconds: number };
type Config = { text: Choice; image: Choice; audio: Choice; promptMode: Choice; deepseekEffort: Choice; keys: Key[]; activeKeys: number; requestsPerSecond: number };
type Setting = "textModel" | "imageModel" | "audioModel" | "promptMode" | "deepseekEffort";

/** The platform admin's AI settings: the model for text, photos and voice notes (the default is preselected and tagged), prompt style, DeepSeek reasoning, and the Hive keys the requests are spread over. */
export default function AiConfigPage() {
  const { t } = useI18n();
  const toast = useToast();
  const cfg = useApi<Config>("platform/ai-config");
  const [saving, setSaving] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [label, setLabel] = useState("");
  const [secret, setSecret] = useState("");
  const [addError, setAddError] = useState<unknown>(null);
  const [removing, setRemoving] = useState<Key | null>(null);
  const [tests, setTests] = useState<Record<string, string>>({});

  const failed = (e: unknown) => toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" });

  async function choose(field: Setting, value: string) {
    setSaving(field);
    try { await api("platform/ai-config", { method: "PUT", body: { [field]: value } }); toast(t("aic.saved")); cfg.reload(); } catch (e) { failed(e); } finally { setSaving(null); }
  }

  async function addKey() {
    setAddError(null);
    try {
      await api("platform/ai-config/keys", { body: { label, key: secret } });
      setAdding(false); setLabel(""); setSecret(""); toast(t("aic.saved")); cfg.reload();
    } catch (e) { setAddError(e); }
  }

  async function toggle(k: Key) {
    try { await api(`platform/ai-config/keys/${k.id}`, { method: "PATCH", body: { enabled: !k.enabled } }); cfg.reload(); } catch (e) { failed(e); }
  }

  async function remove() {
    if (!removing) return;
    try { await api(`platform/ai-config/keys/${removing.id}`, { method: "DELETE" }); setRemoving(null); cfg.reload(); } catch (e) { failed(e); }
  }

  async function test(k: Key) {
    setTests((x) => ({ ...x, [k.id]: "…" }));
    try {
      const r = await api<{ ok: boolean; status: number; ms: number }>(`platform/ai-config/keys/${k.id}/test`, { body: {} });
      setTests((x) => ({ ...x, [k.id]: r.ok ? t("aic.keys.testOk", { ms: r.ms }) : t("aic.keys.testFail", { s: r.status || "–" }) }));
      cfg.reload();
    } catch (e) { setTests((x) => ({ ...x, [k.id]: "" })); failed(e); }
  }

  const c = cfg.data;
  return (
    <>
      <PageHeader title={t("aic.title")} subtitle={t("aic.hint")} />
      <ErrorText error={cfg.error} />
      {!c ? <Loading /> : (
        <div className="space-y-6">
          <div className="grid gap-4 lg:grid-cols-3">
            <ChoiceCard title={t("aic.text")} hint={t("aic.text.hint")} choice={c.text} busy={saving === "textModel"} onPick={(v) => choose("textModel", v)} />
            <ChoiceCard title={t("aic.image")} hint={t("aic.image.hint")} choice={c.image} busy={saving === "imageModel"} onPick={(v) => choose("imageModel", v)} />
            <ChoiceCard title={t("aic.audio")} hint={t("aic.audio.hint")} choice={c.audio} busy={saving === "audioModel"} onPick={(v) => choose("audioModel", v)} note={c.audio.options.length === 1 ? t("aic.audio.only") : undefined} />
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <ChoiceCard title={t("aic.prompt")} hint={t("aic.prompt.hint")} choice={c.promptMode} busy={saving === "promptMode"} onPick={(v) => choose("promptMode", v)} describe={(o) => t(`aic.prompt.${o.code}`)} />
            <ChoiceCard title={t("aic.effort")} hint={t("aic.effort.hint")} choice={c.deepseekEffort} busy={saving === "deepseekEffort"} onPick={(v) => choose("deepseekEffort", v)} describe={(o) => t(`aic.effort.${o.code}`)} />
          </div>

          <Section title={t("aic.keys")} subtitle={t("aic.keys.hint")} action={<Button size="sm" onClick={() => setAdding(true)}>{t("aic.keys.add")}</Button>}>
            <p className="mb-3 text-sm font-semibold text-slate-700">{t("aic.keys.rate", { n: c.activeKeys, r: c.requestsPerSecond })}</p>
            {c.keys.length === 0 ? <p className="text-sm text-slate-500">{t("aic.keys.none")}</p> : (
              <ul className="divide-y divide-slate-100 rounded-[18px] border border-slate-200 bg-white">
                {c.keys.map((k) => (
                  <li key={k.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                    <div className="min-w-0 flex-1">
                      <p className="flex flex-wrap items-center gap-2 text-sm font-bold text-slate-900">
                        {k.label} <span dir="ltr" className="font-mono text-xs font-medium text-slate-500">••••{k.last4}</span>
                        {k.fromEnvironment && <Badge tone="slate">{t("aic.keys.env")}</Badge>}
                        {k.restingSeconds > 0 && <Badge tone="amber">{t("aic.keys.resting", { s: k.restingSeconds })}</Badge>}
                      </p>
                      <p className="mt-0.5 text-xs text-slate-500">
                        {t("aic.keys.requests")}: <span className="tnum">{k.requests}</span> · {t("aic.keys.failures")}: <span className="tnum">{k.failures}</span>{k.lastStatus ? ` · ${k.lastStatus}` : ""}
                        {k.fromEnvironment ? ` · ${t("aic.keys.envHint")}` : ""}
                      </p>
                      {tests[k.id] && <p className="mt-0.5 text-xs font-semibold text-slate-700">{tests[k.id]}</p>}
                    </div>
                    <Button size="sm" variant="secondary" onClick={() => test(k)}>{t("aic.keys.test")}</Button>
                    {!k.fromEnvironment && (
                      <>
                        <label className="flex items-center gap-2 text-sm font-medium text-slate-700">
                          <input type="checkbox" checked={k.enabled} onChange={() => toggle(k)} className="h-4 w-4 accent-[var(--brand)]" /> {t("aic.keys.enabled")}
                        </label>
                        <Button size="sm" variant="danger" onClick={() => setRemoving(k)}>{t("common.delete")}</Button>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Section>
        </div>
      )}

      <Modal open={adding} onClose={() => setAdding(false)} title={t("aic.keys.add")}>
        <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); void addKey(); }}>
          <Field label={t("aic.keys.label")} hint={t("aic.keys.labelHint")}><Input value={label} onChange={(e) => setLabel(e.target.value)} maxLength={60} /></Field>
          <Field label={t("aic.keys.value")} hint={t("aic.keys.valueHint")}><Input dir="ltr" value={secret} onChange={(e) => setSecret(e.target.value)} autoComplete="off" spellCheck={false} /></Field>
          <ErrorText error={addError} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setAdding(false)}>{t("common.cancel")}</Button>
            <Button type="submit" disabled={secret.trim().length < 8}>{t("common.save")}</Button>
          </div>
        </form>
      </Modal>
      <ConfirmDialog open={!!removing} onClose={() => setRemoving(null)} onConfirm={remove} title={t("aic.keys.deleteTitle")} body={t("aic.keys.deleteBody")} confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} danger />
    </>
  );
}

/** One setting: the options as a radio list, the default tagged; the choice is saved as soon as it is picked. */
function ChoiceCard({ title, hint, choice, onPick, busy, note, describe }: { title: string; hint: string; choice: Choice; onPick: (v: string) => void; busy: boolean; note?: string; describe?: (o: Option) => string }) {
  const { t } = useI18n();
  return (
    <Card className="p-5">
      <h2 className="text-[15px] font-bold text-slate-900">{title}</h2>
      <p className="mt-0.5 text-xs text-slate-500">{hint}</p>
      <div role="radiogroup" aria-label={title} className="mt-3 space-y-2">
        {choice.options.map((o) => {
          const on = o.code === choice.selected;
          return (
            <button key={o.code} type="button" role="radio" aria-checked={on} disabled={busy} onClick={() => !on && onPick(o.code)}
              className={`flex w-full items-center gap-3 rounded-[14px] border px-3.5 py-3 text-start transition ${on ? "border-[var(--brand)] bg-[color-mix(in_srgb,var(--brand)_8%,white)]" : "border-slate-200 bg-white hover:border-slate-300"} disabled:opacity-60`}>
              <span className={`grid h-5 w-5 shrink-0 place-items-center rounded-full border-2 ${on ? "border-[var(--brand)]" : "border-slate-300"}`}>{on && <span className="h-2.5 w-2.5 rounded-full bg-[var(--brand)]" />}</span>
              <span className="min-w-0 flex-1 text-sm font-semibold text-slate-900">{describe ? describe(o) : o.label}</span>
              {o.default && <Badge tone="green">{t("aic.default")}</Badge>}
            </button>
          );
        })}
      </div>
      {note && <p className="mt-2 text-xs text-slate-500">{note}</p>}
      {!choice.isDefault && <button type="button" disabled={busy} onClick={() => onPick("default")} className="mt-3 text-xs font-semibold text-[var(--brand)] hover:underline">{t("aic.reset")}</button>}
    </Card>
  );
}
