"use client";

import { useRouter } from "next/navigation";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { dayKey, money, num, timeOnly } from "@/lib/format";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { VisitTypeBadge, VisitTypePicker, type VisitType } from "@/components/visit-type";
import { ConfirmDialog, Menu, Sheet, type MenuItem } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Chips, SearchBox } from "@/components/dashboard/filters";
import { Avatar, Button, Empty, ErrorText, Field, Input, KpiStrip, Loading, PageHeader, Select } from "@/components/ui";
import { CalmStatus, MoreTrigger, Pill, calm } from "@/components/clinic/dash";

type Appt = { id: string; patientId: string; patientName: string; patientCode: string; doctorId: string; doctorName: string; branchId: string; serviceName: string; visitType?: string; startAt: string; status: string; paymentStatus: string; paymentMethod: string; priceMinor?: number; currency: string; queueNumber?: number; patientNote?: string };
type Doctor = { id: string; displayName: string };
type Branch = { id: string; name: string };
type Service = { id: string; name: string; visitType?: string | null; isActive?: boolean };
type PatientRow = { id: string; firstName: string; lastName: string; patientCode: string; phone?: string };

/** Status groups for the filter chips (client side, over the day already loaded). */
const GROUPS: Record<string, string[]> = {
  confirm: ["PENDING_CONFIRMATION", "REQUESTED"],
  upcoming: ["CONFIRMED"],
  inClinic: ["CHECKED_IN", "IN_PROGRESS"],
  done: ["COMPLETED", "DONE"],
  missed: ["CANCELLED", "NO_SHOW", "REJECTED"],
};
/** Actions that change a booking for good: asked once before they run. */
const CONFIRM_FIRST = new Set(["reject", "no-show", "cancel"]);

