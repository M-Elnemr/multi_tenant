"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime, money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Field, Input, Loading, Stat, Tabs } from "../ui";

type Item = { name: string; variant?: string | null; quantity: number; priceMinor: number };
type Cart = { id: string; name?: string | null; phone: string; items: Item[]; itemCount: number; totalMinor: number; currency: string; status: string; createdAt: string; contactedAt?: string | null; reminderStatus?: string | null; whatsappUrl: string };
type Stats = { dueCount: number; dueMinor: number; recentCount: number; recoveredCount: number; recoveredMinor: number; recoveryRatePercent: number };
type Settings = { recoveryEnabled: boolean; allowPaid: boolean; remindAfterHours: number; couponCode: string | null; messagePriceMinor: number };
type Res = { carts: Cart[]; stats: Stats; settings: Settings };

/** Carts that were not completed: shown 24 hours after they were started, with ways to reach the customer and a switch for reminders. Paid WhatsApp messages stay off unless the owner turns them on here. */
export default function CartsView({ canWrite }: { canWrite: boolean }) {
  const { t, locale, currency } = useI18n();
  const [view, setView] = useState<"due" | "recent" | "recovered">("due");
  const res = useApi<Res>(`store/carts?view=${view}`);
  const [notice, setNotice] = useState<string | null>(null);
  const act = useAction(async (id: string, what: "remind" | "dismiss" | "contacted") => { const r = await api<{ result?: string }>(`store/carts/${id}/${what}`, { body: {} }); if (what === "remind") setNotice(t("cart.reminded")); await res.reload(); return r; });
  const [form, setForm] = useState<{ enabled: boolean; paid: boolean; hours: string; coupon: string } | null>(null);
  const d = res.data;
  const cur = form ?? (d ? { enabled: d.settings.recoveryEnabled, paid: d.settings.allowPaid, hours: String(d.settings.remindAfterHours), coupon: d.settings.couponCode ?? "" } : null);
  const save = useAction(async () => { if (!cur) return; await api("store/carts/settings", { method: "PUT", body: { recoveryEnabled: cur.enabled, allowPaid: cur.paid, remindAfterHours: Number(cur.hours), couponCode: cur.coupon } }); setForm(null); await res.reload(); });
  const template = useAction(async () => { const r = await api<{ status: string }>("store/carts/template", { body: {} }); setNotice(t("cart.templateStatus") + ": " + r.status); });
  if (res.loading && !d) return <Loading />;
  return (
    <div className="space-y-5">
      <ErrorText error={res.error} />
      {d && (
        <div className="grid gap-3 sm:grid-cols-4">
          <Stat label={t("cart.stat.due")} value={d.stats.dueCount} icon="box" tone="amber" hint={money(d.stats.dueMinor, currency, locale)} />
          <Stat label={t("cart.stat.recent")} value={d.stats.recentCount} icon="dashboard" />
          <Stat label={t("cart.stat.recovered")} value={d.stats.recoveredCount} icon="billing" tone="green" hint={money(d.stats.recoveredMinor, currency, locale)} />
          <Stat label={t("cart.stat.rate")} value={<span dir="ltr">{d.stats.recoveryRatePercent}%</span>} icon="trending" />
        </div>
      )}
      <Tabs tabs={[{ key: "due", label: t("cart.tab.due") }, { key: "recent", label: t("cart.tab.recent") }, { key: "recovered", label: t("cart.tab.recovered") }]} value={view} onChange={setView} />
      {notice && <Alert tone="green">{notice}</Alert>}
      <ErrorText error={act.error ?? template.error} />
      {d && d.carts.length === 0 ? <Empty>{t(view === "due" ? "cart.empty.due" : view === "recent" ? "cart.empty.recent" : "cart.empty.recovered")}</Empty> : (
        <div className="grid gap-3 lg:grid-cols-2">
          {d?.carts.map((c) => (
            <Card key={c.id} className="space-y-2">
              <div className="flex items-start justify-between gap-2">
                <div><div className="font-semibold">{c.name || t("cart.guest")}</div><div className="text-sm text-slate-500" dir="ltr">{c.phone}</div></div>
                <div className="text-end"><div className="font-bold">{money(c.totalMinor, c.currency, locale)}</div><div className="text-xs text-slate-500">{dateTime(c.createdAt, locale)}</div></div>
              </div>
              <ul className="text-sm text-slate-700">{c.items.map((i, k) => <li key={k}>{i.quantity} × {i.name}{i.variant ? ` (${i.variant})` : ""}</li>)}</ul>
              <div className="flex flex-wrap gap-1.5">
                {c.contactedAt && <Badge tone="blue">{t("cart.contacted")}</Badge>}
                {c.reminderStatus && <Badge tone={c.reminderStatus.startsWith("SENT") ? "green" : "amber"}>{t(`cart.rs.${c.reminderStatus}`)}</Badge>}
              </div>
              {canWrite && c.status === "OPEN" && (
                <div className="flex flex-wrap gap-2 pt-1">
                  <a href={c.whatsappUrl} target="_blank" rel="noreferrer" onClick={() => void act.run(c.id, "contacted")} className="inline-flex items-center rounded-xl bg-emerald-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-emerald-700">{t("cart.whatsapp")}</a>
                  <a href={`tel:+${c.phone.replace(/\D/g, "")}`} onClick={() => void act.run(c.id, "contacted")} className="inline-flex items-center rounded-xl border px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">{t("cart.call")}</a>
                  <Button size="sm" variant="secondary" loading={act.loading} onClick={() => { setNotice(null); void act.run(c.id, "remind"); }}>{t("cart.remind")}</Button>
                  <Button size="sm" variant="ghost" onClick={() => void act.run(c.id, "dismiss")}>{t("cart.dismiss")}</Button>
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
      {cur && (
        <Card className="space-y-4">
          <div><h2 className="font-medium">{t("cart.settings")}</h2><p className="mt-1 text-sm text-slate-500">{t("cart.settingsHint")}</p></div>
          <div className="grid gap-4 sm:grid-cols-4">
            <Field label={t("cart.autoRemind")}><select disabled={!canWrite} className="s-input w-full rounded-xl border px-3 py-2" value={cur.enabled ? "1" : "0"} onChange={(e) => setForm({ ...cur, enabled: e.target.value === "1", paid: e.target.value === "1" ? cur.paid : false })}><option value="0">{t("wr.off")}</option><option value="1">{t("wr.on")}</option></select></Field>
            <Field label={t("cart.afterHours")}><Input dir="ltr" inputMode="numeric" disabled={!canWrite} value={cur.hours} onChange={(e) => setForm({ ...cur, hours: e.target.value })} /></Field>
            <Field label={t("cart.coupon")} hint={t("cart.couponHint")}><Input dir="ltr" disabled={!canWrite} value={cur.coupon} onChange={(e) => setForm({ ...cur, coupon: e.target.value })} /></Field>
            <Field label={t("cart.paid")}><select disabled={!canWrite || !cur.enabled} className="s-input w-full rounded-xl border px-3 py-2" value={cur.paid ? "1" : "0"} onChange={(e) => setForm({ ...cur, paid: e.target.value === "1" })}><option value="0">{t("wr.off")}</option><option value="1">{t("wr.on")}</option></select></Field>
          </div>
          <Alert tone="amber">{t("cart.paidWarn", { price: money(d?.settings.messagePriceMinor ?? 0, currency, locale) })}</Alert>
          <ErrorText error={save.error} />
          {canWrite && <div className="flex flex-wrap gap-2"><Button loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button><Button variant="secondary" loading={template.loading} onClick={() => void template.run()}>{t("cart.registerTemplate")}</Button></div>}
        </Card>
      )}
    </div>
  );
}
