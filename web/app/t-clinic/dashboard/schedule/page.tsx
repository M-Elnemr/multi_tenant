"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Avatar, Button, Empty, ErrorText, Field, Input, Loading, PageHeader, Select } from "@/components/ui";
import { Paper, PaperHead, Pill } from "@/components/clinic/dash";

type Doctor = { id: string; displayName: string };
type Branch = { id: string; name: string };
type Schedule = { id: string; doctorId: string; branchId: string; weekday: number; startLocalTime: string; endLocalTime: string; slotDurationMinutes: number; bufferMinutes: number };

// ISO weekdays; the week starts on Saturday in Egypt
const DAYS = [6, 7, 1, 2, 3, 4, 5];

/** Weekly working hours per doctor and branch, plus one-off days off / custom hours. The booking screen only offers free slots inside these hours. */
export default function SchedulePage() {
  const t = useT();
  const toast = useToast();
  const write = true;   // the server enforces access (as before); the screen itself never hid these controls
  const doctors = useApi<Doctor[]>("clinic/doctors");
  const branches = useApi<Branch[]>("clinic/branches");
  const schedules = useApi<Schedule[]>("clinic/schedules");
  const [f, setF] = useState({ doctorId: "", branchId: "", weekday: 7, startTime: "10:00", endTime: "16:00", slotDurationMinutes: 30, bufferMinutes: 0 });
  const [ex, setEx] = useState({ doctorId: "", branchId: "", date: "", type: "DAY_OFF", startTime: "", endTime: "", reason: "" });
  const [hoursOpen, setHoursOpen] = useState(false);
  const [exOpen, setExOpen] = useState(false);
  const [ask, setAsk] = useState<{ id: string; label: string } | null>(null);
  const doctorId = f.doctorId || doctors.data?.[0]?.id || "";
  const branchId = f.branchId || branches.data?.[0]?.id || "";

  const add = useAction(async () => {
    await api("clinic/schedules", { body: { ...f, doctorId, branchId } });
    await schedules.reload();
    setHoursOpen(false);
    toast(t("common.saved"));
  });
  const del = useAction(async (id: string) => { await api(`clinic/schedules/${id}`, { method: "DELETE" }); setAsk(null); await schedules.reload(); toast(t("cld.hoursRemoved"), { tone: "info" }); });
  const addEx = useAction(async () => {
    await api("clinic/schedule-exceptions", { body: { doctorId: ex.doctorId || doctorId, branchId: ex.branchId || undefined, date: ex.date, type: ex.type, startTime: ex.startTime || undefined, endTime: ex.endTime || undefined, reason: ex.reason || undefined } });
    setExOpen(false);
    toast(t("common.saved"));
  });

  if (doctors.loading || schedules.loading) return <Loading variant="detail" />;
  const dayName = (d: number) => t(`weekday.${d}`);
  const openAdd = (docId?: string, day?: number) => { setF({ ...f, doctorId: docId ?? f.doctorId, weekday: day ?? f.weekday }); setHoursOpen(true); };
  return (
    <>
      <PageHeader eyebrow={t("navgrp.setup")} title={t("nav.schedule")} subtitle={t("schedule.hint")}
        actions={write ? <>
          <Button variant="secondary" onClick={() => setExOpen(true)}><Icon name="calendar" className="h-4 w-4" />{t("schedule.exceptions")}</Button>
          <Button onClick={() => openAdd()}><Icon name="plus" className="h-4 w-4" />{t("schedule.addHours")}</Button>
        </> : undefined} />
      <div className="space-y-5">
        <ErrorText error={schedules.error ?? del.error} />
        {doctors.data?.length === 0 && <Empty icon="clock" title={t("schedule.addHours")} />}
        {doctors.data?.map((d) => {
          const mine = schedules.data?.filter((s) => s.doctorId === d.id) ?? [];
          return (
            <Paper key={d.id}>
              <PaperHead title={d.displayName} subtitle={`${mine.length} ${t("schedule.addHours")}`} icon="doctor" action={write && <Button size="sm" variant="secondary" onClick={() => openAdd(d.id)}><Icon name="plus" className="h-3.5 w-3.5" />{t("common.add")}</Button>} />
              <div className="grid gap-2.5 px-4 pb-5 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-7">
                {DAYS.map((day) => {
                  const rows = mine.filter((s) => s.weekday === day).sort((a, b) => a.startLocalTime.localeCompare(b.startLocalTime));
                  return (
                    <div key={day} className={`flex min-h-24 flex-col rounded-[14px] p-3 text-sm ring-1 ring-inset ${rows.length ? "bg-[color-mix(in_srgb,var(--rail-accent)_7%,var(--surface))] ring-[color-mix(in_srgb,var(--rail-accent)_22%,transparent)]" : "bg-surface-2 ring-line"}`}>
                      <div className="mb-2 flex items-center justify-between gap-2">
                        <p className="font-display text-[14px] font-semibold text-ink">{dayName(day)}</p>
                        {write && <button type="button" onClick={() => openAdd(d.id, day)} aria-label={t("common.add")} className="grid h-6 w-6 place-items-center rounded-[7px] text-muted hover:bg-surface hover:text-ink"><Icon name="plus" className="h-3.5 w-3.5" /></button>}
                      </div>
                      {rows.length === 0 && <p className="text-xs text-faint">{t("schedule.off")}</p>}
                      <div className="space-y-1.5">
                        {rows.map((s) => (
                          <div key={s.id} className="flex items-center justify-between gap-1 rounded-[9px] bg-surface px-2 py-1.5 shadow-[0_0_0_1px_var(--line)]" dir="ltr">
                            <span className="text-[12.5px] font-semibold text-ink-2 tnum">{s.startLocalTime}–{s.endLocalTime}<span className="ms-1 text-[11px] font-normal text-muted">{s.slotDurationMinutes}m</span></span>
                            {write && <button type="button" className="grid h-6 w-6 shrink-0 place-items-center rounded-[7px] text-faint hover:bg-rose-50 hover:text-rose-700" onClick={() => setAsk({ id: s.id, label: `${dayName(day)} ${s.startLocalTime}–${s.endLocalTime}` })} aria-label={t("common.delete")}><Icon name="close" className="h-3.5 w-3.5" /></button>}
                          </div>
                        ))}
                      </div>
                    </div>
                  );
                })}
              </div>
            </Paper>
          );
        })}
      </div>

      <ConfirmDialog open={!!ask} onClose={() => setAsk(null)} danger loading={del.loading} title={t("common.delete")} body={ask?.label}
        confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} onConfirm={() => { if (ask) void del.run(ask.id); }} />

      <Sheet open={hoursOpen} onClose={() => setHoursOpen(false)} title={t("schedule.addHours")}
        footer={<><ErrorText error={add.error} /><Button className="mt-2 w-full" loading={add.loading} onClick={() => add.run()}>{t("common.add")}</Button></>}>
        <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="space-y-4">
          <Field label={t("book.doctor")}><Select value={doctorId} onChange={(e) => setF({ ...f, doctorId: e.target.value })}>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select></Field>
          <Field label={t("book.branch")}><Select value={branchId} onChange={(e) => setF({ ...f, branchId: e.target.value })}>{branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></Field>
          <Field label={t("schedule.day")}><Select value={f.weekday} onChange={(e) => setF({ ...f, weekday: Number(e.target.value) })}>{DAYS.map((d) => <option key={d} value={d}>{dayName(d)}</option>)}</Select></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("schedule.from")}><Input type="time" value={f.startTime} onChange={(e) => setF({ ...f, startTime: e.target.value })} dir="ltr" /></Field>
            <Field label={t("schedule.to")}><Input type="time" value={f.endTime} onChange={(e) => setF({ ...f, endTime: e.target.value })} dir="ltr" /></Field>
            <Field label={t("schedule.slot")}><Input type="number" min={5} value={f.slotDurationMinutes} onChange={(e) => setF({ ...f, slotDurationMinutes: Number(e.target.value) })} dir="ltr" /></Field>
            <Field label={t("schedule.buffer")}><Input type="number" min={0} value={f.bufferMinutes} onChange={(e) => setF({ ...f, bufferMinutes: Number(e.target.value) })} dir="ltr" /></Field>
          </div>
        </form>
      </Sheet>

      <Sheet open={exOpen} onClose={() => setExOpen(false)} title={t("schedule.exceptions")}
        footer={<><ErrorText error={addEx.error} /><Button className="mt-2 w-full" loading={addEx.loading} disabled={!ex.date} onClick={() => addEx.run()}>{t("common.add")}</Button></>}>
        <form onSubmit={(e) => { e.preventDefault(); void addEx.run(); }} className="space-y-4">
          <div className="flex items-center gap-3 rounded-[12px] bg-surface-2 p-3 ring-1 ring-inset ring-line"><Avatar name={doctors.data?.find((d) => d.id === (ex.doctorId || doctorId))?.displayName ?? ""} className="h-9 w-9 text-xs" /><Pill tone="sage">{t(`exception.${ex.type}`)}</Pill></div>
          <Field label={t("book.doctor")}><Select value={ex.doctorId || doctorId} onChange={(e) => setEx({ ...ex, doctorId: e.target.value })}>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select></Field>
          <Field label={t("book.day")}><Input type="date" value={ex.date} onChange={(e) => setEx({ ...ex, date: e.target.value })} dir="ltr" required /></Field>
          <Field label={t("settings.type")}><Select value={ex.type} onChange={(e) => setEx({ ...ex, type: e.target.value })}>{["DAY_OFF", "HOLIDAY", "CUSTOM_HOURS", "FULL_BOOKED"].map((x) => <option key={x} value={x}>{t(`exception.${x}`)}</option>)}</Select></Field>
          {ex.type === "CUSTOM_HOURS" && (
            <div className="grid grid-cols-2 gap-3">
              <Field label={t("schedule.from")}><Input type="time" value={ex.startTime} onChange={(e) => setEx({ ...ex, startTime: e.target.value })} dir="ltr" required /></Field>
              <Field label={t("schedule.to")}><Input type="time" value={ex.endTime} onChange={(e) => setEx({ ...ex, endTime: e.target.value })} dir="ltr" required /></Field>
            </div>
          )}
          <Field label={t("admin.reason")}><Input value={ex.reason} onChange={(e) => setEx({ ...ex, reason: e.target.value })} /></Field>
        </form>
      </Sheet>
    </>
  );
}
