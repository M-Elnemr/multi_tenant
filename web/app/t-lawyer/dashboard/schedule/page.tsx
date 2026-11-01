"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { calColor, ymd, type CalItem } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { CaseFields, CasePicker, caseBody, emptyCase, type CaseDraft } from "@/components/lawyer/case-form";
import { StepFields } from "@/components/lawyer/step-fields";
import { Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Tabs, Textarea } from "@/components/ui";
import { timeOnly } from "@/lib/format";

type View = "month" | "week" | "day";
const HOURS = Array.from({ length: 15 }, (_, i) => i + 8);   // 08:00 .. 22:00
const startOfWeek = (d: Date) => { const x = new Date(d); x.setHours(0, 0, 0, 0); x.setDate(x.getDate() - ((x.getDay() + 1) % 7)); return x; };   // Saturday first
const addDays = (d: Date, n: number) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const pad = (n: number) => String(n).padStart(2, "0");
const NOTE_COLORS = ["", "#ef4444", "#f59e0b", "#10b981", "#3b82f6", "#8b5cf6"];

export default function Schedule() {
  const { t, locale } = useI18n();
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
  const items = (data ?? []).filter((i) => filter.includes(i.kind));
  const byDay = useMemo(() => {
    const m: Record<string, CalItem[]> = {};
    for (const i of items) (m[ymd(new Date(i.startsAt))] ??= []).push(i);
    return m;
  }, [items]);
  const move = useAction(async (i: CalItem, startsAt: string) => { await api("lawyer/schedule/move", { body: { kind: i.kind, id: i.id, startsAt } }); await reload(); });
  const step = (n: number) => setCursor((c) => view === "month" ? new Date(c.getFullYear(), c.getMonth() + n, 1) : addDays(c, n * (view === "week" ? 7 : 1)));
  const intl = locale === "ar" ? "ar-EG" : "en-GB";
  const title = view === "month" ? new Intl.DateTimeFormat(intl, { month: "long", year: "numeric" }).format(cursor)
    : view === "week" ? `${new Intl.DateTimeFormat(intl, { day: "numeric", month: "short" }).format(days[0])} – ${new Intl.DateTimeFormat(intl, { day: "numeric", month: "short", year: "numeric" }).format(days[6])}`
      : new Intl.DateTimeFormat(intl, { weekday: "long", day: "numeric", month: "long" }).format(cursor);
  const today = ymd(new Date());

  /** Dropping keeps the time of day (month view) or takes the slot's hour (week/day). */
  const dropTo = (day: Date, hour?: number) => {
    if (!drag || !write) return;
    const old = new Date(drag.startsAt);
    const n = new Date(day);
    n.setHours(hour ?? old.getHours(), hour === undefined ? old.getMinutes() : 0, 0, 0);
    if (n.getTime() !== old.getTime()) void move.run(drag, n.toISOString());
    setDrag(null);
  };
  const chip = (i: CalItem, compact = false) => {
    const c = calColor(i);
    return (
      <button key={i.kind + i.id} type="button" draggable={write} onDragStart={() => setDrag(i)} onDragEnd={() => setDrag(null)} onClick={(e) => { e.stopPropagation(); setOpen(i); }}
        className={`group block w-full truncate rounded-lg px-2 py-1 text-start text-xs font-semibold transition hover:brightness-95 ${write ? "cursor-grab active:cursor-grabbing" : ""} ${i.done ? "line-through opacity-60" : ""}`} style={{ background: c.bg, color: c.fg }}>
        <span className="me-1 inline-block h-1.5 w-1.5 rounded-full align-middle" style={{ background: c.dot }} />
        {!i.allDay && <span className="opacity-80">{timeOnly(i.startsAt, locale)} </span>}{compact ? "" : i.title}
      </button>
    );
  };

  return (
    <>
      <PageHeader title={t("lw.nav.schedule")} subtitle={t("lw.sch.subtitle")}
        actions={write && <Button onClick={() => { const d = new Date(); d.setMinutes(0, 0, 0); d.setHours(d.getHours() + 1); setAdding({ at: `${ymd(d)}T${pad(d.getHours())}:00` }); }}><Icon name="plus" className="h-4 w-4" />{t("lw.sch.add")}</Button>} />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-white p-3 shadow-card">
        <div className="flex items-center gap-1">
          <button type="button" onClick={() => step(-1)} className="grid h-10 w-10 place-items-center rounded-xl hover:bg-slate-100"><Icon name="arrow" className="h-4 w-4 ltr:rotate-180" /></button>
          <button type="button" onClick={() => setCursor(new Date())} className="rounded-xl px-3 py-2 text-sm font-semibold hover:bg-slate-100">{t("range.today")}</button>
          <button type="button" onClick={() => step(1)} className="grid h-10 w-10 place-items-center rounded-xl hover:bg-slate-100"><Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></button>
          <h2 className="ms-2 text-lg font-extrabold text-ink">{title}</h2>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {(["BOOKING", "STEP", "NOTE"] as const).map((k) => {
            const c = calColor({ kind: k, stepKind: "HEARING", status: "APPROVED" });
            const on = filter.includes(k);
            return <button key={k} type="button" onClick={() => setFilter(on ? filter.filter((x) => x !== k) : [...filter, k])} className={`chip ${on ? "" : "opacity-40"}`}><span className="h-2.5 w-2.5 rounded-full" style={{ background: c.dot }} />{t(`lw.sch.kind.${k}`)}</button>;
          })}
          <Tabs value={view} onChange={setView} tabs={[{ key: "day", label: t("lw.sch.day") }, { key: "week", label: t("lw.sch.week") }, { key: "month", label: t("lw.sch.month") }]} />
        </div>
      </div>
      <ErrorText error={error ?? move.error} />
      {loading && !data ? <Loading /> : view === "month" ? (
        <div className="overflow-hidden rounded-2xl bg-white shadow-card">
          <div className="grid grid-cols-7 border-b border-slate-100 bg-slate-50 text-center text-xs font-bold text-slate-500">
            {days.slice(0, 7).map((d) => <div key={d.toISOString()} className="py-2">{new Intl.DateTimeFormat(intl, { weekday: "short" }).format(d)}</div>)}
          </div>
          <div className="grid grid-cols-7">
            {days.map((d) => {
              const k = ymd(d), list = byDay[k] ?? [], other = d.getMonth() !== cursor.getMonth();
              return (
                <div key={k} onDragOver={(e) => e.preventDefault()} onDrop={() => dropTo(d)} onClick={() => write && setAdding({ at: `${k}T10:00` })}
                  className={`min-h-[104px] cursor-pointer border-b border-e border-slate-100 p-1.5 transition hover:bg-brand-soft/40 ${other ? "bg-slate-50/60 text-slate-400" : ""} ${drag ? "hover:ring-2 hover:ring-inset hover:ring-brand" : ""}`}>
                  <p className={`mb-1 grid h-7 w-7 place-items-center rounded-full text-xs font-bold ${k === today ? "bg-brand text-white" : ""}`}>{d.getDate()}</p>
                  <div className="space-y-1">{list.slice(0, 3).map((i) => chip(i))}{list.length > 3 && <button type="button" onClick={(e) => { e.stopPropagation(); setCursor(d); setView("day"); }} className="text-[11px] font-bold text-brand">+{list.length - 3}</button>}</div>
                </div>
              );
            })}
          </div>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-2xl bg-white shadow-card">
          <div className={`grid ${view === "week" ? "min-w-[860px] grid-cols-[56px_repeat(7,1fr)]" : "grid-cols-[56px_1fr]"}`}>
            <div className="border-b border-slate-100" />
            {days.map((d) => (
              <button key={d.toISOString()} type="button" onClick={() => { setCursor(d); setView("day"); }} className={`border-b border-s border-slate-100 py-2 text-center ${ymd(d) === today ? "bg-brand-soft" : ""}`}>
                <p className="text-xs font-semibold text-slate-500">{new Intl.DateTimeFormat(intl, { weekday: "short" }).format(d)}</p>
                <p className={`text-lg font-extrabold ${ymd(d) === today ? "text-brand" : "text-ink"}`}>{d.getDate()}</p>
              </button>
            ))}
            {days.some((d) => (byDay[ymd(d)] ?? []).some((i) => i.allDay)) && <>
              <div className="border-b border-slate-100 p-1 text-[10px] text-slate-400">{t("lw.sch.allDay")}</div>
              {days.map((d) => <div key={"ad" + d.toISOString()} className="space-y-1 border-b border-s border-slate-100 p-1">{(byDay[ymd(d)] ?? []).filter((i) => i.allDay).map((i) => chip(i))}</div>)}
            </>}
            {HOURS.map((h) => (
              <div key={h} className="contents">
                <div className="border-b border-slate-50 px-1 pt-1 text-end text-[11px] text-slate-400" dir="ltr">{pad(h)}:00</div>
                {days.map((d) => {
                  const k = ymd(d);
                  const list = (byDay[k] ?? []).filter((i) => !i.allDay && new Date(i.startsAt).getHours() === h);
                  return (
                    <div key={k + h} onDragOver={(e) => e.preventDefault()} onDrop={() => dropTo(d, h)} onClick={() => write && setAdding({ at: `${k}T${pad(h)}:00` })}
                      className={`min-h-[52px] cursor-pointer space-y-1 border-b border-s border-slate-50 p-1 transition hover:bg-brand-soft/40 ${k === today ? "bg-brand-soft/20" : ""}`}>
                      {list.map((i) => chip(i))}
                    </div>
                  );
                })}
              </div>
            ))}
          </div>
        </div>
      )}
      {(data ?? []).some((i) => { const hr = new Date(i.startsAt).getHours(); return !i.allDay && (hr < 8 || hr > 22); }) && view !== "month" && <p className="mt-2 text-xs text-slate-500">{t("lw.sch.outside")}</p>}
      {adding && <AddSheet at={adding.at} onClose={() => setAdding(null)} onSaved={async () => { setAdding(null); await reload(); }} />}
      {open && <ItemSheet item={open} onClose={() => setOpen(null)} onChanged={async () => { setOpen(null); await reload(); }} />}
    </>
  );
}

