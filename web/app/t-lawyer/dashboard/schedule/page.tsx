"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { api } from "@/lib/client";
import { calColor, ymd, type CalItem } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Sheet, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { CaseFields, CasePicker, caseBody, emptyCase, type CaseDraft } from "@/components/lawyer/case-form";
import { StepFields } from "@/components/lawyer/step-fields";
import { BRASS, ContactButtons, Eyebrow, IVORY, NAVY, useIntl } from "@/components/lawyer/kit";
import { Button, ErrorText, Field, Input, PageHeader, Tabs, Textarea, cx } from "@/components/ui";
import { timeOnly } from "@/lib/format";

type View = "month" | "week" | "day";
const HOURS = Array.from({ length: 15 }, (_, i) => i + 8);   // 08:00 .. 22:00
const startOfWeek = (d: Date) => { const x = new Date(d); x.setHours(0, 0, 0, 0); x.setDate(x.getDate() - ((x.getDay() + 1) % 7)); return x; };   // Saturday first
const addDays = (d: Date, n: number) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const pad = (n: number) => String(n).padStart(2, "0");
const NOTE_COLORS = ["", "#ef4444", "#f59e0b", "#10b981", "#3b82f6", "#8b5cf6"];
const tint = (hex: string, pct = 14) => `color-mix(in srgb, ${hex} ${pct}%, transparent)`;

