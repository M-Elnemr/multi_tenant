"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input, StatusBadge } from "@/components/ui";
import { dateTime, money } from "@/lib/format";

type Overview = {
  settings: { enabled: boolean; confirmAfterHours: number; reminderEnabled: boolean; reminderAfterHours: number; brandName?: string | null };
  readiness: { channelConnected: boolean; wabaKnown: boolean; templatesApproved: boolean; hasCredit: boolean };
  templates: { name: string; status: string; rejectedReason?: string }[];
  balanceMinor: number; priceMinor: number;
  topups: { id: string; amountMinor: number; status: string; adminNote?: string; createdAt: string }[];
};
type Msg = { kind: string; status: string; costMinor: number; error?: string; sentAt?: string; createdAt: string; orderNumber: string };

const Check = ({ ok, label }: { ok: boolean; label: string }) => (
  <li className="flex items-center gap-2 text-sm"><span className={`flex h-5 w-5 items-center justify-center rounded-full text-xs font-bold text-white ${ok ? "bg-emerald-500" : "bg-slate-300"}`}>{ok ? "✓" : "·"}</span><span className={ok ? "" : "text-slate-500"}>{label}</span></li>
);

/** Automatic WhatsApp order confirmation: settings, what is still missing, prepaid credit and the log of sent messages. */
export function AiWhatsApp({ write }: { write: boolean }) {
  const { t, locale } = useI18n();
  const { data, reload } = useApi<Overview>("store/ai/whatsapp");
  const log = useApi<Msg[]>("store/ai/whatsapp/messages");
  const [edit, setEdit] = useState<Partial<Overview["settings"]>>({});
  const [saved, setSaved] = useState(false);
  const [amount, setAmount] = useState(10000);
  const f = data ? { ...data.settings, ...edit } : null;

  const save = useAction(async () => {
    if (!f) return;
    await api("store/ai/whatsapp", { method: "PUT", body: { enabled: f.enabled, confirmAfterHours: f.confirmAfterHours, reminderEnabled: f.reminderEnabled, reminderAfterHours: f.reminderAfterHours, brandName: f.brandName ?? "" } });
    setEdit({}); setSaved(true); await reload();
  });
  const templates = useAction(async (path: string) => { await api(`store/ai/whatsapp/${path}`, { body: {} }); await reload(); });
  const topup = useAction(async () => { await api("store/ai/whatsapp/topups", { body: { amountMinor: amount } }); await reload(); });
  if (!data || !f) return null;
  const ready = Object.values(data.readiness).every(Boolean);
  const price = money(data.priceMinor, "EGP", locale);

  return (
    <div className="max-w-2xl space-y-5">
      <Alert tone="blue">{t("wa.intro", { price })}</Alert>

      <section className="space-y-2 rounded-xl border bg-white p-4">
        <h3 className="font-semibold">{t("wa.readiness")}</h3>
        <ul className="space-y-1.5">
          <Check ok={data.readiness.channelConnected} label={t("wa.r.channel")} />
          <Check ok={data.readiness.wabaKnown} label={t("wa.r.waba")} />
          <Check ok={data.readiness.templatesApproved} label={t("wa.r.templates")} />
          <Check ok={data.readiness.hasCredit} label={t("wa.r.credit")} />
        </ul>
        {!ready && <p className="text-xs text-slate-500">{t("wa.r.note")}</p>}
      </section>

      <form onSubmit={(e) => { e.preventDefault(); setSaved(false); void save.run(); }} className="space-y-4 rounded-xl border bg-white p-4">
        <fieldset disabled={!write} className="space-y-4 disabled:opacity-70">
          <label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" checked={f.enabled} onChange={(e) => setEdit({ ...edit, enabled: e.target.checked })} />{t("wa.enable")}</label>
          <Field label={t("wa.firstAfter")} hint={t("wa.firstAfterHint")}><Input type="number" min={1} max={72} value={f.confirmAfterHours} onChange={(e) => setEdit({ ...edit, confirmAfterHours: Math.max(1, Math.min(72, Number(e.target.value) || 1)) })} /></Field>
          <label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" checked={f.reminderEnabled} onChange={(e) => setEdit({ ...edit, reminderEnabled: e.target.checked })} />{t("wa.reminder")}</label>
          {f.reminderEnabled && <Field label={t("wa.reminderAfter")} hint={t("wa.reminderAfterHint")}><Input type="number" min={1} max={72} value={f.reminderAfterHours} onChange={(e) => setEdit({ ...edit, reminderAfterHours: Math.max(1, Math.min(72, Number(e.target.value) || 1)) })} /></Field>}
          <Field label={t("wa.brand")} hint={t("wa.brandHint")}><Input maxLength={40} value={f.brandName ?? ""} onChange={(e) => setEdit({ ...edit, brandName: e.target.value })} /></Field>
        </fieldset>
        <ErrorText error={save.error} />
        {saved && <Alert tone="green">{t("ai.saved")}</Alert>}
        {write && <Button type="submit" loading={save.loading}>{t("common.save")}</Button>}
      </form>

      <section className="space-y-3 rounded-xl border bg-white p-4">
        <div className="flex items-center justify-between"><h3 className="font-semibold">{t("wa.credit")}</h3><p className="text-2xl font-extrabold text-brand">{money(data.balanceMinor, "EGP", locale)}</p></div>
        {write && (
          <div className="flex flex-wrap items-end gap-2">
            {[5000, 10000, 20000, 50000].map((a) => <button key={a} type="button" onClick={() => setAmount(a)} className={`rounded-full border px-3 py-1.5 text-sm font-semibold ${amount === a ? "border-brand bg-brand-soft text-brand" : ""}`}>{money(a, "EGP", locale)}</button>)}
            <Input className="w-28" type="number" min={10} value={Math.round(amount / 100)} onChange={(e) => setAmount(Math.max(0, Number(e.target.value) || 0) * 100)} aria-label={t("wa.amount")} />
            <Button onClick={() => void topup.run()} loading={topup.loading} disabled={amount < 1000}>{t("wa.requestTopup")}</Button>
          </div>
        )}
        <ErrorText error={topup.error} />
        <p className="text-xs text-slate-500">{t("wa.topupHint")}</p>
        <ul className="space-y-1 text-sm">{data.topups.map((r) => <li key={r.id} className="flex items-center justify-between gap-2 rounded-lg bg-slate-50 px-3 py-2"><span>{money(r.amountMinor, "EGP", locale)} · <span className="text-xs text-slate-500">{dateTime(r.createdAt, locale)}</span></span><StatusBadge status={r.status} /></li>)}</ul>
      </section>

      <section className="space-y-3 rounded-xl border bg-white p-4">
        <h3 className="font-semibold">{t("wa.templates")}</h3>
        <ul className="space-y-1 text-sm">{data.templates.length === 0 && <li className="text-slate-500">{t("wa.noTemplates")}</li>}{data.templates.map((x) => <li key={x.name} className="flex items-center justify-between gap-2"><span className="font-mono text-xs" dir="ltr">{x.name}</span><span className="flex items-center gap-2"><StatusBadge status={x.status} />{x.rejectedReason && <span className="text-xs text-red-600">{x.rejectedReason}</span>}</span></li>)}</ul>
        {write && <div className="flex gap-2"><Button size="sm" onClick={() => void templates.run("templates")} loading={templates.loading}>{t("wa.submitTemplates")}</Button><Button size="sm" variant="ghost" onClick={() => void templates.run("templates/refresh")}>{t("wa.refresh")}</Button></div>}
        <ErrorText error={templates.error} />
      </section>

      <section className="space-y-2 rounded-xl border bg-white p-4">
        <h3 className="font-semibold">{t("wa.log")}</h3>
        {log.data?.length === 0 && <p className="text-sm text-slate-500">{t("wa.noLog")}</p>}
        <ul className="space-y-1 text-sm">{log.data?.map((m, i) => <li key={i} className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-slate-50 px-3 py-2"><span><span className="font-mono text-xs" dir="ltr">{m.orderNumber}</span> · {t(`wa.kind.${m.kind}`)}</span><span className="flex items-center gap-2 text-xs"><StatusBadge status={m.status} />{m.costMinor > 0 && <span>{money(m.costMinor, "EGP", locale)}</span>}{m.error && <span className="text-red-600">{m.error}</span>}</span></li>)}</ul>
      </section>
    </div>
  );
}
