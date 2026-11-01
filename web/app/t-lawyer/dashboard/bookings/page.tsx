"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { statusTone, type Booking } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Chips, SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { Badge, Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Pager, Textarea } from "@/components/ui";

type Res = Page<Booking> & { counts: { pending: number; approved: number } };
const pad = (n: number) => String(n).padStart(2, "0");
const toLocal = (iso: string) => { const d = new Date(iso); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };

export default function Bookings() {
  const { t, locale, currency } = useI18n();
  const { canWrite } = useMe();
  const write = canWrite("bookings");
  const [status, setStatus] = useState<string[]>(["PENDING"]);
  const [range, setRange] = useState<DateRange>({});
  const [q, setQ] = useState("");
  const [upcoming, setUpcoming] = useState(true);
  const [page, setPage] = useState(1);
  const [deny, setDeny] = useState<Booking | null>(null);
  const [moveB, setMoveB] = useState<Booking | null>(null);
  const qs = rangeQuery(new URLSearchParams({ page: String(page), upcoming: String(upcoming) }), range);
  if (status.length) qs.set("status", status.join(","));
  if (q.trim()) qs.set("q", q.trim());
  const { data, loading, error, reload } = useApi<Res>(`lawyer/bookings?${qs}`);
  const decide = useAction(async (b: Booking, s: string) => { await api(`lawyer/bookings/${b.id}`, { method: "PUT", body: { status: s } }); await reload(); });
  const [now] = useState(() => Date.now());
  const intl = locale === "ar" ? "ar-EG" : "en-GB";
  return (
    <>
      <PageHeader title={t("lw.nav.bookings")} subtitle={t("lw.bk.subtitle")} />
      <div className="mb-3 flex flex-wrap items-center gap-2 rounded-2xl bg-white p-3 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("lw.bk.search")} />
        <DateRangePicker future value={range} onChange={(r) => { setRange(r); setPage(1); }} />
        <Chips options={[{ value: "up", label: t("lw.bk.upcomingOnly") }]} value={upcoming ? ["up"] : []} onChange={(v) => { setUpcoming(v.length > 0); setPage(1); }} />
      </div>
      <div className="mb-4"><Chips allLabel={t("orders.allStatuses")} multi options={[
        { value: "PENDING", label: t("lw.bks.PENDING"), count: data?.counts.pending, dot: "#f59e0b" }, { value: "APPROVED", label: t("lw.bks.APPROVED"), count: data?.counts.approved, dot: "#14b8a6" },
        { value: "DONE", label: t("lw.bks.DONE") }, { value: "DENIED", label: t("lw.bks.DENIED") }, { value: "CANCELLED", label: t("lw.bks.CANCELLED") }, { value: "NO_SHOW", label: t("lw.bks.NO_SHOW") },
      ]} value={status} onChange={(v) => { setStatus(v); setPage(1); }} /></div>
      <ErrorText error={error ?? decide.error} />
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? (
        <div className="rounded-3xl border border-dashed border-slate-300 bg-white/70 p-10 text-center"><p className="text-5xl">📅</p><p className="mt-3 font-bold text-ink">{t("lw.bk.none")}</p><p className="mt-1 text-sm text-slate-500">{t("lw.bk.noneHint")}</p></div>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {data?.data.map((b) => {
            const d = new Date(b.startsAt);
            return (
              <article key={b.id} className={`flex gap-4 rounded-2xl bg-white p-4 shadow-card ring-1 ${b.status === "PENDING" ? "ring-amber-200" : "ring-slate-100"}`}>
                <div className={`grid h-16 w-16 shrink-0 place-items-center rounded-2xl text-center ${b.status === "PENDING" ? "bg-amber-50 text-amber-800" : "bg-teal-50 text-teal-800"}`}>
                  <div><p className="text-[11px] font-bold">{new Intl.DateTimeFormat(intl, { weekday: "short" }).format(d)}</p><p className="text-xl font-extrabold leading-none">{d.getDate()}</p><p className="text-[11px] font-bold">{new Intl.DateTimeFormat(intl, { month: "short" }).format(d)}</p></div>
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0"><p className="truncate font-bold text-ink">{b.guestName}</p><p className="text-sm text-slate-500">{new Intl.DateTimeFormat(intl, { hour: "numeric", minute: "2-digit" }).format(d)} · {b.serviceName ?? t("lw.bk.session")}{b.priceMinor ? ` · ${money(b.priceMinor, currency, locale)}` : ""}</p></div>
                    <Badge tone={statusTone[b.status]}>{t(`lw.bks.${b.status}`)}</Badge>
                  </div>
                  {b.topic && <p className="mt-2 line-clamp-3 rounded-xl bg-slate-50 px-3 py-2 text-sm text-slate-700">“{b.topic}”</p>}
                  {b.decisionNote && <p className="mt-1 text-xs text-slate-500">{b.decisionNote}</p>}
                  <div className="mt-3 flex flex-wrap items-center gap-1.5">
                    <a href={`tel:${b.guestPhone}`} className="chip"><Icon name="phone" className="h-3.5 w-3.5" /><span dir="ltr">{b.guestPhone}</span></a>
                    <a href={`https://wa.me/${b.guestPhone.replace(/[^0-9]/g, "")}?text=${encodeURIComponent(t("lw.bk.waText", { name: b.guestName, code: b.code }))}`} target="_blank" rel="noopener noreferrer" className="chip">WhatsApp</a>
                    {write && b.status === "PENDING" && <>
                      <Button size="sm" loading={decide.loading} onClick={() => decide.run(b, "APPROVED")}><Icon name="check" className="h-3.5 w-3.5" />{t("lw.bk.approve")}</Button>
                      <Button size="sm" variant="secondary" onClick={() => setDeny(b)}>{t("lw.bk.deny")}</Button>
                    </>}
                    {write && (b.status === "PENDING" || b.status === "APPROVED") && <Button size="sm" variant="ghost" onClick={() => setMoveB(b)}>{t("lw.bk.move")}</Button>}
                    {write && b.status === "APPROVED" && d.getTime() < now && <><Button size="sm" variant="ghost" onClick={() => decide.run(b, "DONE")}>✓ {t("lw.bks.DONE")}</Button><Button size="sm" variant="ghost" onClick={() => decide.run(b, "NO_SHOW")}>{t("lw.bks.NO_SHOW")}</Button></>}
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      )}
      <Pager meta={data?.meta} onPage={setPage} />
      {deny && <DenyModal b={deny} onClose={() => setDeny(null)} onDone={async () => { setDeny(null); await reload(); }} />}
      {moveB && <MoveModal b={moveB} onClose={() => setMoveB(null)} onDone={async () => { setMoveB(null); await reload(); }} />}
    </>
  );
}

function DenyModal({ b, onClose, onDone }: { b: Booking; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const fe = useFieldErrors();
  const [note, setNote] = useState("");
  const save = useAction(async () => {
    if (!fe.check({ note: !note.trim() && t("srv.Tell the client why (it is shown on their booking page)") })) return;
    try { await api(`lawyer/bookings/${b.id}`, { method: "PUT", body: { status: "DENIED", note } }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onDone();
  });
  return (
    <Modal open onClose={onClose} title={t("lw.bk.denyTitle", { name: b.guestName })}>
      <div className="space-y-3">
        <div className="flex flex-wrap gap-1.5">{["lw.bk.r1", "lw.bk.r2", "lw.bk.r3"].map((r) => <button key={r} type="button" className="chip" onClick={() => setNote(t(r))}>{t(r)}</button>)}</div>
        <Field name="note" error={fe.err("note")} label={t("lw.bk.reason")} hint={t("lw.bk.reasonHint")}><Textarea rows={3} value={note} onChange={(e) => { setNote(e.target.value); fe.clear("note"); }} /></Field>
        {!fe.any && <ErrorText error={save.error} />}
        <Button variant="danger" className="w-full" loading={save.loading} onClick={() => save.run()}>{t("lw.bk.deny")}</Button>
      </div>
    </Modal>
  );
}

function MoveModal({ b, onClose, onDone }: { b: Booking; onClose: () => void; onDone: () => void }) {
  const { t, locale } = useI18n();
  const fe = useFieldErrors();
  const [day, setDay] = useState(toLocal(b.startsAt).slice(0, 10));
  const [time, setTime] = useState<string | null>(null);
  const slots = useApi<string[]>(`lawyer/slots?date=${day}`);
  const save = useAction(async () => {
    if (!fe.check({ startsAt: !time && t("srv.Choose a time") })) return;
    try { await api(`lawyer/bookings/${b.id}`, { method: "PUT", body: { status: "APPROVED", startsAt: time } }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onDone();
  });
  return (
    <Modal open onClose={onClose} title={t("lw.bk.moveTitle")}>
      <div className="space-y-3">
        <Field label={t("lw.bk.day")}><Input type="date" value={day} onChange={(e) => { setDay(e.target.value); setTime(null); }} /></Field>
        <div data-field="startsAt" className="flex flex-wrap gap-1.5">
          {slots.data?.map((s) => <button key={s} type="button" onClick={() => setTime(s)} className={`chip ${time === s ? "chip-on" : ""}`} dir="ltr">{new Date(s).toLocaleTimeString(locale === "ar" ? "ar-EG" : "en-GB", { hour: "numeric", minute: "2-digit" })}</button>)}
          {slots.data && slots.data.length === 0 && <p className="text-sm text-slate-500">{t("lw.bk.noSlots")}</p>}
        </div>
        {fe.err("startsAt") && <p className="text-xs font-medium text-red-600">{fe.err("startsAt")}</p>}
        {!fe.any && <ErrorText error={save.error} />}
        <Button className="w-full" loading={save.loading} onClick={() => save.run()}>{t("lw.bk.moveSave")}</Button>
      </div>
    </Modal>
  );
}