export default function Schedule() {
  const { t, locale } = useI18n();
  const intl = useIntl();
  const toast = useToast();
  const reduce = useReducedMotion();
  const { canWrite } = useMe();
  const write = canWrite("schedule");
  const [view, setView] = useState<View>("week");
  const [cursor, setCursor] = useState(() => new Date());
  const [adding, setAdding] = useState<{ at: string } | null>(null);
  const [open, setOpen] = useState<CalItem | null>(null);
  const [drag, setDrag] = useState<CalItem | null>(null);
  const [filter, setFilter] = useState<string[]>(["BOOKING", "STEP", "NOTE"]);

  const days = useMemo(() => {
    if (view === "day") return [new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate())];
    if (view === "week") { const s = startOfWeek(cursor); return Array.from({ length: 7 }, (_, i) => addDays(s, i)); }
    const first = new Date(cursor.getFullYear(), cursor.getMonth(), 1);
    const s = startOfWeek(first);
    return Array.from({ length: 42 }, (_, i) => addDays(s, i));
  }, [view, cursor]);
  const from = ymd(days[0]), to = ymd(days[days.length - 1]);
  const { data, loading, error, reload } = useApi<CalItem[]>(`lawyer/schedule?from=${from}&to=${to}`);
  const items = useMemo(() => (data ?? []).filter((i) => filter.includes(i.kind)), [data, filter]);
  const byDay = useMemo(() => {
    const m: Record<string, CalItem[]> = {};
    for (const i of items) (m[ymd(new Date(i.startsAt))] ??= []).push(i);
    return m;
  }, [items]);
  const move = useAction(async (i: CalItem, startsAt: string) => { await api("lawyer/schedule/move", { body: { kind: i.kind, id: i.id, startsAt } }); await reload(); toast(t("lw.sch.moved")); });
  const step = (n: number) => setCursor((c) => view === "month" ? new Date(c.getFullYear(), c.getMonth() + n, 1) : addDays(c, n * (view === "week" ? 7 : 1)));
  const title = view === "month" ? new Intl.DateTimeFormat(intl, { month: "long", year: "numeric" }).format(cursor)
    : view === "week" ? `${new Intl.DateTimeFormat(intl, { day: "numeric", month: "short" }).format(days[0])} – ${new Intl.DateTimeFormat(intl, { day: "numeric", month: "short", year: "numeric" }).format(days[6])}`
      : new Intl.DateTimeFormat(intl, { weekday: "long", day: "numeric", month: "long" }).format(cursor);
  const today = ymd(new Date());
  const addAt = (k: string, h = 10) => write && setAdding({ at: `${k}T${pad(h)}:00` });

  /** Dropping keeps the time of day (month view) or takes the slot's hour (week/day). */
  const dropTo = (day: Date, hour?: number) => {
    if (!drag || !write) return;
    const old = new Date(drag.startsAt);
    const n = new Date(day);
    n.setHours(hour ?? old.getHours(), hour === undefined ? old.getMinutes() : 0, 0, 0);
    if (n.getTime() !== old.getTime()) void move.run(drag, n.toISOString());
    setDrag(null);
  };
  const chip = (i: CalItem) => {
    const c = calColor(i);
    return (
      <button key={i.kind + i.id} type="button" draggable={write} onDragStart={() => setDrag(i)} onDragEnd={() => setDrag(null)} onClick={(e) => { e.stopPropagation(); setOpen(i); }}
        className={cx("block w-full truncate rounded-[7px] border-s-[3px] px-1.5 py-1 text-start text-[11.5px] font-semibold text-ink transition hover:brightness-95", write && "cursor-grab active:cursor-grabbing", i.done && "line-through opacity-60")}
        style={{ background: tint(c.dot), borderColor: c.dot }}>
        {!i.allDay && <span className="me-1 text-muted tnum" dir="ltr">{timeOnly(i.startsAt, locale)}</span>}{i.title}
      </button>
    );
  };
  const kinds = (["BOOKING", "STEP", "NOTE"] as const);
  const outside = (data ?? []).some((i) => { const hr = new Date(i.startsAt).getHours(); return !i.allDay && (hr < 8 || hr > 22); });

  return (
    <div className="space-y-4">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.office")}</Eyebrow>} title={t("lw.nav.schedule")} subtitle={t("lw.sch.subtitle")}
        actions={write && <Button onClick={() => { const d = new Date(); d.setMinutes(0, 0, 0); d.setHours(d.getHours() + 1); setAdding({ at: `${ymd(d)}T${pad(d.getHours())}:00` }); }}><Icon name="plus" className="h-4 w-4" />{t("lw.sch.add")}</Button>} />

      {/* Diary toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-[18px] bg-surface p-2.5 shadow-card">
        <div className="flex min-w-0 items-center gap-1">
          <button type="button" onClick={() => step(-1)} aria-label={t("common.prev")} className="grid h-10 w-10 place-items-center rounded-[10px] text-ink-2 transition hover:bg-surface-2"><Icon name="chevron" className="h-4 w-4 rotate-180 flip-rtl" /></button>
          <button type="button" onClick={() => setCursor(new Date())} className="h-10 rounded-[10px] px-3 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:bg-surface-2">{t("range.today")}</button>
          <button type="button" onClick={() => step(1)} aria-label={t("common.next")} className="grid h-10 w-10 place-items-center rounded-[10px] text-ink-2 transition hover:bg-surface-2"><Icon name="chevron" className="h-4 w-4 flip-rtl" /></button>
          <h2 className="ms-2 truncate font-display text-[17px] font-semibold text-ink sm:text-[19px]">{title}</h2>
          {loading && data && <span className="ms-2 h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent text-faint" aria-hidden />}
        </div>
        <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
          <div className="flex gap-1.5 overflow-x-auto [scrollbar-width:none]">
            {kinds.map((k) => {
              const c = calColor({ kind: k, stepKind: "HEARING", status: "APPROVED" });
              const on = filter.includes(k);
              return <button key={k} type="button" aria-pressed={on} onClick={() => setFilter(on ? filter.filter((x) => x !== k) : [...filter, k])} className={cx("chip", !on && "opacity-45")}><span className="h-2.5 w-2.5 rounded-full" style={{ background: c.dot }} />{t(`lw.sch.kind.${k}`)}</button>;
            })}
          </div>
          <div className="ms-auto"><Tabs value={view} onChange={setView} tabs={[{ key: "day", label: t("lw.sch.day") }, { key: "week", label: t("lw.sch.week") }, { key: "month", label: t("lw.sch.month") }]} /></div>
        </div>
      </div>
      <ErrorText error={error ?? move.error} />

      {loading && !data ? (
        <div className="skeleton h-[28rem] !rounded-[18px]" aria-busy="true" />
      ) : (
        <AnimatePresence mode="wait" initial={false}>
          <motion.div key={view + from} initial={reduce ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={reduce ? undefined : { opacity: 0 }} transition={{ duration: 0.18 }}>
            {view === "month" ? (
              <div className="overflow-hidden rounded-[18px] bg-surface shadow-card">
                <div className="grid grid-cols-7 text-center text-[11px] font-semibold uppercase tracking-wide" style={{ background: NAVY, color: IVORY }}>
                  {days.slice(0, 7).map((d) => <div key={d.toISOString()} className="py-2.5">{new Intl.DateTimeFormat(intl, { weekday: "short" }).format(d)}</div>)}
                </div>
                <div className="grid grid-cols-7">
                  {days.map((d) => {
                    const k = ymd(d), list = byDay[k] ?? [], other = d.getMonth() !== cursor.getMonth();
                    return (
                      <div key={k} onDragOver={(e) => e.preventDefault()} onDrop={() => dropTo(d)} onClick={() => addAt(k)}
                        className={cx("min-h-[78px] min-w-0 border-b border-e border-line p-1 transition sm:min-h-[108px] sm:p-1.5", write && "cursor-pointer hover:bg-surface-2", other && "bg-surface-2/60 text-faint", drag && "hover:ring-2 hover:ring-inset hover:ring-brand")}>
                        <p className={cx("mb-1 grid h-6 w-6 place-items-center rounded-full text-[11.5px] font-semibold tnum sm:h-7 sm:w-7", k === today && "text-[#14213d]")} style={k === today ? { background: "#d9b97f" } : undefined}>{d.getDate()}</p>
                        {/* phones: dots only; larger screens: chips */}
                        <div className="flex flex-wrap gap-1 sm:hidden">{list.slice(0, 4).map((i) => <span key={i.kind + i.id} className="h-1.5 w-1.5 rounded-full" style={{ background: calColor(i).dot }} />)}</div>
                        <div className="hidden space-y-1 sm:block">{list.slice(0, 3).map((i) => chip(i))}</div>
                        {list.length > 0 && <button type="button" onClick={(e) => { e.stopPropagation(); setCursor(d); setView("day"); }} className={cx("mt-0.5 text-[10.5px] font-semibold text-muted hover:text-ink", list.length <= 3 && "sm:hidden")}>{list.length > 3 ? `+${list.length - 3}` : list.length}</button>}
                      </div>
                    );
                  })}
                </div>
              </div>
            ) : (
              <>
                {/* Phones (week view): a day-by-day agenda like a pocket diary */}
                {view === "week" && (
                  <div className="space-y-3 sm:hidden">
                    {days.map((d) => {
                      const k = ymd(d), list = (byDay[k] ?? []).slice().sort((a, b) => a.startsAt.localeCompare(b.startsAt));
                      return (
                        <section key={k} className="overflow-hidden rounded-[16px] bg-surface shadow-card">
                          <div className="flex items-center justify-between gap-2 px-4 py-2.5 hairline-b" style={k === today ? { background: tint(BRASS, 14) } : undefined}>
                            <button type="button" onClick={() => { setCursor(d); setView("day"); }} className="flex items-baseline gap-2 text-start">
                              <span className="font-display text-[18px] font-semibold text-ink tnum">{d.getDate()}</span>
                              <span className="text-[13px] font-semibold text-muted">{new Intl.DateTimeFormat(intl, { weekday: "long", month: "short" }).format(d)}</span>
                            </button>
                            {write && <button type="button" onClick={() => addAt(k)} aria-label={t("lw.sch.add")} className="grid h-11 w-11 place-items-center rounded-[10px] text-muted hover:bg-surface-2 hover:text-ink"><Icon name="plus" className="h-4 w-4" /></button>}
                          </div>
                          {list.length === 0 ? <p className="px-4 py-3 text-xs text-faint">{t("lw.sch.nothing")}</p> : (
                            <ul className="space-y-1.5 p-2.5">{list.map((i) => <li key={i.kind + i.id}>{chip(i)}</li>)}</ul>
                          )}
                        </section>
                      );
                    })}
                  </div>
                )}
                <div className={cx("overflow-hidden rounded-[18px] bg-surface shadow-card", view === "week" && "hidden sm:block")}>
                  <div className="overflow-x-auto">
                    <div className={cx("grid", view === "week" ? "min-w-[760px] grid-cols-[56px_repeat(7,minmax(0,1fr))]" : "grid-cols-[56px_minmax(0,1fr)]")}>
                      <div className="hairline-b" />
                      {days.map((d) => {
                        const isToday = ymd(d) === today;
                        return (
                          <button key={d.toISOString()} type="button" onClick={() => { setCursor(d); setView("day"); }} className="border-s border-line py-2.5 text-center hairline-b transition hover:bg-surface-2" style={isToday ? { background: tint(BRASS, 12) } : undefined}>
                            <p className="text-[11px] font-semibold uppercase tracking-wide text-muted">{new Intl.DateTimeFormat(intl, { weekday: "short" }).format(d)}</p>
                            <p className={cx("mx-auto mt-0.5 grid h-8 w-8 place-items-center rounded-full font-display text-[17px] font-semibold tnum", isToday ? "text-[#14213d]" : "text-ink")} style={isToday ? { background: "#d9b97f" } : undefined}>{d.getDate()}</p>
                          </button>
                        );
                      })}
                      {days.some((d) => (byDay[ymd(d)] ?? []).some((i) => i.allDay)) && <>
                        <div className="p-1 text-[10px] font-medium text-faint hairline-b">{t("lw.sch.allDay")}</div>
                        {days.map((d) => <div key={"ad" + d.toISOString()} className="space-y-1 border-s border-line p-1 hairline-b">{(byDay[ymd(d)] ?? []).filter((i) => i.allDay).map((i) => chip(i))}</div>)}
                      </>}
                      {HOURS.map((h) => (
                        <div key={h} className="contents">
                          <div className="border-b border-line/60 px-1.5 pt-1 text-end text-[11px] text-faint tnum" dir="ltr">{pad(h)}:00</div>
                          {days.map((d) => {
                            const k = ymd(d);
                            const list = (byDay[k] ?? []).filter((i) => !i.allDay && new Date(i.startsAt).getHours() === h);
                            return (
                              <div key={k + h} onDragOver={(e) => e.preventDefault()} onDrop={() => dropTo(d, h)} onClick={() => addAt(k, h)}
                                className={cx("min-h-[52px] min-w-0 space-y-1 border-b border-s border-line/60 p-1 transition", write && "cursor-pointer hover:bg-surface-2")} style={k === today ? { background: tint(BRASS, 5) } : undefined}>
                                {list.map((i) => chip(i))}
                              </div>
                            );
                          })}
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              </>
            )}
          </motion.div>
        </AnimatePresence>
      )}
      {outside && view !== "month" && <p className="flex items-center gap-1.5 text-xs text-muted"><Icon name="clock" className="h-3.5 w-3.5" />{t("lw.sch.outside")}</p>}
      {write && <p className="hidden text-xs text-faint sm:block">{t("lw.sch.dragHint")}</p>}
      <AddSheet at={adding?.at ?? null} onClose={() => setAdding(null)} onSaved={async () => { setAdding(null); toast(t("common.saved")); await reload(); }} />
      <ItemSheet item={open} onClose={() => setOpen(null)} onChanged={async () => { setOpen(null); await reload(); }} />
    </div>
  );
}

