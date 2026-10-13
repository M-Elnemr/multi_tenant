"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, Reorder, motion, useDragControls } from "motion/react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Button, Card, Empty, ErrorText, Loading, PageHeader, Select } from "@/components/ui";
import { Icon } from "@/components/icons";
import { timeOnly } from "@/lib/format";
import { VisitTypeBadge, VisitTypeEditable } from "@/components/visit-type";

type Entry = {
  id: string; status: string; queueNumber?: number; calledAt?: string; startAt: string; waitedMinutes?: number;
  doctorId: string; doctorName: string; serviceName: string; visitType?: string; patientId: string; patientName: string; patientCode: string; isWalkIn?: boolean;
};
type Reserved = { id: string; patientId: string; status: string; startAt: string; doctorName: string; serviceName: string; visitType?: string; patientName: string; patientCode: string; patientPhone?: string; patientSex?: string; patientAgeYears?: number };
type Queue = { inProgress: Entry[]; waiting: Entry[]; reserved?: Reserved[] };
type Doctor = { id: string; displayName: string };


/** Clicking empty space on a card opens the patient; buttons, links and form controls inside keep their own action. */
const openOnClick = (go: () => void) => (ev: React.MouseEvent) => { if (!(ev.target as HTMLElement).closest("button,a,select,input,[data-nogo]")) go(); };

/** One waiting patient: drag handle, number, details and the actions. Dragging only starts from the handle so buttons stay clickable. */
function WaitingRow({ e, i, last, canReorder, clinical, canManage, onCall, onSendIn, onNoShow, onRemove, onMove, onChanged, onOpen }: {
  e: Entry; i: number; last: boolean; canReorder: boolean; clinical: boolean; canManage: boolean;
  onCall: () => void; onSendIn: () => void; onNoShow: () => void; onRemove: () => void; onMove: (d: -1 | 1) => void; onChanged: () => void; onOpen: () => void;
}) {
  const { t, locale, timezone } = useI18n();
  const controls = useDragControls();
  return (
    <Reorder.Item value={e} dragListener={false} dragControls={controls} layout="position" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, x: locale === "ar" ? 80 : -80, height: 0, marginBottom: 0 }} whileDrag={{ scale: 1.02, boxShadow: "0 18px 40px -12px rgb(15 35 60 / 0.35)", zIndex: 20 }} className="relative list-none">
      <div onClick={openOnClick(onOpen)} className="flex cursor-pointer flex-wrap items-center justify-between gap-3 rounded-2xl border bg-white p-4 text-sm transition hover:border-brand/40 hover:shadow-sm">
        <div className="flex items-center gap-3">
          {canReorder && (
            <div className="flex flex-col items-center">
              <button type="button" aria-label={t("queue.moveUp")} disabled={i === 0} onClick={() => onMove(-1)} className="rounded p-0.5 text-slate-400 hover:text-brand disabled:opacity-20"><Icon name="arrow" className="h-3.5 w-3.5 -rotate-90" /></button>
              <span data-nogo onPointerDown={(ev) => controls.start(ev)} className="cursor-grab touch-none select-none px-1 text-lg leading-none text-slate-400 active:cursor-grabbing" title={t("queue.reorderHint")} aria-hidden>⠿</span>
              <button type="button" aria-label={t("queue.moveDown")} disabled={last} onClick={() => onMove(1)} className="rounded p-0.5 text-slate-400 hover:text-brand disabled:opacity-20"><Icon name="arrow" className="h-3.5 w-3.5 rotate-90" /></button>
            </div>
          )}
          <span className="flex h-10 w-10 items-center justify-center rounded-full bg-slate-900 text-lg font-bold text-white">{e.queueNumber}</span>
          <div>
            <p className="flex flex-wrap items-center gap-2 font-medium">{e.patientName} <span className="font-mono text-xs text-slate-500">{e.patientCode}</span> <VisitTypeEditable appointmentId={e.id} type={e.visitType} onChanged={onChanged} /></p>
            <p className="text-slate-500">{e.serviceName} · {e.doctorName} · {t("queue.booked", { time: timeOnly(e.startAt, locale, timezone) })}</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={(e.waitedMinutes ?? 0) > 30 ? "red" : (e.waitedMinutes ?? 0) > 15 ? "amber" : "slate"}>{t("queue.waited", { n: e.waitedMinutes ?? 0 })}</Badge>
          {e.calledAt && <Badge tone="blue">{t("queue.called")}</Badge>}
          {i > 0 && <Button size="sm" variant="ghost" onClick={onCall}>{t("queue.call")}</Button>}
          {i > 0 && !clinical && canManage && <Button size="sm" variant="secondary" onClick={onSendIn}>{t("queue.sendIn")}</Button>}
          {canManage && <Button size="sm" variant="ghost" onClick={onRemove}>{t("queue.remove")}</Button>}
          <Button size="sm" variant="ghost" onClick={onNoShow}>{t("appt.noShow")}</Button>
        </div>
      </div>
    </Reorder.Item>
  );
}