/** Add from the calendar: a note, a case step (on an existing case or a new one made here), or a session booked by phone. */
function AddSheet({ at, onClose, onSaved }: { at: string; onClose: () => void; onSaved: () => void }) {
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
    <div data-field="caseId" className="space-y-2 rounded-2xl bg-slate-50 p-3">
      <div className="flex items-center justify-between"><p className="text-sm font-bold text-slate-700">{t("lw.sch.forCase")}{optional && <span className="font-normal text-slate-400"> ({t("offer.optional")})</span>}</p>
        <div className="flex gap-1"><button type="button" onClick={() => setNewCase(false)} className={`chip ${!newCase ? "chip-on" : ""}`}>{t("lw.sch.existing")}</button><button type="button" onClick={() => { setNewCase(true); setPick(null); }} className={`chip ${newCase ? "chip-on" : ""}`}>+ {t("lw.case.new")}</button></div></div>
      {newCase ? <CaseFields d={cd} set={(x) => setCd((o) => ({ ...o, ...x }))} err={fe.err} p="newCase." /> : <CasePicker value={pick} onChange={(v) => { setPick(v); fe.clear("caseId"); }} />}
      {fe.err("caseId") && <p className="text-xs font-medium text-red-600">{fe.err("caseId")}</p>}
    </div>
  );
  return (
    <Modal open onClose={onClose} title={t("lw.sch.add")} wide>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
        <Tabs value={kind} onChange={(k) => { setKind(k); fe.clear(); }} tabs={[{ key: "STEP", label: "⚖️ " + t("lw.sch.kind.STEP") }, { key: "NOTE", label: "📝 " + t("lw.sch.kind.NOTE") }, { key: "BOOKING", label: "📞 " + t("lw.sch.kind.BOOKING") }]} />
        {kind === "STEP" && <><StepFields f={st} set={(x) => setSt((o) => ({ ...o, ...x }))} err={fe.err} p="step." />{caseBlock(false)}</>}
        {kind === "NOTE" && (
          <>
            <Field name="title" error={fe.err("title")} label={t("lw.note.title")}><Input value={note.title} onChange={(e) => setNote({ ...note, title: e.target.value })} placeholder={t("lw.note.ph")} /></Field>
            <Field label={t("lw.note.body")}><Textarea rows={3} value={note.body} onChange={(e) => setNote({ ...note, body: e.target.value })} /></Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field name="startsAt" error={fe.err("startsAt")} label={t("lw.step.when")}><Input type={note.allDay ? "date" : "datetime-local"} value={note.allDay ? note.startsAt.slice(0, 10) : note.startsAt} onChange={(e) => setNote({ ...note, startsAt: note.allDay ? `${e.target.value}T09:00` : e.target.value })} /></Field>
              <div className="space-y-2 pt-6"><label className="flex items-center gap-2 text-sm font-semibold"><input type="checkbox" className="h-5 w-5 accent-[var(--brand)]" checked={note.allDay} onChange={(e) => setNote({ ...note, allDay: e.target.checked })} />{t("lw.sch.allDay")}</label>
                <div className="flex gap-1.5">{NOTE_COLORS.map((c) => <button key={c || "x"} type="button" onClick={() => setNote({ ...note, color: c })} className={`h-7 w-7 rounded-full ring-2 ring-offset-2 ${note.color === c ? "ring-slate-700" : "ring-transparent"}`} style={{ background: c || "#eab308" }} aria-label={c || "default"} />)}</div></div>
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
        {fe.any ? <p className="rounded-xl bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{t("form.fixMarked")}</p> : <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}

function ItemSheet({ item, onClose, onChanged }: { item: CalItem; onClose: () => void; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const { canWrite } = useMe();
  const del = useAction(async () => { await api(`lawyer/schedule/notes/${item.id}`, { method: "DELETE" }); onChanged(); });
  const c = calColor(item);
  return (
    <Modal open onClose={onClose} title={t(`lw.cal.${item.kind === "STEP" ? item.stepKind : item.kind}`)}>
      <div className="space-y-3">
        <div className="rounded-2xl p-4" style={{ background: c.bg, color: c.fg }}>
          <p className="text-sm font-bold">{new Date(item.startsAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "long", day: "numeric", month: "long", hour: item.allDay ? undefined : "numeric", minute: item.allDay ? undefined : "2-digit" })}</p>
          <p className="mt-1 text-lg font-extrabold">{item.title}</p>
          {item.location && <p className="text-sm">📍 {item.location}</p>}
        </div>
        {item.body && <p className="whitespace-pre-line text-sm text-slate-700">{item.body}</p>}
        {item.phone && <p className="flex gap-2"><a href={`tel:${item.phone}`} className="chip">📞 <span dir="ltr">{item.phone}</span></a><a href={`https://wa.me/${item.phone.replace(/[^0-9]/g, "")}`} target="_blank" rel="noopener noreferrer" className="chip">WhatsApp</a></p>}
        {item.caseId && <Link href={`/dashboard/cases/${item.caseId}`} className="block rounded-xl border border-slate-200 p-3 text-sm font-semibold text-brand hover:bg-slate-50">💼 #{item.caseNumber} {item.caseTitle ?? ""} →</Link>}
        {item.kind === "BOOKING" && <Link href="/dashboard/bookings" className="block text-sm font-semibold text-brand">{t("lw.sch.manageBooking")} →</Link>}
        <ErrorText error={del.error} />
        {item.kind === "NOTE" && canWrite("schedule") && <Button variant="danger" size="sm" loading={del.loading} onClick={() => del.run()}>{t("offer.delete")}</Button>}
        <p className="text-xs text-slate-400">{t("lw.sch.dragHint")}</p>
      </div>
    </Modal>
  );
}