/** Add from the calendar: a note, a case step (on an existing case or a new one made here), or a session booked by phone. */
function AddSheet({ at, onClose, onSaved }: { at: string | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  return (
    <Sheet open={!!at} onClose={onClose} title={t("lw.sch.add")} width="34rem"
      footer={<Button type="submit" form="lw-add-form" className="w-full">{t("common.save")}</Button>}>
      {at && <AddForm key={at} at={at} onSaved={onSaved} />}
    </Sheet>
  );
}

function AddForm({ at, onSaved }: { at: string; onSaved: () => void }) {
  const { t } = useI18n();
  const fe = useFieldErrors();
  const [kind, setKind] = useState<"NOTE" | "STEP" | "BOOKING">("STEP");
  const [pick, setPick] = useState<{ id: string; label: string } | null>(null);
  const [newCase, setNewCase] = useState(false);
  const [cd, setCd] = useState<CaseDraft>(emptyCase());
  const [note, setNote] = useState({ title: "", body: "", allDay: false, color: "", startsAt: at });
  const [st, setSt] = useState({ kind: "HEARING", startsAt: at, durationMinutes: "60", description: "", location: "", result: "", done: false });
  const [bk, setBk] = useState({ name: "", phone: "", topic: "", startsAt: at });
  const save = useAction(async () => {
    try {
      if (kind === "NOTE") {
        if (!fe.check({ title: !note.title.trim() && t("srv.Write the note"), "newCase.title": newCase && cd.title.trim().length < 3 && t("srv.Give the case a clear title") })) return;
        await api("lawyer/schedule/notes", { body: { startsAt: new Date(note.startsAt).toISOString(), allDay: note.allDay, title: note.title, body: note.body, color: note.color, caseId: newCase ? null : pick?.id ?? null, newCase: newCase ? caseBody(cd) : null } });
      } else if (kind === "STEP") {
        if (!fe.check({ "step.description": !st.description.trim() && t("srv.Describe this step"), caseId: !newCase && !pick && t("srv.Pick a case or create a new one"), "newCase.title": newCase && cd.title.trim().length < 3 && t("srv.Give the case a clear title") })) return;
        await api("lawyer/schedule/steps", { body: { caseId: newCase ? null : pick?.id, newCase: newCase ? caseBody(cd) : null, step: { kind: st.kind, startsAt: new Date(st.startsAt).toISOString(), durationMinutes: Number(st.durationMinutes), description: st.description, location: st.location } } });
      } else {
        if (!fe.check({ name: !bk.name.trim() && t("srv.Write the client's name") })) return;
        await api("lawyer/bookings", { body: { name: bk.name, phone: bk.phone, topic: bk.topic, startsAt: new Date(bk.startsAt).toISOString(), caseId: pick?.id ?? null } });
      }
    } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });
  const caseBlock = (optional: boolean) => (
    <div data-field="caseId" className="space-y-3 rounded-[14px] bg-surface-2 p-3.5 ring-1 ring-line">
      <div className="flex flex-wrap items-center justify-between gap-2"><p className="text-[13px] font-semibold text-ink-2">{t("lw.sch.forCase")}{optional && <span className="font-normal text-faint"> ({t("offer.optional")})</span>}</p>
        <div className="flex gap-1"><button type="button" onClick={() => setNewCase(false)} className={cx("chip", !newCase && "chip-on")}>{t("lw.sch.existing")}</button><button type="button" onClick={() => { setNewCase(true); setPick(null); }} className={cx("chip", newCase && "chip-on")}><Icon name="plus" className="h-3.5 w-3.5" />{t("lw.case.new")}</button></div></div>
      {newCase ? <CaseFields d={cd} set={(x) => setCd((o) => ({ ...o, ...x }))} err={fe.err} p="newCase." /> : <CasePicker value={pick} onChange={(v) => { setPick(v); fe.clear("caseId"); }} />}
      {fe.err("caseId") && <p className="text-xs font-medium text-red-600">{fe.err("caseId")}</p>}
    </div>
  );
  return (
    <form id="lw-add-form" onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
      <Tabs value={kind} onChange={(k) => { setKind(k); fe.clear(); }} tabs={[{ key: "STEP", label: t("lw.sch.kind.STEP") }, { key: "NOTE", label: t("lw.sch.kind.NOTE") }, { key: "BOOKING", label: t("lw.sch.kind.BOOKING") }]} />
      {kind === "STEP" && <><StepFields f={st} set={(x) => setSt((o) => ({ ...o, ...x }))} err={fe.err} p="step." />{caseBlock(false)}</>}
      {kind === "NOTE" && (
        <>
          <Field name="title" error={fe.err("title")} label={t("lw.note.title")}><Input value={note.title} onChange={(e) => setNote({ ...note, title: e.target.value })} placeholder={t("lw.note.ph")} /></Field>
          <Field label={t("lw.note.body")}><Textarea rows={3} value={note.body} onChange={(e) => setNote({ ...note, body: e.target.value })} /></Field>
          <Field name="startsAt" error={fe.err("startsAt")} label={t("lw.step.when")}><Input type={note.allDay ? "date" : "datetime-local"} value={note.allDay ? note.startsAt.slice(0, 10) : note.startsAt} onChange={(e) => setNote({ ...note, startsAt: note.allDay ? `${e.target.value}T09:00` : e.target.value })} /></Field>
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-[12px] bg-surface-2 px-3.5 py-3 ring-1 ring-line">
            <span className="flex items-center gap-2.5 text-[13px] font-semibold text-ink-2"><Switch checked={note.allDay} onChange={(v) => setNote({ ...note, allDay: v })} label={t("lw.sch.allDay")} />{t("lw.sch.allDay")}</span>
            <div className="flex gap-1.5">{NOTE_COLORS.map((c) => <button key={c || "x"} type="button" onClick={() => setNote({ ...note, color: c })} className={cx("h-7 w-7 rounded-full ring-2 ring-offset-2 ring-offset-surface", note.color === c ? "ring-ink" : "ring-transparent")} style={{ background: c || "#eab308" }} aria-label={c || "default"} aria-pressed={note.color === c} />)}</div>
          </div>
          {caseBlock(true)}
        </>
      )}
      {kind === "BOOKING" && (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field name="name" error={fe.err("name")} label={t("lw.client.name")}><Input value={bk.name} onChange={(e) => setBk({ ...bk, name: e.target.value })} /></Field>
            <Field name="phone" error={fe.err("phone")} label={t("lw.client.phone")}><Input dir="ltr" inputMode="tel" value={bk.phone} onChange={(e) => setBk({ ...bk, phone: e.target.value })} /></Field>
          </div>
          <Field name="startsAt" error={fe.err("startsAt")} label={t("lw.step.when")}><Input type="datetime-local" value={bk.startsAt} onChange={(e) => setBk({ ...bk, startsAt: e.target.value })} /></Field>
          <Field label={t("lw.bk.topic")}><Textarea rows={2} value={bk.topic} onChange={(e) => setBk({ ...bk, topic: e.target.value })} /></Field>
        </>
      )}
      {fe.any ? <p className="rounded-[12px] bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{t("form.fixMarked")}</p> : <ErrorText error={save.error} />}
      {save.loading && <p className="text-xs text-muted">…</p>}
    </form>
  );
}

function ItemSheet({ item, onClose, onChanged }: { item: CalItem | null; onClose: () => void; onChanged: () => void }) {
  const { t } = useI18n();
  const intl = useIntl();
  const toast = useToast();
  const { canWrite } = useMe();
  const [confirm, setConfirm] = useState(false);
  const del = useAction(async (id: string) => { await api(`lawyer/schedule/notes/${id}`, { method: "DELETE" }); setConfirm(false); toast(t("lw.toast.deleted")); onChanged(); });
  const c = item ? calColor(item) : null;
  return (
    <>
      <Sheet open={!!item} onClose={onClose} title={item ? t(`lw.cal.${item.kind === "STEP" ? item.stepKind : item.kind}`) : ""}
        footer={item?.kind === "NOTE" && canWrite("schedule") ? <Button variant="secondary" className="w-full !text-red-600" onClick={() => setConfirm(true)}><Icon name="close" className="h-4 w-4" />{t("offer.delete")}</Button> : undefined}>
        {item && c && (
          <div className="space-y-4">
            <div className="overflow-hidden rounded-[16px] ring-1 ring-line">
              <div className="h-1" style={{ background: c.dot }} />
              <div className="p-4">
                <p className="text-[12.5px] font-semibold text-muted">{new Date(item.startsAt).toLocaleString(intl, { weekday: "long", day: "numeric", month: "long", hour: item.allDay ? undefined : "numeric", minute: item.allDay ? undefined : "2-digit" })}</p>
                <p className={cx("mt-1 font-display text-[19px] font-semibold text-ink", item.done && "line-through opacity-70")}>{item.title}</p>
                {item.location && <p className="mt-1 flex items-center gap-1.5 text-sm text-muted"><Icon name="pin" className="h-4 w-4" />{item.location}</p>}
              </div>
            </div>
            {item.body && <p className="whitespace-pre-line text-sm leading-relaxed text-ink-2">{item.body}</p>}
            {item.phone && <div className="flex items-center justify-between gap-2 rounded-[12px] bg-surface-2 px-3.5 py-2.5 ring-1 ring-line"><span dir="ltr" className="text-sm font-semibold text-ink tnum">{item.phone}</span><ContactButtons phone={item.phone} /></div>}
            {item.caseId && (
              <Link href={`/dashboard/cases/${item.caseId}`} className="group flex items-center gap-3 rounded-[12px] px-3.5 py-3 ring-1 ring-line transition hover:bg-surface-2">
                <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[10px]" style={{ background: NAVY, color: "#d9b97f" }}><Icon name="briefcase" className="h-4 w-4" /></span>
                <span className="min-w-0 flex-1"><span className="block font-mono text-[11.5px] text-muted">#{item.caseNumber}</span><span className="block truncate text-sm font-semibold text-ink">{item.caseTitle ?? ""}</span></span>
                <Icon name="chevron" className="h-4 w-4 text-faint flip-rtl" />
              </Link>
            )}
            {item.kind === "BOOKING" && <Link href="/dashboard/bookings" className="inline-flex items-center gap-1 text-sm font-semibold text-brand">{t("lw.sch.manageBooking")}<Icon name="chevron" className="h-3.5 w-3.5 flip-rtl" /></Link>}
            <ErrorText error={del.error} />
          </div>
        )}
      </Sheet>
      <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} onConfirm={() => item && void del.run(item.id)} loading={del.loading} danger
        title={t("lw.sch.delNoteTitle")} body={item?.title} confirmLabel={t("offer.delete")} cancelLabel={t("common.cancel")} />
    </>
  );
}
