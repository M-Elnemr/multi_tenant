"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { statusTone, type Booking } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Menu, Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Chips, SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { ContactButtons, DateTile, DotsButton, Eyebrow, ListSkeleton, Rise, useIntl } from "@/components/lawyer/kit";
import { Badge, Button, Empty, ErrorText, Field, Input, KpiStrip, PageHeader, Pager, Textarea, cx } from "@/components/ui";

type Res = Page<Booking> & { counts: { pending: number; approved: number } };
const pad = (n: number) => String(n).padStart(2, "0");
const toLocal = (iso: string) => { const d = new Date(iso); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };

export default function Bookings() {
  const { t, locale, currency } = useI18n();
  const intl = useIntl();
  const toast = useToast();
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
  const decide = useAction(async (b: Booking, s: string) => { await api(`lawyer/bookings/${b.id}`, { method: "PUT", body: { status: s } }); await reload(); toast(t(`lw.bkToast.${s}`)); });
  const [now] = useState(() => Date.now());
  const filtered = status.length > 0 || q.trim() !== "" || !!range.from || !!range.to;
  return (
    <div className="space-y-4">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.office")}</Eyebrow>} title={t("lw.nav.bookings")} subtitle={t("lw.bk.subtitle")} />
      <KpiStrip items={[
        { label: t("lw.bks.PENDING"), value: data?.counts.pending ?? 0, icon: "clock", tone: (data?.counts.pending ?? 0) > 0 ? "amber" : "slate" },
        { label: t("lw.bks.APPROVED"), value: data?.counts.approved ?? 0, icon: "check", tone: "green" },
      ]} />
      <div className="flex flex-wrap items-center gap-2.5 rounded-[18px] bg-surface p-2.5 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("lw.bk.search")} />
        <DateRangePicker future value={range} onChange={(r) => { setRange(r); setPage(1); }} />
        <Chips options={[{ value: "up", label: t("lw.bk.upcomingOnly") }]} value={upcoming ? ["up"] : []} onChange={(v) => { setUpcoming(v.length > 0); setPage(1); }} />
      </div>
      <Chips allLabel={t("orders.allStatuses")} multi options={[
        { value: "PENDING", label: t("lw.bks.PENDING"), count: data?.counts.pending, dot: "#f59e0b" }, { value: "APPROVED", label: t("lw.bks.APPROVED"), count: data?.counts.approved, dot: "#10b981" },
        { value: "DONE", label: t("lw.bks.DONE") }, { value: "DENIED", label: t("lw.bks.DENIED") }, { value: "CANCELLED", label: t("lw.bks.CANCELLED") }, { value: "NO_SHOW", label: t("lw.bks.NO_SHOW") },
      ]} value={status} onChange={(v) => { setStatus(v); setPage(1); }} />
      <ErrorText error={error ?? decide.error} />
      {loading && !data ? <ListSkeleton /> : data && data.data.length === 0 ? (
        <Empty icon="calendar" title={t("lw.bk.none")} action={filtered ? <Button variant="secondary" onClick={() => { setStatus([]); setQ(""); setRange({}); setPage(1); }}>{t("filter.clearAll")}</Button> : undefined}>{t("lw.bk.noneHint")}</Empty>
      ) : (
        <Rise className="grid gap-3 lg:grid-cols-2">
          {data?.data.map((b) => {
            const d = new Date(b.startsAt);
            const pend = b.status === "PENDING";
            const canClose = write && b.status === "APPROVED" && d.getTime() < now;
            const items = [
              ...(write && (pend || b.status === "APPROVED") ? [{ label: t("lw.bk.move"), icon: "calendar", onSelect: () => setMoveB(b) }] : []),
              ...(canClose ? [{ label: t("lw.bks.DONE"), icon: "check", onSelect: () => void decide.run(b, "DONE") }, { label: t("lw.bks.NO_SHOW"), icon: "xcircle", onSelect: () => void decide.run(b, "NO_SHOW"), danger: true }] : []),
            ];
            return (
              <article key={b.id} className={cx("relative flex gap-3.5 overflow-hidden rounded-[18px] bg-surface p-4 shadow-card sm:gap-4", pend && "ring-1 ring-amber-500/30")}>
                {pend && <span className="absolute inset-y-0 start-0 w-1 bg-amber-400" aria-hidden />}
                <DateTile date={d} tone={pend ? "amber" : b.status === "APPROVED" ? "emerald" : "slate"} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate text-[15px] font-semibold text-ink">{b.guestName}</p>
                      <p className="mt-0.5 truncate text-[13px] text-muted tnum">{new Intl.DateTimeFormat(intl, { hour: "numeric", minute: "2-digit" }).format(d)} · {b.serviceName ?? t("lw.bk.session")}{b.priceMinor ? ` · ${money(b.priceMinor, currency, locale)}` : ""}</p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1"><Badge tone={statusTone[b.status]}>{t(`lw.bks.${b.status}`)}</Badge>
                      {items.length > 0 && <Menu items={items} label={t("common.more")} trigger={({ toggle, open }) => <DotsButton onClick={toggle} open={open} label={t("common.more")} />} />}</div>
                  </div>
                  {b.topic && <p className="mt-2.5 line-clamp-3 rounded-[10px] bg-surface-2 px-3 py-2 text-[13px] leading-relaxed text-ink-2">“{b.topic}”</p>}
                  {b.decisionNote && <p className="mt-1.5 text-xs text-muted">{b.decisionNote}</p>}
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <ContactButtons phone={b.guestPhone} waText={t("lw.bk.waText", { name: b.guestName, code: b.code })} />
                    <span dir="ltr" className="text-xs text-muted tnum">{b.guestPhone}</span>
                    {write && pend && (
                      <span className="ms-auto flex gap-1.5">
                        <Button size="sm" variant="secondary" onClick={() => setDeny(b)}>{t("lw.bk.deny")}</Button>
                        <Button size="sm" loading={decide.loading} onClick={() => void decide.run(b, "APPROVED")}><Icon name="check" className="h-3.5 w-3.5" />{t("lw.bk.approve")}</Button>
                      </span>
                    )}
                  </div>
                </div>
              </article>
            );
          })}
        </Rise>
      )}
      <Pager meta={data?.meta} onPage={setPage} />
      <DenySheet b={deny} onClose={() => setDeny(null)} onDone={async () => { setDeny(null); toast(t("lw.bkToast.DENIED")); await reload(); }} />
      <MoveSheet b={moveB} onClose={() => setMoveB(null)} onDone={async () => { setMoveB(null); toast(t("lw.bkToast.moved")); await reload(); }} />
    </div>
  );
}

