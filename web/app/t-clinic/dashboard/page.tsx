"use client";

import Link from "next/link";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon, type IconName } from "@/components/icons";
import { AnimatedNumber, Empty, ErrorText, KpiStrip, Loading, Section, SectionLink, StatusBadge, Timeline } from "@/components/ui";
import { money, num, timeOnly } from "@/lib/format";

type Dash = {
  today: { id: string; startAt: string; status: string; queueNumber?: number; patientName: string; patientCode: string; doctorName: string; serviceName: string }[];
  pendingRequests: number; checkedIn: number; pendingLabReviews: number; noShowsThisMonth: number; completedThisMonth: number; revenueThisMonthMinor: number; newPatientsThisMonth: number; unpaidVisitsCount: number; unpaidVisitsMinor: number;
};
type Appt = { id: string; startAt: string; status: string };

// calm, muted status colours for the doctor's day
const TONE: Record<string, string> = { REQUESTED: "#d4a24c", PENDING_CONFIRMATION: "#d4a24c", CONFIRMED: "#5b8def", CHECKED_IN: "#8b6fd6", IN_PROGRESS: "var(--brand)", COMPLETED: "#3fa37a", DONE: "#3fa37a", CANCELLED: "#a3a8b1", NO_SHOW: "#d9776b" };
const dayKey = (d: Date, tz: string) => d.toLocaleDateString("en-CA", { timeZone: tz });

