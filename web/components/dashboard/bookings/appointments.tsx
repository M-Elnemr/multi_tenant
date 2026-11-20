"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateOnly, money } from "@/lib/format";
import { localPhone } from "@/lib/phone";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Menu, Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Alert, Button, Empty, ErrorText, Field, Input, Loading, Pager, Select, Table, Td, Textarea, Toolbar } from "@/components/ui";
import { SearchBox } from "@/components/dashboard/filters";
import { errText } from "@/components/dashboard/marketing/kit";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { BookingStatus, type Booking, type Service } from "./common";

const FILTERS = ["", "PENDING", "CONFIRMED", "COMPLETED", "CANCELLED"] as const;

/** Free start times of one service on one day, as chips; the chosen one is highlighted. */
function TimePicker({ serviceId, date, value, onChange, exclude }: { serviceId: string; date: string; value: string; onChange: (t: string) => void; exclude?: string }) {
  const { t } = useI18n();
  const slots = useApi<string[]>(serviceId && date ? `store/booking/slots?serviceId=${serviceId}&date=${date}` : null);
  if (!serviceId || !date) return <p className="text-[12.5px] text-muted">{t("bk.pickServiceDate")}</p>;
  if (!slots.data) return <div className="skeleton h-10 rounded-[10px]" />;
  const list = slots.data.filter((x) => x !== exclude);
  if (list.length === 0) return <Alert tone="amber">{t("bk.noTimes")}</Alert>;
  return <div className="flex flex-wrap gap-2">{list.map((s) => <button key={s} type="button" aria-pressed={value === s} onClick={() => onChange(s)} className={`chip tnum ${value === s ? "chip-on" : ""}`}>{s}</button>)}</div>;
}

/** A booking the shop makes itself (a phone call, a walk-in): confirmed at once. */
export function NewBooking({ open, onClose, services, onDone }: { open: boolean; onClose: () => void; services: Service[]; onDone: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const active = services.filter((s) => s.isActive);
  const [f, setF] = useState({ serviceId: "", date: "", time: "", name: "", phone: "", note: "" });
  const save = useAction(async () => { await api("store/bookings", { body: f }); toast(t("bk.created")); setF({ serviceId: "", date: "", time: "", name: "", phone: "", note: "" }); onDone(); });
  const ready = f.serviceId && f.date && f.time && f.name.trim() && f.phone.trim().length >= 9;
  return (
    <Sheet open={open} onClose={onClose} title={t("bk.new")} footer={<div className="flex justify-end gap-2"><Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button variant="ink" loading={save.loading} disabled={!ready} onClick={() => void save.run()}>{t("bk.create")}</Button></div>}>
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); if (ready) void save.run(); }}>
        <Field label={t("bk.service")}>
          <Select value={f.serviceId} onChange={(e) => setF({ ...f, serviceId: e.target.value, time: "" })}><option value="">{t("bk.chooseService")}</option>{active.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select>
        </Field>
        <Field label={t("bk.date")}><Input type="date" dir="ltr" min={new Date().toISOString().slice(0, 10)} value={f.date} onChange={(e) => setF({ ...f, date: e.target.value, time: "" })} /></Field>
        <div className="space-y-1.5"><span className="text-[13px] font-semibold text-ink-2">{t("bk.time")}</span><TimePicker serviceId={f.serviceId} date={f.date} value={f.time} onChange={(x) => setF({ ...f, time: x })} /></div>
        <Field label={t("bk.customerName")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
        <Field label={t("bk.phone")}><Input type="tel" dir="ltr" inputMode="tel" value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} /></Field>
        <Field label={`${t("bk.note")} (${t("ch.optional")})`}><Textarea value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} /></Field>
        <ErrorText error={save.error} />
      </form>
    </Sheet>
  );
}

