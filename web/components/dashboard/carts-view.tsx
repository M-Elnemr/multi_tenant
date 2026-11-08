"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { api } from "@/lib/client";
import { dateTime, money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { ConfirmDialog, Sheet } from "../overlay";
import { useToast } from "../toast";
import { Alert, Avatar, Badge, Button, Empty, ErrorText, Field, Input, KpiStrip, Toolbar } from "../ui";
import { SendModePicker, useOwnNumber, type SendMode } from "./send-mode";
import { isNoWaba, WabaDialog } from "./waba-dialog";
import { CardSkeletons, PanelHead, PillTabs, SwitchRow, errText } from "./marketing/kit";

type Item = { name: string; variant?: string | null; quantity: number; priceMinor: number };
type Cart = { id: string; name?: string | null; phone: string; items: Item[]; itemCount: number; totalMinor: number; currency: string; status: string; createdAt: string; contactedAt?: string | null; reminderStatus?: string | null; whatsappUrl: string };
type Stats = { dueCount: number; dueMinor: number; recentCount: number; recoveredCount: number; recoveredMinor: number; recoveryRatePercent: number };
type Settings = { recoveryEnabled: boolean; allowPaid: boolean; remindAfterHours: number; couponCode: string | null; messagePriceMinor: number; sendMode?: SendMode };
type Res = { carts: Cart[]; stats: Stats; settings: Settings };

/** Carts that were not completed: shown 24 hours after they were started, with ways to reach the customer and a switch for reminders. Paid WhatsApp messages stay off unless the owner turns them on here. */
export default function CartsView({ canWrite }: { canWrite: boolean }) {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const reduce = useReducedMotion();
  const [view, setView] = useState<"due" | "recent" | "recovered">("due");
  const [range, setRange] = useState<DateRange>({});
  const rqs = rangeQuery(new URLSearchParams(), range).toString();
  const res = useApi<Res>(`store/carts?view=${view}${rqs ? `&${rqs}` : ""}`);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [dismissing, setDismissing] = useState<Cart | null>(null);
  const act = useAction(async (id: string, what: "remind" | "dismiss" | "contacted") => {
    try {
      const r = await api<{ result?: string }>(`store/carts/${id}/${what}`, { body: {} });
      if (what === "remind") toast(t("cart.reminded"));
      if (what === "dismiss") toast(t("cartx.dismissed"));
      await res.reload();
      return r;
    } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  const [form, setForm] = useState<{ enabled: boolean; paid: boolean; hours: string; coupon: string; mode: SendMode } | null>(null);
  const own = useOwnNumber();
  const d = res.data;
  const cur = form ?? (d ? { enabled: d.settings.recoveryEnabled, paid: d.settings.allowPaid, hours: String(d.settings.remindAfterHours), coupon: d.settings.couponCode ?? "", mode: d.settings.sendMode ?? "PAID" } : null);
  const save = useAction(async () => {
    if (!cur) return;
    await api("store/carts/settings", { method: "PUT", body: { recoveryEnabled: cur.enabled, allowPaid: cur.paid, remindAfterHours: Number(cur.hours), couponCode: cur.coupon, sendMode: own.linked ? cur.mode : "PAID" } });
    setForm(null); toast(t("common.saved")); await res.reload();
  });
  const [wabaOpen, setWabaOpen] = useState(false);
  const template = useAction(async () => { const r = await api<{ status: string }>("store/carts/template", { body: {} }); toast(`${t("cart.templateStatus")}: ${r.status}`, { tone: "info" }); });
  return (
    <div className="space-y-5">
      <ErrorText error={res.error} />
      {!d ? (
        <div className="space-y-5"><div className="skeleton h-28 rounded-[14px]" /><CardSkeletons n={4} className="grid gap-3 lg:grid-cols-2" h="h-52" /></div>
      ) : (
        <>
          <KpiStrip items={[
            { label: t("cart.stat.due"), value: d.stats.dueCount, icon: "cart", tone: "amber", hint: money(d.stats.dueMinor, currency, locale) },
            { label: t("cart.stat.recent"), value: d.stats.recentCount, icon: "clock", tone: "slate" },
            { label: t("cart.stat.recovered"), value: d.stats.recoveredCount, icon: "wallet", tone: "green", hint: money(d.stats.recoveredMinor, currency, locale) },
            { label: t("cart.stat.rate"), value: <span dir="ltr">{d.stats.recoveryRatePercent}%</span>, icon: "trending" },
          ]} />

          <div className={`flex items-center justify-between gap-3 rounded-[18px] p-4 ring-1 ${d.settings.recoveryEnabled ? "bg-emerald-50/60 ring-emerald-600/15" : "bg-surface ring-line"}`}>
            <div className="flex min-w-0 items-center gap-3">
              <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-[11px] ${d.settings.recoveryEnabled ? "bg-emerald-100 text-emerald-700" : "bg-canvas text-muted"}`}><Icon name="bell" className="h-[18px] w-[18px]" /></span>
              <p className="min-w-0 text-[13.5px] font-semibold text-ink">{d.settings.recoveryEnabled ? <>{t("cartx.autoOn", { h: d.settings.remindAfterHours })}</> : t("cartx.autoOff")}</p>
            </div>
            <Button variant="secondary" size="sm" onClick={() => setSettingsOpen(true)}><Icon name="settings" className="h-4 w-4" />{t("cart.settings")}</Button>
          </div>

          <Toolbar>
            <div className="min-w-0 flex-1"><PillTabs id="carts" value={view} onChange={setView} tabs={[{ key: "due", label: t("cart.tab.due") }, { key: "recent", label: t("cart.tab.recent") }, { key: "recovered", label: t("cart.tab.recovered") }]} /></div>
            <DateRangePicker value={range} onChange={setRange} />
          </Toolbar>

          {d.carts.length === 0 ? (
            <Empty icon="cart" title={t(view === "due" ? "cart.empty.due" : view === "recent" ? "cart.empty.recent" : "cart.empty.recovered")}>{t("cartx.emptyBody")}</Empty>
          ) : (
            <ul className="grid gap-3 lg:grid-cols-2">
              <AnimatePresence initial={false}>
                {d.carts.map((c) => (
                  <motion.li key={c.id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, scale: 0.98 }} transition={{ duration: 0.2 }} className="flex flex-col rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
                    <div className="flex items-start gap-3">
                      <Avatar name={c.name || "?"} className="h-10 w-10 text-sm" />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-[14px] font-semibold text-ink">{c.name || t("cart.guest")}</p>
                        <p className="text-[12.5px] tnum text-muted" dir="ltr">{c.phone}</p>
                      </div>
                      <div className="text-end">
                        <p className="font-display text-[16px] font-semibold tnum text-ink">{money(c.totalMinor, c.currency, locale)}</p>
                        <p className="text-[11.5px] text-faint">{dateTime(c.createdAt, locale)}</p>
                      </div>
                    </div>
                    <ul className="mt-3 flex-1 space-y-1 rounded-[12px] bg-canvas p-3 text-[13px] text-ink-2 ring-1 ring-line">
                      {c.items.map((i, k) => <li key={k} className="flex gap-2"><span className="tnum font-semibold text-muted">{i.quantity}×</span><span className="min-w-0 flex-1 truncate">{i.name}{i.variant ? ` (${i.variant})` : ""}</span></li>)}
                    </ul>
                    {(c.contactedAt || c.reminderStatus) && (
                      <div className="mt-3 flex flex-wrap gap-1.5">
                        {c.contactedAt && <Badge tone="blue">{t("cart.contacted")}</Badge>}
                        {c.reminderStatus && <Badge tone={c.reminderStatus.startsWith("SENT") ? "green" : "amber"}>{t(`cart.rs.${c.reminderStatus}`)}</Badge>}
                      </div>
                    )}
                    {canWrite && c.status === "OPEN" && (
                      <div className="mt-3 flex flex-wrap items-center gap-2">
                        <a href={c.whatsappUrl} target="_blank" rel="noreferrer" onClick={() => void act.run(c.id, "contacted")} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-emerald-600 px-3 text-[13px] font-semibold text-white transition hover:bg-emerald-700"><Icon name="chat" className="h-4 w-4" />{t("cart.whatsapp")}</a>
                        <a href={`tel:+${c.phone.replace(/\D/g, "")}`} onClick={() => void act.run(c.id, "contacted")} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-3 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:bg-surface-2"><Icon name="phone" className="h-4 w-4" />{t("cart.call")}</a>
                        <Button size="sm" variant="ink" loading={act.loading} onClick={() => void act.run(c.id, "remind")}><Icon name="send" className="h-4 w-4 flip-rtl" />{t("cart.remind")}</Button>
                        <Button size="sm" variant="ghost" className="ms-auto" onClick={() => setDismissing(c)}>{t("cart.dismiss")}</Button>
                      </div>
                    )}
                  </motion.li>
                ))}
              </AnimatePresence>
            </ul>
          )}
        </>
      )}

      <ConfirmDialog open={!!dismissing} onClose={() => setDismissing(null)} loading={act.loading}
        onConfirm={() => { const c = dismissing; setDismissing(null); if (c) void act.run(c.id, "dismiss"); }}
        title={t("cartx.dismissTitle")} body={t("cartx.dismissBody")} confirmLabel={t("cart.dismiss")} cancelLabel={t("common.cancel")} />

      <Sheet open={settingsOpen} onClose={() => setSettingsOpen(false)} title={t("cart.settings")} width="30rem"
        footer={canWrite && cur ? <div className="flex flex-wrap gap-2"><Button variant="ink" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button><Button variant="secondary" loading={template.loading} onClick={() => void template.run()}>{t("cart.registerTemplate")}</Button></div> : undefined}>
        {cur && (
          <div className="space-y-4">
            <PanelHead icon="bell" title={t("cart.settings")} hint={t("cart.settingsHint")} />
            <Field label={t("sm.title")}><SendModePicker value={own.linked ? cur.mode : "PAID"} onChange={(m) => setForm({ ...cur, mode: m })} disabled={!canWrite} /></Field>
            <SwitchRow title={t("cart.autoRemind")} checked={cur.enabled} disabled={!canWrite} onChange={(v) => setForm({ ...cur, enabled: v, paid: v ? cur.paid : false })} />
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("cart.afterHours")}><Input dir="ltr" inputMode="numeric" disabled={!canWrite} value={cur.hours} onChange={(e) => setForm({ ...cur, hours: e.target.value })} /></Field>
              <Field label={t("cart.coupon")} hint={t("cart.couponHint")}><Input dir="ltr" disabled={!canWrite} value={cur.coupon} onChange={(e) => setForm({ ...cur, coupon: e.target.value })} /></Field>
            </div>
            {(!own.linked || cur.mode === "PAID") && <SwitchRow title={t("cart.paid")} checked={cur.paid} disabled={!canWrite || !cur.enabled} onChange={(v) => setForm({ ...cur, paid: v })} />}
            {own.linked && cur.mode === "FREE" ? <Alert tone="green">{t("cart.freeInfo")}</Alert> : <Alert tone="amber">{t("cart.paidWarn", { price: money(d?.settings.messagePriceMinor ?? 0, currency, locale) })}</Alert>}
            <ErrorText error={save.error ?? template.error} />
            {isNoWaba(template.error) && <Button variant="secondary" onClick={() => setWabaOpen(true)}>{t("waba.add")}</Button>}
          </div>
        )}
      </Sheet>
      <WabaDialog open={wabaOpen} onClose={() => setWabaOpen(false)} onSaved={() => { template.setError(null); void template.run(); }} />
    </div>
  );
}