export default function ClinicOverview() {
  const { t, locale, timezone, currency } = useI18n();
  const { me, can } = useMe();
  const { data, loading, error } = useApi<Dash>("clinic/dashboard");
  const now = new Date();
  const from = dayKey(now, timezone), to = dayKey(new Date(now.getTime() + 6 * 86400000), timezone);
  const week = useApi<Page<Appt>>(can("appointment.read") || can("appointment.manage") ? `clinic/appointments?from=${from}&to=${to}&pageSize=500` : null);
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;

  const sorted = [...data.today].sort((a, b) => a.startAt.localeCompare(b.startAt));
  const live = sorted.find((a) => a.status === "IN_PROGRESS");
  const next = sorted.find((a) => ["CHECKED_IN", "CONFIRMED", "REQUESTED"].includes(a.status) && new Date(a.startAt).getTime() >= now.getTime() - 30 * 60000);
  const done = sorted.filter((a) => a.status === "COMPLETED" || a.status === "DONE").length;
  const progress = sorted.length ? done / sorted.length : 0;
  const quick: [string, string, IconName, string][] = [["/dashboard/queue", t("nav.queue"), "queue", "appointment.manage"], ["/dashboard/appointments", t("cl.book"), "plus", "appointment.manage"], ["/dashboard/patients", t("nav.patients"), "patients", "patient.read"], ["/?edit=1", t("site.customize"), "edit", "settings.manage"]];
  const noShowRate = data.completedThisMonth + data.noShowsThisMonth > 0 ? Math.round((data.noShowsThisMonth / (data.completedThisMonth + data.noShowsThisMonth)) * 100) : 0;

  // the week ahead: real bookings per day
  const days = Array.from({ length: 7 }, (_, i) => new Date(now.getTime() + i * 86400000));
  const perDay = days.map((d) => (week.data?.data ?? []).filter((a) => dayKey(new Date(a.startAt), timezone) === dayKey(d, timezone) && a.status !== "CANCELLED").length);
  const maxDay = Math.max(1, ...perDay);
  const longDate = new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "long", day: "numeric", month: "long", timeZone: timezone }).format(now);

  return (
    <div className="space-y-5">
      {/* Calm header: the date, a greeting, and what's happening right now */}
      <section className="hero-calm animate-fade-up p-6 sm:p-8">
        <div className="grid gap-6 lg:grid-cols-[1fr_auto] lg:items-end">
          <div>
            <p className="eyebrow">{longDate}</p>
            <h1 className="mt-2 font-display text-[1.9rem] font-semibold leading-tight text-ink sm:text-[2.4rem]">{t("cl.greet", { name: me?.firstName ?? "" })}</h1>
            <p className="mt-2 max-w-xl text-[15px] text-muted">
              {sorted.length === 0 ? t("cl.dayEmpty") : t("cl.daySummary", { n: num(sorted.length, locale), done: num(done, locale) })}
            </p>
            <div className="mt-5 flex flex-wrap gap-2">
              {quick.filter(([, , , perm]) => can(perm)).map(([href, label, icon], i) => (
                <Link key={href} href={href} className={`press inline-flex h-9 items-center gap-2 rounded-[10px] px-3.5 text-[13px] font-semibold transition ${i === 0 ? "bg-brand text-white hover:bg-brand-dark" : "bg-surface text-ink shadow-[0_0_0_1px_var(--line-strong)] hover:bg-surface-2"}`}><Icon name={icon} className="h-4 w-4" />{label}</Link>
              ))}
            </div>
          </div>
          {/* Now / next card */}
          <div className="w-full rounded-[16px] bg-surface p-4 shadow-[0_0_0_1px_var(--line),0_10px_30px_-18px_rgb(16_18_24/.25)] lg:w-80">
            <div className="flex items-center justify-between">
              <p className="flex items-center gap-2 text-xs font-semibold text-muted"><span className={`h-2 w-2 rounded-full ${live ? "bg-brand live-dot" : "bg-slate-300"}`} />{live ? t("cl.inRoom") : t("cl.nextUp")}</p>
              <Link href="/dashboard/queue" className="text-[12px] font-semibold text-brand hover:underline">{t("cl.waitingN", { n: num(data.checkedIn, locale) })}</Link>
            </div>
            {(live ?? next) ? (
              <div className="mt-3">
                <p className="truncate font-display text-lg font-semibold text-ink">{(live ?? next)!.patientName}</p>
                <p className="truncate text-[13px] text-muted">{(live ?? next)!.serviceName} · <span dir="ltr">{timeOnly((live ?? next)!.startAt, locale, timezone)}</span></p>
              </div>
            ) : <p className="mt-3 text-sm text-muted">{t("cl.noneNext")}</p>}
            <div className="mt-4">
              <div className="flex justify-between text-[11px] text-faint"><span>{t("cl.dayProgress")}</span><span className="tnum">{num(done, locale)}/{num(sorted.length, locale)}</span></div>
              <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_6%,transparent)]"><div className="grow-x h-full rounded-full bg-brand" style={{ width: `${progress * 100}%` }} /></div>
            </div>
          </div>
        </div>
      </section>

      <div className="grid gap-5 lg:grid-cols-[1.7fr_1fr]">
        <Section title={t("clinic.todaySchedule")} subtitle={t("cl.apptsN", { n: num(sorted.length, locale) })} icon="calendar" action={<SectionLink href="/dashboard/appointments">{t("nav.appointments")}</SectionLink>}>
          {sorted.length === 0 ? <Empty icon="calendar" title={t("clinic.noToday")}>{t("cl.noTodayHint")}</Empty> : (
            <Timeline items={sorted.map((a) => ({
              key: a.id,
              time: timeOnly(a.startAt, locale, timezone),
              title: <>{a.patientName} <span className="ms-1 font-mono text-[11px] font-normal text-faint">{a.patientCode}</span></>,
              meta: `${a.serviceName} · ${a.doctorName}`,
              tone: TONE[a.status] ?? "#a3a8b1",
              active: a.id === live?.id || (!live && a.id === next?.id),
              aside: <span className="flex items-center gap-2">{a.queueNumber ? <span className="rounded-[6px] bg-surface-2 px-1.5 py-0.5 text-[11px] font-bold text-muted ring-1 ring-inset ring-line tnum">#{a.queueNumber}</span> : null}<StatusBadge status={a.status} /></span>,
            }))} />
          )}
        </Section>

        <div className="grid content-start gap-5">
          <Section title={t("cl.needsYou")} icon="bell" flush>
            <ul className="divide-y divide-line">
              {([
                ["/dashboard/appointments", t("clinic.pending"), data.pendingRequests, "calendar", "amber"],
                ["/dashboard/patients", t("clinic.labReviews"), data.pendingLabReviews, "file", "blue"],
                ["/dashboard/appointments", t("clinic.unpaid"), data.unpaidVisitsCount, "wallet", "red"],
              ] as const).map(([href, label, n, icon, tone]) => (
                <li key={label}>
                  <Link href={href} className="group flex items-center gap-3 px-5 py-3.5 transition hover:bg-surface-2">
                    <span className={`grid h-8 w-8 shrink-0 place-items-center rounded-[9px] ${n > 0 ? { amber: "bg-amber-50 text-amber-700", blue: "bg-sky-50 text-sky-700", red: "bg-red-50 text-red-700" }[tone] : "bg-slate-100 text-slate-500"}`}><Icon name={icon} className="h-4 w-4" /></span>
                    <span className="min-w-0 flex-1 truncate text-[13.5px] font-medium text-ink-2">{label}</span>
                    <span className={`font-display text-lg font-semibold tnum ${n > 0 ? "text-ink" : "text-faint"}`}><AnimatedNumber value={n} /></span>
                    <Icon name="chevron" className="h-4 w-4 text-faint flip-rtl" />
                  </Link>
                </li>
              ))}
            </ul>
            {data.unpaidVisitsMinor > 0 && <p className="px-5 pb-3 text-xs text-muted">{t("cl.unpaidTotal", { m: money(data.unpaidVisitsMinor, currency, locale) })}</p>}
          </Section>

          <Section title={t("cl.weekAhead")} icon="chart">
            <div className="flex h-32 items-end gap-2" dir={locale === "ar" ? "rtl" : "ltr"}>
              {days.map((d, i) => (
                <div key={i} className="flex h-full flex-1 flex-col items-center justify-end gap-1.5">
                  <span className="text-[11px] font-semibold text-muted tnum">{perDay[i] ? num(perDay[i], locale) : ""}</span>
                  <div className="grow-y w-full max-w-9 rounded-[6px]" style={{ height: `${Math.max(4, (perDay[i] / maxDay) * 80)}%`, background: i === 0 ? "var(--brand)" : "color-mix(in srgb, var(--brand) 28%, var(--surface))", ["--i" as string]: i }} />
                  <span className={`text-[11px] ${i === 0 ? "font-semibold text-ink" : "text-faint"}`}>{new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "short", timeZone: timezone }).format(d)}</span>
                </div>
              ))}
            </div>
          </Section>
        </div>
      </div>

      <KpiStrip items={[
        { label: t("clinic.revenueMonth"), value: money(data.revenueThisMonthMinor, currency, locale), icon: "billing", tone: "green" },
        { label: t("clinic.completed"), value: data.completedThisMonth, icon: "check", tone: "brand" },
        { label: t("clinic.newPatients"), value: data.newPatientsThisMonth, icon: "patients", tone: "violet" },
        { label: t("clinic.noShows"), value: data.noShowsThisMonth, icon: "xcircle", tone: "slate", hint: t("cl.noShowRate", { n: num(noShowRate, locale) }) },
      ]} />
    </div>
  );
}
