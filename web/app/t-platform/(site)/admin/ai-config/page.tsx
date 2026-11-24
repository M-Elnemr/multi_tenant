"use client";

import { useState } from "react";
import { useApi } from "@/components/hooks";
import { api, ApiError } from "@/lib/client";
import { useI18n } from "@/components/i18n-provider";
import { useToast } from "@/components/toast";
import { ConfirmDialog } from "@/components/overlay";
import { Badge, Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, Section, Select } from "@/components/ui";

type Option = { code: string; label: string; note: string | null; default: boolean };
type Choice = { options: Option[]; selected: string; default: string; isDefault: boolean };
type Key = { id: string; label: string; last4: string; fromEnvironment: boolean; enabled: boolean; requests: number; failures: number; lastStatus: string; restingSeconds: number };
type Config = { text: Choice; image: Choice; audio: Choice; promptMode: Choice; deepseekEffort: Choice; optimization: Choice; keys: Key[]; activeKeys: number; requestsPerSecond: number };
type Setting = "textModel" | "imageModel" | "audioModel" | "promptMode" | "deepseekEffort" | "optimization";
type Layout = Record<string, number | string | null> & { mode: string; topChecks: { kind: string; count: number }[] };
type Compare = { days: number; source: string; layouts: Layout[] };

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
          <OptimizationCard choice={c.optimization} busy={saving === "optimization"} onPick={(v) => choose("optimization", v)} />
          <CompareSection />
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
            <p className="mb-3 text-sm font-semibold text-ink">{t("aic.keys.rate", { n: c.activeKeys, r: c.requestsPerSecond })}</p>
            {c.keys.length === 0 ? <p className="text-sm text-muted">{t("aic.keys.none")}</p> : (
              <ul className="divide-y divide-line rounded-[18px] border border-line bg-surface">
                {c.keys.map((k) => (
                  <li key={k.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                    <div className="min-w-0 flex-1">
                      <p className="flex flex-wrap items-center gap-2 text-sm font-bold text-ink">
                        {k.label} <span dir="ltr" className="font-mono text-xs font-medium text-muted">••••{k.last4}</span>
                        {k.fromEnvironment && <Badge tone="slate">{t("aic.keys.env")}</Badge>}
                        {k.restingSeconds > 0 && <Badge tone="amber">{t("aic.keys.resting", { s: k.restingSeconds })}</Badge>}
                      </p>
                      <p className="mt-0.5 text-xs text-muted">
                        {t("aic.keys.requests")}: <span className="tnum">{k.requests}</span> · {t("aic.keys.failures")}: <span className="tnum">{k.failures}</span>{k.lastStatus ? ` · ${k.lastStatus}` : ""}
                        {k.fromEnvironment ? ` · ${t("aic.keys.envHint")}` : ""}
                      </p>
                      {tests[k.id] && <p className="mt-0.5 text-xs font-semibold text-ink">{tests[k.id]}</p>}
                    </div>
                    <Button size="sm" variant="secondary" onClick={() => test(k)}>{t("aic.keys.test")}</Button>
                    {!k.fromEnvironment && (
                      <>
                        <label className="flex items-center gap-2 text-sm font-medium text-ink">
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
      <h2 className="text-[15px] font-bold text-ink">{title}</h2>
      <p className="mt-0.5 text-xs text-muted">{hint}</p>
      <div role="radiogroup" aria-label={title} className="mt-3 space-y-2">
        {choice.options.map((o) => {
          const on = o.code === choice.selected;
          return (
            <button key={o.code} type="button" role="radio" aria-checked={on} disabled={busy} onClick={() => !on && onPick(o.code)}
              className={`flex w-full items-center gap-3 rounded-[14px] border px-3.5 py-3 text-start transition ${on ? "border-[var(--brand)] bg-[color-mix(in_srgb,var(--brand)_8%,white)]" : "border-line bg-surface hover:border-line-strong"} disabled:opacity-60`}>
              <span className={`grid h-5 w-5 shrink-0 place-items-center rounded-full border-2 ${on ? "border-[var(--brand)]" : "border-line-strong"}`}>{on && <span className="h-2.5 w-2.5 rounded-full bg-[var(--brand)]" />}</span>
              <span className="min-w-0 flex-1 text-sm font-semibold text-ink">{describe ? describe(o) : o.label}</span>
              {o.default && <Badge tone="green">{t("aic.default")}</Badge>}
            </button>
          );
        })}
      </div>
      {note && <p className="mt-2 text-xs text-muted">{note}</p>}
      {!choice.isDefault && <button type="button" disabled={busy} onClick={() => onPick("default")} className="mt-3 text-xs font-semibold text-[var(--brand)] hover:underline">{t("aic.reset")}</button>}
    </Card>
  );
}

/** The admin's switch between the classic and the optimized AI request: a big on/off toggle, saved at once. */
function OptimizationCard({ choice, onPick, busy }: { choice: Choice; onPick: (v: string) => void; busy: boolean }) {
  const { t } = useI18n();
  const on = choice.selected === "on";
  return (
    <Card className="p-5">
      <div className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <h2 className="flex flex-wrap items-center gap-2 text-[15px] font-bold text-ink">
            {t("aic.opt")}
            <Badge tone={on ? "green" : "slate"}>{t("aic.opt.live", { m: on ? t("aic.cmp.optimized") : t("aic.cmp.classic") })}</Badge>
          </h2>
          <p className="mt-1 max-w-3xl text-xs leading-relaxed text-muted">{t("aic.opt.hint")}</p>
        </div>
        <button type="button" role="switch" aria-checked={on} aria-label={t("aic.opt")} disabled={busy} onClick={() => onPick(on ? "off" : "on")}
          className={`relative inline-flex h-8 w-14 shrink-0 items-center rounded-full transition disabled:opacity-60 ${on ? "bg-[var(--brand)]" : "bg-line-strong"}`}>
          <span className={`inline-block h-6 w-6 rounded-full bg-white shadow transition-transform ${on ? "translate-x-7 rtl:-translate-x-7" : "translate-x-1 rtl:-translate-x-1"}`} />
        </button>
      </div>
      <p className="mt-3 text-sm font-semibold text-ink">{on ? t("aic.opt.on") : t("aic.opt.off")}</p>
    </Card>
  );
}

/** Rows of the before/after table: the field, how to show it, and which direction is better. */
const ROWS: { key: string; label: string; fmt: "n" | "pct" | "ms" | "x"; better: "low" | "high" | null }[] = [
  { key: "replies", label: "aic.cmp.replies", fmt: "n", better: null },
  { key: "calls_per_reply", label: "aic.cmp.calls", fmt: "x", better: "low" },
  { key: "tokens_in", label: "aic.cmp.tokensIn", fmt: "n", better: "low" },
  { key: "tokens_in_per_call", label: "aic.cmp.tokensCall", fmt: "n", better: "low" },
  { key: "cached_pct", label: "aic.cmp.cached", fmt: "pct", better: "high" },
  { key: "tokens_out", label: "aic.cmp.tokensOut", fmt: "n", better: "low" },
  { key: "reply_ms_p50", label: "aic.cmp.p50", fmt: "ms", better: "low" },
  { key: "reply_ms_p90", label: "aic.cmp.p90", fmt: "ms", better: "low" },
  { key: "rewrite_pct", label: "aic.cmp.rewrite", fmt: "pct", better: "low" },
  { key: "review_pct", label: "aic.cmp.review", fmt: "pct", better: null },
  { key: "second_opinion_pct", label: "aic.cmp.second", fmt: "pct", better: "low" },
  { key: "second_opinions_skipped", label: "aic.cmp.skipped", fmt: "n", better: null },
  { key: "failed_pct", label: "aic.cmp.failed", fmt: "pct", better: "low" },
  { key: "static_chars", label: "aic.cmp.static", fmt: "n", better: null },
  { key: "dynamic_chars", label: "aic.cmp.dynamic", fmt: "n", better: null },
  { key: "history_chars", label: "aic.cmp.history", fmt: "n", better: null },
];

function show(v: number | string | null | undefined, fmt: string) {
  if (v === null || v === undefined || v === "") return "–";
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  if (fmt === "pct") return `${n.toFixed(1)}%`;
  if (fmt === "ms") return `${(n / 1000).toFixed(1)} s`;
  if (fmt === "x") return n.toFixed(2);
  return Math.round(n).toLocaleString("en");
}

/** Classic vs optimized side by side, from the reply traces: the numbers that say whether the switch helped. */
function CompareSection() {
  const { t } = useI18n();
  const [days, setDays] = useState("7");
  const [source, setSource] = useState("ALL");
  const cmp = useApi<Compare>(`platform/ai-config/compare?days=${days}&source=${source}`);
  const by = (m: string) => cmp.data?.layouts.find((l) => l.mode === m);
  const a = by("classic"), b = by("optimized");
  return (
    <Section title={t("aic.cmp")} subtitle={t("aic.cmp.hint")} action={
      <div className="flex flex-wrap gap-2">
        <Select aria-label={t("aic.cmp.days")} value={days} onChange={(e) => setDays(e.target.value)}>
          <option value="1">{t("aic.cmp.day1")}</option><option value="7">{t("aic.cmp.day7")}</option><option value="30">{t("aic.cmp.day30")}</option>
        </Select>
        <Select aria-label={t("aic.cmp.source")} value={source} onChange={(e) => setSource(e.target.value)}>
          {["ALL", "REPLY", "TEST"].map((s) => <option key={s} value={s}>{t(`aic.cmp.${s}`)}</option>)}
        </Select>
      </div>
    }>
      <ErrorText error={cmp.error} />
      {!cmp.data ? <Loading /> : !a && !b ? <p className="text-sm text-muted">{t("aic.cmp.none")}</p> : (
        <>
          <div className="overflow-x-auto rounded-[18px] border border-line bg-surface">
            <table className="w-full min-w-[520px] text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-semibold">{t("aic.cmp.metric")}</th>
                  <th className="px-4 py-2.5 text-end font-semibold">{t("aic.cmp.classic")}</th>
                  <th className="px-4 py-2.5 text-end font-semibold">{t("aic.cmp.optimized")}</th>
                  <th className="px-4 py-2.5 text-end font-semibold">{t("aic.cmp.change")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {ROWS.map((row) => {
                  const x = a ? Number(a[row.key]) : NaN, y = b ? Number(b[row.key]) : NaN;
                  const both = Number.isFinite(x) && Number.isFinite(y) && x !== 0 && row.key !== "replies";
                  const change = both ? ((y - x) / x) * 100 : null;
                  const good = change === null || row.better === null || Math.abs(change) < 1 ? null : (row.better === "low") === change < 0;
                  return (
                    <tr key={row.key}>
                      <td className="px-4 py-2.5 text-ink">{t(row.label)}{row.better && <span className="ms-1.5 text-[11px] text-muted">({t(row.better === "low" ? "aic.cmp.lower" : "aic.cmp.higher")})</span>}</td>
                      <td className="tnum px-4 py-2.5 text-end text-ink">{show(a?.[row.key], row.fmt)}</td>
                      <td className="tnum px-4 py-2.5 text-end font-semibold text-ink">{show(b?.[row.key], row.fmt)}</td>
                      <td className={`tnum px-4 py-2.5 text-end font-semibold ${good === null ? "text-muted" : good ? "text-green-600" : "text-red-600"}`} dir="ltr">
                        {change === null ? "–" : `${change > 0 ? "+" : ""}${change.toFixed(0)}%`}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="mt-4 grid gap-4 md:grid-cols-2">
            {[a, b].map((l, i) => (
              <div key={i} className="rounded-[14px] border border-line bg-surface p-4">
                <p className="text-xs font-bold text-ink">{t("aic.cmp.checks")} · {t(i === 0 ? "aic.cmp.classic" : "aic.cmp.optimized")}</p>
                {!l || l.topChecks.length === 0 ? <p className="mt-2 text-xs text-muted">–</p> : (
                  <ul className="mt-2 flex flex-wrap gap-1.5">
                    {l.topChecks.map((c) => <li key={c.kind}><Badge tone="slate"><span dir="ltr">{c.kind}</span> <span className="tnum">{c.count}</span></Badge></li>)}
                  </ul>
                )}
              </div>
            ))}
          </div>
        </>
      )}
    </Section>
  );
}