function DenySheet({ b, onClose, onDone }: { b: Booking | null; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  return (
    <Sheet open={!!b} onClose={onClose} title={b ? t("lw.bk.denyTitle", { name: b.guestName }) : ""}>
      {b && <DenyForm key={b.id} b={b} onDone={onDone} />}
    </Sheet>
  );
}

function DenyForm({ b, onDone }: { b: Booking; onDone: () => void }) {
  const { t } = useI18n();
  const fe = useFieldErrors();
  const [note, setNote] = useState("");
  const save = useAction(async () => {
    if (!fe.check({ note: !note.trim() && t("srv.Tell the client why (it is shown on their booking page)") })) return;
    try { await api(`lawyer/bookings/${b.id}`, { method: "PUT", body: { status: "DENIED", note } }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onDone();
  });
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-1.5">{["lw.bk.r1", "lw.bk.r2", "lw.bk.r3"].map((r) => <button key={r} type="button" className="chip" onClick={() => setNote(t(r))}>{t(r)}</button>)}</div>
      <Field name="note" error={fe.err("note")} label={t("lw.bk.reason")} hint={t("lw.bk.reasonHint")}><Textarea rows={4} value={note} onChange={(e) => { setNote(e.target.value); fe.clear("note"); }} /></Field>
      {!fe.any && <ErrorText error={save.error} />}
      <Button variant="danger" className="w-full" loading={save.loading} onClick={() => save.run()}>{t("lw.bk.deny")}</Button>
    </div>
  );
}

function MoveSheet({ b, onClose, onDone }: { b: Booking | null; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  return (
    <Sheet open={!!b} onClose={onClose} title={t("lw.bk.moveTitle")}>
      {b && <MoveForm key={b.id} b={b} onDone={onDone} />}
    </Sheet>
  );
}

function MoveForm({ b, onDone }: { b: Booking; onDone: () => void }) {
  const { t } = useI18n();
  const intl = useIntl();
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
    <div className="space-y-4">
      <Field label={t("lw.bk.day")}><Input type="date" value={day} onChange={(e) => { setDay(e.target.value); setTime(null); }} /></Field>
      <div data-field="startsAt" className="flex flex-wrap gap-1.5">
        {!slots.data && slots.loading && [0, 1, 2, 3].map((i) => <span key={i} className="skeleton h-9 w-20 !rounded-full" />)}
        {slots.data?.map((s) => <button key={s} type="button" onClick={() => setTime(s)} className={cx("chip tnum", time === s && "chip-on")} dir="ltr">{new Date(s).toLocaleTimeString(intl, { hour: "numeric", minute: "2-digit" })}</button>)}
        {slots.data && slots.data.length === 0 && <p className="text-sm text-muted">{t("lw.bk.noSlots")}</p>}
      </div>
      {fe.err("startsAt") && <p className="text-xs font-medium text-red-600">{fe.err("startsAt")}</p>}
      {!fe.any && <ErrorText error={save.error} />}
      <Button className="w-full" loading={save.loading} onClick={() => save.run()}>{t("lw.bk.moveSave")}</Button>
    </div>
  );
}