export function Decide({ b, mode, onClose, onDone }: { b: Booking | null; mode: "decline" | "cancel" | "reschedule"; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const [reason, setReason] = useState("");
  const [date, setDate] = useState("");
  const [time, setTime] = useState("");
  const go = useAction(async () => {
    if (!b) return;
    await api(`store/bookings/${b.id}/${mode}`, { body: mode === "reschedule" ? { date, time } : { reason } });
    toast(t("common.saved"));
    setReason(""); setDate(""); setTime("");
    onDone();
  });
  return (
    <Sheet open={!!b} onClose={onClose} title={t(`bk.${mode}`)} footer={<div className="flex justify-end gap-2"><Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button variant={mode === "reschedule" ? "ink" : "danger"} loading={go.loading} disabled={mode === "reschedule" && (!date || !time)} onClick={() => void go.run()}>{t(`bk.${mode}.do`)}</Button></div>}>
      {b && (
        <div className="space-y-4 text-sm">
          <p className="rounded-[12px] bg-canvas p-3 ring-1 ring-line"><b>{b.customerName}</b> · {b.serviceName}<br /><span className="tnum text-muted">{b.date} {b.time}</span></p>
          {mode === "reschedule" ? (
            <>
              <Field label={t("bk.newDate")}><Input type="date" dir="ltr" min={new Date().toISOString().slice(0, 10)} value={date} onChange={(e) => { setDate(e.target.value); setTime(""); }} /></Field>
              <div className="space-y-1.5"><span className="text-[13px] font-semibold text-ink-2">{t("bk.time")}</span><TimePicker serviceId={b.serviceId} date={date} value={time} onChange={setTime} /></div>
              <p className="text-[12.5px] text-muted">{t("bk.rescheduleTells")}</p>
            </>
          ) : (
            <Field label={`${t("bk.reason")} (${t("ch.optional")})`} hint={t("bk.reasonHint")}><Textarea value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          )}
          <ErrorText error={go.error} />
        </div>
      )}
    </Sheet>
  );
}

export function Appointments({ canManage, onChanged }: { canManage: boolean; services?: Service[]; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [status, setStatus] = useState<(typeof FILTERS)[number]>("PENDING");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const [dlg, setDlg] = useState<{ b: Booking; mode: "decline" | "cancel" | "reschedule" } | null>(null);
  const qs = new URLSearchParams({ page: String(page) });
  if (status) qs.set("status", status);
  if (q.trim()) qs.set("q", q.trim());
  if (status === "COMPLETED" || status === "CANCELLED") qs.set("from", "2020-01-01");
  const { data, loading, error, reload } = useApi<Page<Booking>>(`store/bookings?${qs}`);
  const after = () => { void reload(); onChanged(); };
  const act = useAction(async (id: string, what: string) => {
    try { await api(`store/bookings/${id}/${what}`, { body: {} }); toast(t("common.saved")); after(); } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  return (
    <div className="space-y-4">
      <div className="-mx-1 overflow-x-auto px-1 pb-1 [scrollbar-width:none]"><div className="flex w-max gap-2">
        {FILTERS.map((f) => <button key={f} type="button" aria-pressed={status === f} onClick={() => { setStatus(f); setPage(1); }} className={`chip !min-h-11 ${status === f ? "chip-on" : ""}`}>{f ? t(`bk.status.${f}`) : t("bk.all")}</button>)}
      </div></div>
      <Toolbar><SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("bk.search")} /></Toolbar>
      <ErrorText error={error} />
      {loading && !data ? <Loading variant="table" /> : data && data.data.length === 0 ? (
        <Empty icon="calendar" title={t("bk.none.title")}>{t("bk.none.body")}</Empty>
      ) : (
        <>
          <Table head={[t("bk.when"), t("orders.customer"), t("bk.service"), t("bk.price"), t("inbox.f.status"), ""]}>
            {data?.data.map((b) => (
              <tr key={b.id}>
                <Td><span className="block font-semibold tnum text-ink">{b.time}</span><span className="block text-[12px] text-muted">{dateOnly(b.date, locale)}</span></Td>
                <Td><span className="block font-semibold text-ink">{b.customerName}</span><span dir="ltr" className="block text-start text-[12px] tnum text-muted">{localPhone(b.phone)}</span></Td>
                <Td><span className="block text-ink-2">{b.serviceName}</span>{b.note && <span className="block max-w-[14rem] truncate text-[12px] text-muted">{b.note}</span>}<span className="block font-mono text-[11px] text-faint">{b.bookingNumber}</span></Td>
                <Td><span className="font-semibold tnum text-ink">{money(b.priceMinor, b.currency, locale)}</span></Td>
                <Td><BookingStatus status={b.status} /></Td>
                <Td>
                  <span className="flex items-center justify-end gap-1.5">
                    {canManage && b.status === "PENDING" && <Button size="sm" variant="ink" loading={act.loading} onClick={() => void act.run(b.id, "confirm")}><Icon name="check" className="h-4 w-4" />{t("bk.confirm")}</Button>}
                    <WhatsAppButton phone={b.phone} message={t("bk.waHello", { name: b.customerName, service: b.serviceName, date: b.date, time: b.time })} size={32} />
                    {canManage && (b.status === "PENDING" || b.status === "CONFIRMED") && (
                      <Menu label={t("common.more")} items={[
                        ...(b.status === "PENDING" ? [{ label: t("bk.decline"), icon: "close" as const, onSelect: () => setDlg({ b, mode: "decline" }) }] : []),
                        ...(b.status === "CONFIRMED" ? [{ label: t("bk.complete"), icon: "check" as const, onSelect: () => void act.run(b.id, "complete") }, { label: t("bk.noShow"), icon: "alert" as const, onSelect: () => void act.run(b.id, "no-show") }] : []),
                        { label: t("bk.reschedule"), icon: "calendar" as const, onSelect: () => setDlg({ b, mode: "reschedule" }) },
                        { label: t("bk.cancel"), icon: "close" as const, onSelect: () => setDlg({ b, mode: "cancel" }) },
                      ]} trigger={({ toggle }) => <button type="button" onClick={toggle} aria-label={t("common.more")} className="grid h-8 w-8 place-items-center rounded-[9px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="menu" className="h-4 w-4" /></button>} />
                    )}
                  </span>
                </Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      <Decide b={dlg?.b ?? null} mode={dlg?.mode ?? "cancel"} onClose={() => setDlg(null)} onDone={() => { setDlg(null); after(); }} />
    </div>
  );
}
