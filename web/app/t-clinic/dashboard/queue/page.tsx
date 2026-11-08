"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, Reorder, motion, useDragControls } from "motion/react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Avatar, Button, Empty, ErrorText, Loading, PageHeader, Select } from "@/components/ui";
import { ConfirmDialog, Menu, type MenuItem } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { MoreTrigger, Paper, PaperHead, Pill } from "@/components/clinic/dash";
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
  const waited = e.waitedMinutes ?? 0;
  const more: MenuItem[] = [];
  if (i > 0) more.push({ label: t("queue.call"), icon: "phone", onSelect: onCall });
  if (canManage) more.push({ label: t("queue.remove"), icon: "close", onSelect: onRemove });
  more.push({ label: t("appt.noShow"), icon: "clock", danger: true, onSelect: onNoShow });
  return (
    <Reorder.Item value={e} dragListener={false} dragControls={controls} layout="position" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, x: locale === "ar" ? 80 : -80, height: 0, marginBottom: 0 }} whileDrag={{ scale: 1.015, boxShadow: "0 18px 40px -12px rgb(15 35 60 / 0.35)", zIndex: 20 }} className="relative list-none">
      <div onClick={openOnClick(onOpen)} className="flex cursor-pointer flex-col gap-3 rounded-[16px] bg-surface p-3.5 shadow-card transition hover:shadow-[0_0_0_1px_color-mix(in_srgb,var(--rail-accent)_40%,transparent)] sm:flex-row sm:items-center sm:p-4">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          {canReorder && (
            <div className="flex shrink-0 flex-col items-center text-faint">
              <button type="button" aria-label={t("queue.moveUp")} disabled={i === 0} onClick={() => onMove(-1)} className="rounded p-0.5 hover:text-ink disabled:opacity-20 pointer-coarse:p-1.5"><Icon name="arrow" className="h-3.5 w-3.5 -rotate-90" /></button>
              <span data-nogo onPointerDown={(ev) => controls.start(ev)} className="cursor-grab touch-none select-none px-1 text-lg leading-none active:cursor-grabbing" title={t("queue.reorderHint")} aria-hidden>⠿</span>
              <button type="button" aria-label={t("queue.moveDown")} disabled={last} onClick={() => onMove(1)} className="rounded p-0.5 hover:text-ink disabled:opacity-20 pointer-coarse:p-1.5"><Icon name="arrow" className="h-3.5 w-3.5 rotate-90" /></button>
            </div>
          )}
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-ink font-display text-lg font-semibold text-surface tnum">{e.queueNumber}</span>
          <div className="min-w-0">
            <p className="flex flex-wrap items-center gap-x-2 gap-y-1 font-semibold text-ink">{e.patientName} <span className="font-mono text-[11px] font-normal text-faint">{e.patientCode}</span> <VisitTypeEditable appointmentId={e.id} type={e.visitType} onChanged={onChanged} /></p>
            <p className="mt-0.5 text-[13px] text-muted">{e.serviceName} · {e.doctorName} · {t("queue.booked", { time: timeOnly(e.startAt, locale, timezone) })}</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2 ps-14 sm:ps-0">
          <Pill tone={waited > 30 ? "rose" : waited > 15 ? "amber" : "slate"}><Icon name="clock" className="h-3 w-3" />{t("queue.waited", { n: waited })}</Pill>
          {e.calledAt && <Pill tone="sky">{t("queue.called")}</Pill>}
          {i > 0 && !clinical && canManage && <Button size="sm" variant="secondary" onClick={onSendIn}>{t("queue.sendIn")}</Button>}
          <Menu label={t("cld.moreActions")} items={more} trigger={({ toggle }) => <MoreTrigger toggle={toggle} label={t("cld.moreActions")} />} />
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
  const toast = useToast();
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
    toast(t("queue.removed", { name: e.patientName }), { tone: "info", ms: 3300, action: { label: t("queue.undo"), run: () => undoRemove(e.id) } });
    removals.current.set(e.id, setTimeout(async () => {
      removals.current.delete(e.id);
      try { await api(`clinic/appointments/${e.id}/remove-from-queue`, { body: {} }); } catch { /* shown by the refresh */ }
      busy.current = removals.current.size > 0;
      setRemoved((r) => r.filter((x) => x !== e.id));
      await reload();
    }, 3500));
  };
  const undoRemove = (id: string) => {
    const h = removals.current.get(id);
    if (h) clearTimeout(h);
    removals.current.delete(id);
    setRemoved((r) => r.filter((x) => x !== id));
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

  if (queue.loading && !queue.data) return <Loading variant="detail" />;
  const q = queue.data;
  const next = waitingNow[0];
  const clinical = can("medical_note.create");

  return (
    <>
      <PageHeader
        eyebrow={t("rail.today")}
        title={t("nav.queue")}
        subtitle={t("queue.hint")}
        actions={(doctors.data?.length ?? 0) > 1 ? <Select value={doctorId} onChange={(e) => setDoctorId(e.target.value)} aria-label={t("book.doctor")}><option value="">{t("appt.allDoctors")}</option>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select> : undefined}
      />
      <div className="space-y-5">
        <ErrorText error={queue.error ?? act.error ?? startVisit.error ?? openVisit.error ?? sendIn.error ?? addReserved.error} />
        <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} loading={sendIn.loading}
          title={t("queue.sendIn")}
          body={confirm ? t("queue.sendInConfirm", { name: queue.data?.inProgress.find((x) => x.doctorId === confirm.doctorId)?.patientName ?? "", next: confirm.patientName }) : undefined}
          confirmLabel={t("queue.sendInYes")} cancelLabel={t("common.cancel")} onConfirm={() => { if (confirm) void sendIn.run(confirm); }} />

        <div className="grid gap-5 lg:grid-cols-2">
          <Paper className="ring-1 ring-[color-mix(in_srgb,var(--rail-accent)_35%,transparent)]">
            <PaperHead icon="stethoscope" title={t("queue.nowServing")} />
            <div className="space-y-3 px-5 pb-5">
              {q?.inProgress.length ? q.inProgress.map((e) => (
                <div key={e.id} onClick={openOnClick(() => router.push(`/dashboard/patients/${e.patientId}`))} className="flex cursor-pointer flex-wrap items-center justify-between gap-3">
                  <div className="flex min-w-0 items-center gap-3">
                    <Avatar name={e.patientName} className="h-12 w-12 text-base" />
                    <div className="min-w-0">
                      <p className="flex flex-wrap items-center gap-2 font-display text-xl font-semibold text-ink">{e.patientName} <VisitTypeEditable appointmentId={e.id} type={e.visitType} onChanged={queue.reload} /></p>
                      <p className="text-[13px] text-muted">#{e.queueNumber} · {e.serviceName} · {e.doctorName}</p>
                    </div>
                  </div>
                  {clinical && <Button onClick={() => openVisit.run(e)} loading={openVisit.loading}>{t("queue.openVisit")}</Button>}
                </div>
              )) : <p className="py-3 text-sm text-muted">{t("queue.nobody")}</p>}
            </div>
          </Paper>
          <Paper className="bg-[color-mix(in_srgb,var(--rail-accent)_5%,var(--surface))]">
            <PaperHead icon="queue" title={t("queue.next")} />
            <div className="px-5 pb-5">
              {next ? (
                <div onClick={openOnClick(() => router.push(`/dashboard/patients/${next.patientId}`))} className="flex cursor-pointer flex-wrap items-center justify-between gap-3">
                  <div className="flex min-w-0 items-center gap-3">
                    <Avatar name={next.patientName} className="h-12 w-12 text-base" />
                    <div className="min-w-0">
                      <p className="font-display text-xl font-semibold text-ink">{next.patientName}</p>
                      <p className="text-[13px] text-muted">#{next.queueNumber} · {t("queue.waited", { n: next.waitedMinutes ?? 0 })}</p>
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button onClick={() => act.run(next.id, "call")} loading={act.loading} variant={next.calledAt ? "secondary" : "primary"}>{next.calledAt ? t("queue.callAgain") : t("queue.call")}</Button>
                    {clinical && <Button variant="secondary" onClick={() => startVisit.run(next)} loading={startVisit.loading}>{t("appt.startVisit")}</Button>}
                    {!clinical && can("appointment.manage") && <Button variant="secondary" onClick={() => trySendIn(next)} loading={sendIn.loading}>{t("queue.sendIn")}</Button>}
                  </div>
                </div>
              ) : <p className="py-3 text-sm text-muted">{t("queue.empty")}</p>}
            </div>
          </Paper>
        </div>

        <div>
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2 px-1">
            <h2 className="font-display text-[16px] font-semibold text-ink">{t("queue.waiting")} <span className="ms-1 text-muted tnum">({waitingNow.length})</span></h2>
            {waitingNow.length > 1 && can("appointment.manage") && <p className="text-xs text-muted">{t("queue.reorderHint")}</p>}
          </div>
          {waitingNow.length === 0 ? <Empty icon="queue" title={t("queue.empty")} compact /> : (
            <Reorder.Group axis="y" values={waitingNow} onReorder={arrange} className="space-y-2.5">
              <AnimatePresence initial={false}>
                {waitingNow.map((e, i) => (
                  <WaitingRow key={e.id} e={e} i={i} last={i === waitingNow.length - 1} canReorder={can("appointment.manage")} clinical={clinical} canManage={can("appointment.manage")}
                    onCall={() => act.run(e.id, "call")} onSendIn={() => trySendIn(e)} onNoShow={() => act.run(e.id, "no-show")} onRemove={() => removeEntry(e)} onMove={(d) => move(i, d)} onChanged={queue.reload} onOpen={() => router.push(`/dashboard/patients/${e.patientId}`)} />
                ))}
              </AnimatePresence>
            </Reorder.Group>
          )}
        </div>

        {(q?.reserved?.length ?? 0) > 0 && (
          <Paper>
            <PaperHead icon="calendar" title={t("queue.reserved")} action={<Pill tone="amber">{q?.reserved?.length}</Pill>} />
            <ul className="divide-y divide-line px-2 pb-2">
              {q?.reserved?.map((r) => (
                <motion.li key={r.id} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} onClick={openOnClick(() => router.push(`/dashboard/patients/${r.patientId}`))} className="flex cursor-pointer flex-wrap items-center justify-between gap-3 rounded-[12px] px-3 py-3 text-sm transition hover:bg-surface-2">
                  <div className="flex min-w-0 items-center gap-3">
                    <span className="flex min-w-[4.25rem] flex-col items-center rounded-[12px] bg-amber-50 px-3 py-2 text-[13px] font-semibold text-amber-800"><Icon name="clock" className="h-3.5 w-3.5" /><span dir="ltr" className="tnum">{timeOnly(r.startAt, locale, timezone)}</span></span>
                    <div className="min-w-0">
                      <p className="flex flex-wrap items-center gap-2 font-semibold text-ink">{r.patientName} <span className="font-mono text-[11px] font-normal text-faint">{r.patientCode}</span> <VisitTypeBadge type={r.visitType} /></p>
                      <p className="text-[13px] text-muted">{[r.patientAgeYears != null ? `${r.patientAgeYears} ${t("patients.years")}` : "", r.patientSex === "F" ? t("patients.female") : r.patientSex === "M" ? t("patients.male") : "", r.serviceName, r.doctorName].filter(Boolean).join(" · ")}</p>
                      {r.patientPhone && <a href={`tel:${r.patientPhone}`} dir="ltr" className="text-xs font-medium text-[var(--rail-accent-ink)]">{r.patientPhone}</a>}
                      {r.status === "PENDING_CONFIRMATION" && <p className="text-xs text-amber-700">{t("queue.awaitingConfirm")}</p>}
                    </div>
                  </div>
                  {can("appointment.manage") && <Button size="sm" loading={adding === r.id} disabled={adding !== null} onClick={() => addReserved.run(r)}>{t("queue.addToQueue")}</Button>}
                </motion.li>
              ))}
            </ul>
          </Paper>
        )}
      </div>
    </>
  );
}