/** The waiting room: who is with the doctor now and who is next, in arrival order. Refreshes by itself every 10 seconds. */
export default function QueuePage() {
  const { t, locale, timezone } = useI18n();
  const router = useRouter();
  const { can } = useMe();
  const [doctorId, setDoctorId] = useState("");
  const doctors = useApi<Doctor[]>("clinic/doctors");
  const queue = useApi<Queue>(`clinic/queue?doctorId=${doctorId}`);
  const reload = queue.reload;

  // The list the doctor is arranging: shown instead of the server's while a change is waiting to be saved, so refreshes never make rows jump.
  const [local, setLocal] = useState<Entry[] | null>(null);
  const [removed, setRemoved] = useState<string[]>([]);
  const busy = useRef(false);
  const commitTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const removals = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const [toast, setToast] = useState<{ id: string; name: string } | null>(null);
  const [adding, setAdding] = useState<string | null>(null);

  useEffect(() => {
    const h = setInterval(() => { if (!busy.current) void reload(); }, 10_000);
    return () => clearInterval(h);
  }, [reload]);
  // a fresh answer from the server replaces the local arrangement once nothing is pending
  useEffect(() => { if (!busy.current) setLocal(null); }, [queue.data]);

  const waitingNow = ((local ?? queue.data?.waiting) ?? []).filter((x) => !removed.includes(x.id));

  const scheduleCommit = useCallback((list: Entry[]) => {
    busy.current = true;
    if (commitTimer.current) clearTimeout(commitTimer.current);
    commitTimer.current = setTimeout(async () => {
      try { await api("clinic/queue/reorder", { body: { ids: list.map((x) => x.id) } }); } catch { /* the refresh below shows the real order */ }
      busy.current = removals.current.size > 0;
      await reload();
    }, 700);
  }, [reload]);
  const arrange = (list: Entry[]) => { setLocal(list); scheduleCommit(list); };
  const move = (i: number, d: -1 | 1) => { const l = [...waitingNow]; const j = i + d; if (j < 0 || j >= l.length) return; [l[i], l[j]] = [l[j], l[i]]; arrange(l); };

  // Remove from the queue: the row slides away at once and can be brought back for a few seconds before the server is told.
  const removeEntry = (e: Entry) => {
    busy.current = true;
    setRemoved((r) => [...r, e.id]);
    setToast({ id: e.id, name: e.patientName });
    setTimeout(() => setToast((cur) => (cur?.id === e.id ? null : cur)), 3000);   // the message hides itself after 3 s
    removals.current.set(e.id, setTimeout(async () => {
      removals.current.delete(e.id);
      try { await api(`clinic/appointments/${e.id}/remove-from-queue`, { body: {} }); } catch { /* shown by the refresh */ }
      busy.current = removals.current.size > 0;
      setRemoved((r) => r.filter((x) => x !== e.id));
      setToast((cur) => (cur?.id === e.id ? null : cur));
      await reload();
    }, 3500));
  };
  const undoRemove = () => {
    if (!toast) return;
    const h = removals.current.get(toast.id);
    if (h) clearTimeout(h);
    removals.current.delete(toast.id);
    setRemoved((r) => r.filter((x) => x !== toast.id));
    setToast(null);
    busy.current = removals.current.size > 0;
  };
  const addReserved = useAction(async (r: Reserved) => {
    setAdding(r.id);
    try {
      if (r.status === "PENDING_CONFIRMATION") await api(`clinic/appointments/${r.id}/confirm`, { body: {} });
      await api(`clinic/appointments/${r.id}/check-in`, { body: {} });
    } finally { setAdding(null); }
    await reload();
  });

  const act = useAction(async (id: string, action: string) => { await api(`clinic/appointments/${id}/${action}`, { body: {} }); await reload(); });
  const startVisit = useAction(async (e: Entry) => {
    await api(`clinic/appointments/${e.id}/start`, { body: {} });
    const enc = await api<{ id: string }>("clinic/encounters", { body: { patientId: e.patientId, appointmentId: e.id } });
    router.push(`/dashboard/encounters/${enc.id}`);
  });
  // The secretary only moves the patient into the room; the doctor opens the exam from "Current exam".
  const [confirm, setConfirm] = useState<Entry | null>(null);
  const sendIn = useAction(async (e: Entry) => { await api(`clinic/appointments/${e.id}/start`, { body: {} }); setConfirm(null); await reload(); });
  const trySendIn = (e: Entry) => {
    const busy = queue.data?.inProgress.find((x) => x.doctorId === e.doctorId);
    if (busy && confirm?.id !== e.id) setConfirm(e); else void sendIn.run(e);
  };
  const openVisit = useAction(async (e: Entry) => {
    const enc = await api<{ id: string }>("clinic/encounters", { body: { patientId: e.patientId, appointmentId: e.id } });
    router.push(`/dashboard/encounters/${enc.id}`);
  });

  if (queue.loading && !queue.data) return <Loading />;
  const q = queue.data;
  const next = waitingNow[0];
  const clinical = can("medical_note.create");

  return (
    <>
      <PageHeader
        title={t("nav.queue")}
        subtitle={t("queue.hint")}
        actions={(doctors.data?.length ?? 0) > 1 ? <Select value={doctorId} onChange={(e) => setDoctorId(e.target.value)}><option value="">{t("appt.allDoctors")}</option>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select> : undefined}
      />
      <ErrorText error={queue.error ?? act.error ?? startVisit.error ?? openVisit.error ?? sendIn.error ?? addReserved.error} />
      {confirm && (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm">
          <p>{t("queue.sendInConfirm", { name: queue.data?.inProgress.find((x) => x.doctorId === confirm.doctorId)?.patientName ?? "", next: confirm.patientName })}</p>
          <div className="flex gap-2"><Button size="sm" loading={sendIn.loading} onClick={() => sendIn.run(confirm)}>{t("queue.sendInYes")}</Button><Button size="sm" variant="ghost" onClick={() => setConfirm(null)}>{t("common.cancel")}</Button></div>
        </div>
      )}

      <div className="mb-6 grid gap-4 lg:grid-cols-2">
        <Card className="border-2 border-brand/40">
          <p className="mb-2 text-sm font-medium text-slate-500">{t("queue.nowServing")}</p>
          {q?.inProgress.length ? q.inProgress.map((e) => (
            <div key={e.id} onClick={openOnClick(() => router.push(`/dashboard/patients/${e.patientId}`))} className="flex cursor-pointer items-center justify-between gap-3">
              <div>
                <p className="flex flex-wrap items-center gap-2 text-2xl font-bold">{e.patientName} <VisitTypeEditable appointmentId={e.id} type={e.visitType} onChanged={queue.reload} /></p>
                <p className="text-sm text-slate-500">#{e.queueNumber} · {e.serviceName} · {e.doctorName}</p>
              </div>
              {clinical && <Button onClick={() => openVisit.run(e)} loading={openVisit.loading}>{t("queue.openVisit")}</Button>}
            </div>
          )) : <p className="text-slate-400">{t("queue.nobody")}</p>}
        </Card>
        <Card className="bg-brand/5">
          <p className="mb-2 text-sm font-medium text-slate-500">{t("queue.next")}</p>
          {next ? (
            <div onClick={openOnClick(() => router.push(`/dashboard/patients/${next.patientId}`))} className="flex cursor-pointer items-center justify-between gap-3">
              <div>
                <p className="text-2xl font-bold">{next.patientName}</p>
                <p className="text-sm text-slate-500">#{next.queueNumber} · {t("queue.waited", { n: next.waitedMinutes ?? 0 })}</p>
              </div>
              <div className="flex flex-col gap-2">
                <Button onClick={() => act.run(next.id, "call")} loading={act.loading} variant={next.calledAt ? "secondary" : "primary"}>{next.calledAt ? t("queue.callAgain") : t("queue.call")}</Button>
                {clinical && <Button variant="secondary" onClick={() => startVisit.run(next)} loading={startVisit.loading}>{t("appt.startVisit")}</Button>}
                {!clinical && can("appointment.manage") && <Button variant="secondary" onClick={() => trySendIn(next)} loading={sendIn.loading}>{t("queue.sendIn")}</Button>}
              </div>
            </div>
          ) : <p className="text-slate-400">{t("queue.empty")}</p>}
        </Card>
      </div>

      <h2 className="mb-1 font-medium">{t("queue.waiting")} ({waitingNow.length})</h2>
      {waitingNow.length > 1 && can("appointment.manage") && <p className="mb-2 text-xs text-slate-500">{t("queue.reorderHint")}</p>}
      {waitingNow.length === 0 ? <Empty>{t("queue.empty")}</Empty> : (
        <Reorder.Group axis="y" values={waitingNow} onReorder={arrange} className="space-y-2">
          <AnimatePresence initial={false}>
            {waitingNow.map((e, i) => (
              <WaitingRow key={e.id} e={e} i={i} last={i === waitingNow.length - 1} canReorder={can("appointment.manage")} clinical={clinical} canManage={can("appointment.manage")}
                onCall={() => act.run(e.id, "call")} onSendIn={() => trySendIn(e)} onNoShow={() => act.run(e.id, "no-show")} onRemove={() => removeEntry(e)} onMove={(d) => move(i, d)} onChanged={queue.reload} onOpen={() => router.push(`/dashboard/patients/${e.patientId}`)} />
            ))}
          </AnimatePresence>
        </Reorder.Group>
      )}

      {(q?.reserved?.length ?? 0) > 0 && (
        <section className="mt-8">
          <h2 className="mb-2 flex items-center gap-2 font-medium">{t("queue.reserved")} <span className="rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-bold text-amber-800">{q?.reserved?.length}</span></h2>
          <ul className="space-y-2">
            {q?.reserved?.map((r, i) => (
              <motion.li key={r.id} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.04 }} onClick={openOnClick(() => router.push(`/dashboard/patients/${r.patientId}`))} className="flex cursor-pointer flex-wrap items-center justify-between gap-3 rounded-2xl border border-amber-200 bg-amber-50/50 p-4 text-sm transition hover:border-amber-400">
                <div className="flex items-center gap-3">
                  <span className="flex min-w-[4.5rem] flex-col items-center rounded-xl bg-amber-100 px-3 py-2 font-bold text-amber-800"><Icon name="clock" className="h-4 w-4" /><span dir="ltr">{timeOnly(r.startAt, locale, timezone)}</span></span>
                  <div>
                    <p className="flex flex-wrap items-center gap-2 font-medium">{r.patientName} <span className="font-mono text-xs text-slate-500">{r.patientCode}</span> <VisitTypeBadge type={r.visitType} /></p>
                    <p className="text-slate-500">{[r.patientAgeYears != null ? `${r.patientAgeYears} ${t("patients.years")}` : "", r.patientSex === "F" ? t("patients.female") : r.patientSex === "M" ? t("patients.male") : "", r.serviceName, r.doctorName].filter(Boolean).join(" · ")}</p>
                    {r.patientPhone && <a href={`tel:${r.patientPhone}`} dir="ltr" className="text-xs font-medium text-brand">{r.patientPhone}</a>}
                    {r.status === "PENDING_CONFIRMATION" && <p className="text-xs text-amber-700">{t("queue.awaitingConfirm")}</p>}
                  </div>
                </div>
                {can("appointment.manage") && <Button size="sm" loading={adding === r.id} disabled={adding !== null} onClick={() => addReserved.run(r)}>{t("queue.addToQueue")}</Button>}
              </motion.li>
            ))}
          </ul>
        </section>
      )}

      <AnimatePresence>
        {toast && (
          <motion.div initial={{ opacity: 0, y: -30 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -30 }} className="fixed inset-x-3 top-4 z-50 mx-auto flex max-w-md items-center justify-between gap-3 rounded-full bg-slate-900 px-5 py-3 text-sm text-white shadow-2xl">
            <span className="truncate">{t("queue.removed", { name: toast.name })}</span>
            <button onClick={undoRemove} className="shrink-0 rounded-full bg-white/20 px-3 py-1 font-bold hover:bg-white/30">{t("queue.undo")}</button>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