export default function Appointments() {
  const { t, locale, timezone, currency } = useI18n();
  const router = useRouter();
  const reduce = useReducedMotion();
  const toast = useToast();
  const { can } = useMe();
  const [date, setDate] = useState(() => dayKey(new Date(), timezone));
  const [doctorId, setDoctorId] = useState("");
  const list = useApi<Page<Appt>>(`clinic/appointments?from=${date}&to=${date}&doctorId=${doctorId}&pageSize=100`);
  const doctors = useApi<Doctor[]>("clinic/doctors");
  const branches = useApi<Branch[]>("clinic/branches");
  const services = useApi<Service[]>("clinic/services");
  const act = useAction(async (id: string, action: string) => { await api(`clinic/appointments/${id}/${action}`, { body: {} }); await list.reload(); return true; });
  const startVisit = useAction(async (a: Appt) => {
    if (a.status === "CHECKED_IN") await api(`clinic/appointments/${a.id}/start`, { body: {} });
    const e = await api<{ id: string }>("clinic/encounters", { body: { patientId: a.patientId, appointmentId: a.id } });
    router.push(`/dashboard/encounters/${e.id}`);
  });

  // client side view filters
  const [q, setQ] = useState("");
  const [group, setGroup] = useState<string[]>([]);
  const [ask, setAsk] = useState<{ a: Appt; action: string; label: string } | null>(null);

  // new booking by reception
  const [open, setOpen] = useState(false);
  const [pq, setPq] = useState("");
  const patients = useApi<Page<PatientRow>>(open ? `clinic/patients?q=${encodeURIComponent(pq)}&pageSize=8` : null);
  const [nb, setNb] = useState({ patientId: "", doctorId: "", branchId: "", serviceId: "", date, slot: "" });
  const sel = { doctorId: nb.doctorId || doctors.data?.[0]?.id || "", branchId: nb.branchId || branches.data?.[0]?.id || "", serviceId: nb.serviceId || services.data?.[0]?.id || "" };
  const slotsApi = useApi<string[]>(open && sel.doctorId && sel.branchId && sel.serviceId ? `clinic/appointments/slots?doctorId=${sel.doctorId}&branchId=${sel.branchId}&serviceId=${sel.serviceId}&date=${nb.date}` : null);
  const slots = slotsApi.data ?? [];
  const book = useAction(async () => { await api("clinic/appointments", { body: { patientId: nb.patientId, doctorId: sel.doctorId, branchId: sel.branchId, serviceId: sel.serviceId, visitType: services.data?.find((x) => x.id === sel.serviceId)?.visitType ?? undefined, startAt: nb.slot, source: "RECEPTION" } }); setOpen(false); setNb({ ...nb, slot: "", patientId: "" }); await list.reload(); return true; });

  const shift = (d: number) => setDate(dayKey(new Date(new Date(date + "T12:00:00Z").getTime() + d * 86400000), "UTC"));
  const today = dayKey(new Date(), timezone);
  const longDay = new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" }).format(new Date(date + "T12:00:00Z"));

  const all = useMemo(() => [...(list.data?.data ?? [])].sort((a, b) => a.startAt.localeCompare(b.startAt)), [list.data]);
  const shown = all.filter((a) => (group.length === 0 || GROUPS[group[0]].includes(a.status)) && (!q.trim() || `${a.patientName} ${a.patientCode}`.toLowerCase().includes(q.trim().toLowerCase())));
  const count = (g: string) => all.filter((a) => GROUPS[g].includes(a.status)).length;
  // group the day by hour, like a paper schedule
  const hours = shown.reduce<{ hour: string; rows: Appt[] }[]>((acc, a) => {
    const h = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", hourCycle: "h23", timeZone: timezone }).format(new Date(a.startAt));
    const last = acc[acc.length - 1];
    if (last && last.hour === h) last.rows.push(a); else acc.push({ hour: h, rows: [a] });
    return acc;
  }, []);

  const run = async (a: Appt, action: string, label: string) => {
    if (CONFIRM_FIRST.has(action) && ask?.a.id !== a.id) { setAsk({ a, action, label }); return; }
    setAsk(null);
    if (await act.run(a.id, action)) toast(t("cld.apptUpdated", { name: a.patientName, action: label }));
  };

  /** The main next step as a button; everything else in the row menu. */
  const actionsFor = (a: Appt) => {
    const primary: { label: string; onClick: () => void; loading?: boolean }[] = [];
    const more: MenuItem[] = [];
    const item = (label: string, action: string, icon: string, danger?: boolean): MenuItem => ({ label: t(label), icon, danger, onSelect: () => void run(a, action, t(label)) });
    if (a.status === "PENDING_CONFIRMATION") { primary.push({ label: t("appt.confirm"), onClick: () => void run(a, "confirm", t("appt.confirm")) }); more.push(item("appt.reject", "reject", "xcircle", true)); }
    if (a.status === "CONFIRMED") { primary.push({ label: t("appt.checkIn"), onClick: () => void run(a, "check-in", t("appt.checkIn")) }); more.push(item("appt.noShow", "no-show", "clock", true), item("orders.cancel", "cancel", "xcircle", true)); }
    if (["CHECKED_IN", "IN_PROGRESS"].includes(a.status) && can("medical_note.create")) primary.push({ label: t("appt.startVisit"), onClick: () => void startVisit.run(a), loading: startVisit.loading });
    if (a.status === "IN_PROGRESS") (primary.length ? more.unshift(item("appt.complete", "complete", "check")) : primary.push({ label: t("appt.complete"), onClick: () => void run(a, "complete", t("appt.complete")) }));
    if (a.paymentMethod === "CASH_AT_CLINIC" && a.paymentStatus !== "PAID" && ["CONFIRMED", "CHECKED_IN", "IN_PROGRESS", "COMPLETED"].includes(a.status)) more.unshift(item("appt.markPaid", "mark-paid", "wallet"));
    return { primary, more };
  };

  const kpis = [
    { label: t("cld.booked"), value: all.filter((a) => !GROUPS.missed.includes(a.status)).length, icon: "calendar" as const, tone: "slate" as const },
    { label: t("cld.toConfirm"), value: count("confirm"), icon: "bell" as const, tone: "amber" as const },
    { label: t("cld.inClinic"), value: count("inClinic"), icon: "stethoscope" as const, tone: "brand" as const },
    { label: t("cld.done"), value: count("done"), icon: "check" as const, tone: "green" as const },
  ];

  return (
    <>
      <PageHeader eyebrow={t("rail.today")} title={t("nav.appointments")} subtitle={t("cld.apptSubtitle")} actions={<Button onClick={() => { setNb({ ...nb, date }); setOpen(true); }}><Icon name="plus" className="h-4 w-4" />{t("appt.new")}</Button>} />

      <div className="space-y-5">
        {/* Day picker + doctor */}
        <div className="flex flex-col gap-3 rounded-[18px] bg-surface p-3 shadow-card sm:flex-row sm:items-center sm:justify-between sm:p-4">
          <div className="flex min-w-0 items-center gap-2">
            <button type="button" onClick={() => shift(-1)} aria-label={t("common.prev")} className="grid h-10 w-10 shrink-0 place-items-center rounded-[10px] text-muted ring-1 ring-line-strong transition hover:bg-surface-2 hover:text-ink"><Icon name="chevron" className="h-4 w-4 rotate-180 flip-rtl" /></button>
            <div className="min-w-0 flex-1 px-1 text-center sm:min-w-[13rem]">
              <p className="truncate font-display text-[16px] font-semibold text-ink">{longDay}</p>
              <p className="text-[11.5px] text-muted">{date === today ? t("rail.today") : <button type="button" onClick={() => setDate(today)} className="font-semibold text-[var(--rail-accent-ink)] hover:underline">{t("cld.backToToday")}</button>}</p>
            </div>
            <button type="button" onClick={() => shift(1)} aria-label={t("common.next")} className="grid h-10 w-10 shrink-0 place-items-center rounded-[10px] text-muted ring-1 ring-line-strong transition hover:bg-surface-2 hover:text-ink"><Icon name="chevron" className="h-4 w-4 flip-rtl" /></button>
          </div>
          <div className="grid grid-cols-2 gap-2 sm:flex sm:items-center">
            <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} dir="ltr" className="sm:w-44" aria-label={t("book.day")} />
            <Select value={doctorId} onChange={(e) => setDoctorId(e.target.value)} className="sm:w-52" aria-label={t("book.doctor")}><option value="">{t("appt.allDoctors")}</option>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select>
          </div>
        </div>

        {list.data && all.length > 0 && <KpiStrip className="!rounded-[18px]" items={kpis} />}

        {/* Search + status chips */}
        {all.length > 0 && (
          <div className="flex flex-col gap-2.5 lg:flex-row lg:items-center">
            <div className="lg:w-72"><SearchBox value={q} onChange={setQ} placeholder={t("patients.searchHint")} /></div>
            <div className="min-w-0 flex-1">
              <Chips allLabel={t("cld.all")} value={group} onChange={setGroup} options={(["confirm", "upcoming", "inClinic", "done", "missed"] as const).map((g) => ({ value: g, label: t(`cld.grp.${g}`), count: count(g), dot: calm(GROUPS[g][0]).dot }))} />
            </div>
          </div>
        )}

        <ErrorText error={list.error ?? act.error ?? startVisit.error} />

        {list.loading && !list.data ? <Loading variant="list" /> : all.length === 0 ? (
          <Empty icon="calendar" title={t("clinic.noToday")} action={<Button variant="secondary" onClick={() => { setNb({ ...nb, date }); setOpen(true); }}><Icon name="plus" className="h-4 w-4" />{t("appt.new")}</Button>}>{t("cld.noApptsHint")}</Empty>
        ) : shown.length === 0 ? (
          <Empty icon="search" compact title={t("cld.noMatch")} action={<Button variant="ghost" onClick={() => { setQ(""); setGroup([]); }}>{t("filter.clearAll")}</Button>} />
        ) : (
          <div className="overflow-hidden rounded-[18px] bg-surface shadow-card">
            {hours.map((h) => (
              <div key={h.hour} className="grid grid-cols-[3.25rem_1fr] border-b border-line last:border-0 sm:grid-cols-[4.5rem_1fr]">
                <div className="border-e border-line bg-surface-2 px-2 py-3 text-center sm:px-3">
                  <span className="font-display text-[15px] font-semibold text-ink-2 tnum" dir="ltr">{h.hour}:00</span>
                </div>
                <ul className="divide-y divide-line">
                  <AnimatePresence initial={false}>
                    {h.rows.map((a) => {
                      const { primary, more } = actionsFor(a);
                      return (
                        <motion.li key={a.id} layout={reduce ? false : "position"} initial={reduce ? false : { opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                          className="relative flex flex-col gap-3 py-3.5 pe-3 ps-4 transition hover:bg-surface-2/60 sm:flex-row sm:items-center sm:pe-4">
                          <span aria-hidden className="absolute inset-y-3 start-0 w-[3px] rounded-full" style={{ background: calm(a.status).dot }} />
                          <div className="flex min-w-0 flex-1 items-start gap-3">
                            <Avatar name={a.patientName} className="mt-0.5 h-10 w-10 text-[13px]" />
                            <div className="min-w-0 flex-1">
                              <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
                                <span className="font-semibold text-ink">{a.patientName}</span>
                                <span className="font-mono text-[11px] text-faint">{a.patientCode}</span>
                                <VisitTypeBadge type={a.visitType} />
                              </p>
                              <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 text-[13px] text-muted">
                                <span dir="ltr" className="font-semibold text-ink-2 tnum">{timeOnly(a.startAt, locale, timezone)}</span>
                                <span aria-hidden>·</span><span>{a.serviceName}</span>
                                <span aria-hidden>·</span><span>{a.doctorName}</span>
                                {a.priceMinor ? <><span aria-hidden>·</span><span className="tnum">{money(a.priceMinor, a.currency || currency, locale)}</span></> : null}
                              </p>
                              {a.patientNote && <p className="mt-1.5 rounded-[10px] bg-surface-2 px-2.5 py-1.5 text-xs italic text-muted ring-1 ring-inset ring-line">“{a.patientNote}”</p>}
                            </div>
                          </div>
                          <div className="flex flex-wrap items-center gap-2 ps-[3.25rem] sm:ps-0">
                            {a.queueNumber ? <Pill>#{num(a.queueNumber, locale)}</Pill> : null}
                            <CalmStatus status={a.status} />
                            {a.paymentStatus === "PAID" && <CalmStatus status="PAID" />}
                            {primary.map((p) => <Button key={p.label} size="sm" loading={p.loading || (act.loading && ask === null && false)} onClick={p.onClick}>{p.label}</Button>)}
                            {more.length > 0 && <Menu label={t("cld.moreActions")} items={more} trigger={({ toggle }) => <MoreTrigger toggle={toggle} label={t("cld.moreActions")} />} />}
                          </div>
                        </motion.li>
                      );
                    })}
                  </AnimatePresence>
                </ul>
              </div>
            ))}
          </div>
        )}
      </div>

      <ConfirmDialog open={!!ask} onClose={() => setAsk(null)} danger loading={act.loading}
        title={ask ? t("cld.confirmAction", { action: ask.label }) : ""}
        body={ask ? t("cld.confirmActionBody", { name: ask.a.patientName, time: timeOnly(ask.a.startAt, locale, timezone) }) : undefined}
        confirmLabel={ask?.label ?? ""} cancelLabel={t("common.cancel")}
        onConfirm={() => { if (ask) void run(ask.a, ask.action, ask.label); }} />

      <Sheet open={open} onClose={() => setOpen(false)} title={t("appt.new")} width="34rem"
        footer={<>
          <ErrorText error={book.error} />
          <Button className="mt-2 w-full" loading={book.loading} disabled={!nb.patientId || !nb.slot} onClick={async () => { if (await book.run()) toast(t("cld.booked.ok")); }}>{t("book.confirm")}</Button>
        </>}>
        <div className="space-y-5">
          <Field label={t("clinic.patient")}>
            <div className="space-y-2">
              <SearchBox value={pq} onChange={setPq} placeholder={t("patients.searchHint")} />
              <div className="max-h-48 overflow-y-auto rounded-[12px] ring-1 ring-line">
                {patients.loading && !patients.data ? <p className="p-3 text-sm text-muted">{t("common.loading")}</p> : patients.data?.data.length === 0 ? <p className="p-3 text-sm text-muted">{t("cld.noMatch")}</p> : patients.data?.data.map((p) => {
                  const on = nb.patientId === p.id;
                  return (
                    <button type="button" key={p.id} onClick={() => setNb({ ...nb, patientId: p.id })} aria-pressed={on} className={`flex w-full items-center gap-3 border-b border-line px-3 py-2.5 text-start text-sm transition last:border-0 ${on ? "bg-[color-mix(in_srgb,var(--rail-accent)_10%,transparent)]" : "hover:bg-surface-2"}`}>
                      <Avatar name={`${p.firstName} ${p.lastName}`} className="h-8 w-8 text-xs" />
                      <span className="min-w-0 flex-1 truncate font-medium text-ink">{p.firstName} {p.lastName}</span>
                      <span className="font-mono text-[11px] text-faint">{p.patientCode}</span>
                      {on && <Icon name="check" className="h-4 w-4 text-[var(--rail-accent-ink)]" />}
                    </button>
                  );
                })}
              </div>
            </div>
          </Field>
          <div className="grid gap-3 sm:grid-cols-3">
            <Field label={t("book.doctor")}><Select value={sel.doctorId} onChange={(e) => setNb({ ...nb, doctorId: e.target.value, slot: "" })}>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select></Field>
            <Field label={t("book.branch")}><Select value={sel.branchId} onChange={(e) => setNb({ ...nb, branchId: e.target.value, slot: "" })}>{branches.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</Select></Field>
            <Field label={t("book.service")}><Select value={sel.serviceId} onChange={(e) => setNb({ ...nb, serviceId: e.target.value, slot: "" })}>{services.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</Select></Field>
          </div>
          <VisitTypePicker value={(services.data?.find((x) => x.id === sel.serviceId)?.visitType as VisitType | undefined) ?? "CONSULTATION"} onChange={(v) => { const m = services.data?.find((x) => x.visitType === v && x.isActive !== false); if (m) setNb({ ...nb, serviceId: m.id, slot: "" }); }} />
          <Field label={t("book.day")}><Input type="date" value={nb.date} onChange={(e) => setNb({ ...nb, date: e.target.value, slot: "" })} dir="ltr" /></Field>
          <div>
            <p className="mb-2 text-[13px] font-semibold text-ink-2">{t("cld.freeTimes")}</p>
            {slotsApi.loading && !slotsApi.data ? (
              <div className="grid grid-cols-4 gap-2 sm:grid-cols-5">{Array.from({ length: 10 }).map((_, i) => <span key={i} className="skeleton h-9 rounded-[10px]" />)}</div>
            ) : slots.length === 0 ? <p className="rounded-[12px] bg-surface-2 p-3 text-sm text-muted ring-1 ring-inset ring-line">{t("book.noSlots")}</p> : (
              <div className="grid grid-cols-4 gap-2 sm:grid-cols-5">
                {slots.map((s) => <button type="button" key={s} onClick={() => setNb({ ...nb, slot: s })} aria-pressed={nb.slot === s} className={`h-9 rounded-[10px] text-[13px] font-semibold tnum transition ${nb.slot === s ? "bg-ink text-surface" : "text-ink-2 ring-1 ring-line-strong hover:bg-surface-2"}`} dir="ltr">{timeOnly(s, locale, timezone)}</button>)}
              </div>
            )}
          </div>
        </div>
      </Sheet>
    </>
  );
}
