"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, Loading, PageHeader, Select } from "@/components/ui";

type Doctor = { id: string; displayName: string };
type Branch = { id: string; name: string };
type Schedule = { id: string; doctorId: string; branchId: string; weekday: number; startLocalTime: string; endLocalTime: string; slotDurationMinutes: number; bufferMinutes: number };

// ISO weekdays; the week starts on Saturday in Egypt
const DAYS = [6, 7, 1, 2, 3, 4, 5];

/** Weekly working hours per doctor and branch, plus one-off days off / custom hours. Patients only ever see free slots inside these hours. */
export default function SchedulePage() {
  const t = useT();
  const doctors = useApi<Doctor[]>("clinic/doctors");
  const branches = useApi<Branch[]>("clinic/branches");
  const schedules = useApi<Schedule[]>("clinic/schedules");
  const [f, setF] = useState({ doctorId: "", branchId: "", weekday: 7, startTime: "10:00", endTime: "16:00", slotDurationMinutes: 30, bufferMinutes: 0 });
  const [ex, setEx] = useState({ doctorId: "", branchId: "", date: "", type: "DAY_OFF", startTime: "", endTime: "", reason: "" });
  const [saved, setSaved] = useState(false);
  const doctorId = f.doctorId || doctors.data?.[0]?.id || "";
  const branchId = f.branchId || branches.data?.[0]?.id || "";

  const add = useAction(async () => {
    await api("clinic/schedules", { body: { ...f, doctorId, branchId } });
    await schedules.reload();
  });
  const del = useAction(async (id: string) => { await api(`clinic/schedules/${id}`, { method: "DELETE" }); await schedules.reload(); });
  const addEx = useAction(async () => {
    await api("clinic/schedule-exceptions", { body: { doctorId: ex.doctorId || doctorId, branchId: ex.branchId || undefined, date: ex.date, type: ex.type, startTime: ex.startTime || undefined, endTime: ex.endTime || undefined, reason: ex.reason || undefined } });
    setSaved(true);
  });

  if (doctors.loading || schedules.loading) return <Loading />;
  const dayName = (d: number) => t(`weekday.${d}`);
  return (
    <>
      <PageHeader title={t("nav.schedule")} subtitle={t("schedule.hint")} />
      <ErrorText error={schedules.error ?? del.error} />
      {doctors.data?.map((d) => (
        <Card key={d.id} className="mb-4">
          <h2 className="mb-3 font-medium">{d.displayName}</h2>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            {DAYS.map((day) => {
              const rows = schedules.data?.filter((s) => s.doctorId === d.id && s.weekday === day) ?? [];
              return (
                <div key={day} className="rounded-lg border p-3 text-sm">
                  <p className="mb-1 font-medium">{dayName(day)}</p>
                  {rows.length === 0 && <p className="text-slate-400">{t("schedule.off")}</p>}
                  {rows.map((s) => (
                    <p key={s.id} className="flex items-center justify-between" dir="ltr">
                      <span>{s.startLocalTime}–{s.endLocalTime} <span className="text-xs text-slate-500">({s.slotDurationMinutes}m)</span></span>
                      <button className="text-red-600" onClick={() => del.run(s.id)} aria-label={t("common.delete")}>✕</button>
                    </p>
                  ))}
                </div>
              );
            })}
          </div>
        </Card>
      ))}
      <Card className="mb-6 space-y-3">
        <h2 className="font-medium">{t("schedule.addHours")}</h2>
        <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="grid gap-3 sm:grid-cols-3 lg:grid-cols-4">
          <Field label={t("book.doctor")}><Select value={doctorId} onChange={(e) => setF({ ...f, doctorId: e.target.value })}>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select></Field>
          <Field label={t("book.branch")}><Select value={branchId} onChange={(e) => setF({ ...f, branchId: e.target.value })}>{branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></Field>
          <Field label={t("schedule.day")}><Select value={f.weekday} onChange={(e) => setF({ ...f, weekday: Number(e.target.value) })}>{DAYS.map((d) => <option key={d} value={d}>{dayName(d)}</option>)}</Select></Field>
          <Field label={t("schedule.slot")}><Input type="number" min={5} value={f.slotDurationMinutes} onChange={(e) => setF({ ...f, slotDurationMinutes: Number(e.target.value) })} dir="ltr" /></Field>
          <Field label={t("schedule.from")}><Input type="time" value={f.startTime} onChange={(e) => setF({ ...f, startTime: e.target.value })} dir="ltr" /></Field>
          <Field label={t("schedule.to")}><Input type="time" value={f.endTime} onChange={(e) => setF({ ...f, endTime: e.target.value })} dir="ltr" /></Field>
          <Field label={t("schedule.buffer")}><Input type="number" min={0} value={f.bufferMinutes} onChange={(e) => setF({ ...f, bufferMinutes: Number(e.target.value) })} dir="ltr" /></Field>
          <div className="flex items-end"><Button type="submit" loading={add.loading} className="w-full">{t("common.add")}</Button></div>
        </form>
        <ErrorText error={add.error} />
      </Card>
      <Card className="space-y-3">
        <h2 className="font-medium">{t("schedule.exceptions")}</h2>
        <form onSubmit={(e) => { e.preventDefault(); setSaved(false); void addEx.run(); }} className="grid gap-3 sm:grid-cols-3 lg:grid-cols-4">
          <Field label={t("book.doctor")}><Select value={ex.doctorId || doctorId} onChange={(e) => setEx({ ...ex, doctorId: e.target.value })}>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select></Field>
          <Field label={t("book.day")}><Input type="date" value={ex.date} onChange={(e) => setEx({ ...ex, date: e.target.value })} dir="ltr" required /></Field>
          <Field label={t("settings.type")}><Select value={ex.type} onChange={(e) => setEx({ ...ex, type: e.target.value })}>{["DAY_OFF", "HOLIDAY", "CUSTOM_HOURS", "FULL_BOOKED"].map((x) => <option key={x} value={x}>{t(`exception.${x}`)}</option>)}</Select></Field>
          <Field label={t("admin.reason")}><Input value={ex.reason} onChange={(e) => setEx({ ...ex, reason: e.target.value })} /></Field>
          {ex.type === "CUSTOM_HOURS" && (<>
            <Field label={t("schedule.from")}><Input type="time" value={ex.startTime} onChange={(e) => setEx({ ...ex, startTime: e.target.value })} dir="ltr" required /></Field>
            <Field label={t("schedule.to")}><Input type="time" value={ex.endTime} onChange={(e) => setEx({ ...ex, endTime: e.target.value })} dir="ltr" required /></Field>
          </>)}
          <div className="flex items-end"><Button type="submit" loading={addEx.loading} className="w-full">{t("common.add")}</Button></div>
        </form>
        <ErrorText error={addEx.error} />
        {saved && <Alert tone="green">{t("common.saved")}</Alert>}
      </Card>
    </>
  );
}
