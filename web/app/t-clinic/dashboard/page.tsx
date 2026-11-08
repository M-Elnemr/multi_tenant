"use client";

import Link from "next/link";
import { motion, useReducedMotion } from "motion/react";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon, type IconName } from "@/components/icons";
import { AnimatedNumber, Avatar, Empty, ErrorText, KpiStrip, Skeleton, SectionLink } from "@/components/ui";
import { CalmStatus, Paper, PaperHead, Pill, calm } from "@/components/clinic/dash";
import { money, num, timeOnly } from "@/lib/format";

type Dash = {
  today: { id: string; startAt: string; status: string; queueNumber?: number; patientName: string; patientCode: string; doctorName: string; serviceName: string }[];
  pendingRequests: number; checkedIn: number; pendingLabReviews: number; noShowsThisMonth: number; completedThisMonth: number; revenueThisMonthMinor: number; newPatientsThisMonth: number; unpaidVisitsCount: number; unpaidVisitsMinor: number;
};
type Appt = { id: string; startAt: string; status: string };

const dayKey = (d: Date, tz: string) => d.toLocaleDateString("en-CA", { timeZone: tz });

function HomeSkeleton() {
  return (
    <div className="space-y-5" aria-busy="true">
      <Skeleton className="h-52 w-full !rounded-[18px]" />
      <div className="grid gap-5 lg:grid-cols-[1.7fr_1fr]"><Skeleton className="h-96 !rounded-[18px]" /><div className="space-y-5"><Skeleton className="h-48 !rounded-[18px]" /><Skeleton className="h-44 !rounded-[18px]" /></div></div>
      <Skeleton className="h-28 w-full !rounded-[18px]" />
    </div>
  );
}

/** The doctor's morning page: today's day, who is in the room and next, what needs attention, the week ahead and this month's real numbers. */
export default function ClinicOverview() {
  const { t, locale, timezone, currency } = useI18n();
  const reduce = useReducedMotion();
  const { me, can } = useMe();
  const { data, loading, error } = useApi<Dash>("clinic/dashboard");
  const now = new Date();
  const from = dayKey(now, timezone), to = dayKey(new Date(now.getTime() + 6 * 86400000), timezone);
  const week = useApi<Page<Appt>>(can("appointment.read") || can("appointment.manage") ? `clinic/appointments?from=${from}&to=${to}&pageSize=500` : null);
  if (loading && !data) return <HomeSkeleton />;
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
  const fmtLocale = locale === "ar" ? "ar-EG" : "en-GB";
  const longDate = new Intl.DateTimeFormat(fmtLocale, { weekday: "long", day: "numeric", month: "long", timeZone: timezone }).format(now);
  const focus = live ?? next;
  const attention = [
    { href: "/dashboard/appointments", label: t("clinic.pending"), hint: t("cld.pendingHint"), n: data.pendingRequests, icon: "calendar" as IconName, tone: "bg-amber-50 text-amber-700" },
    { href: "/dashboard/patients", label: t("clinic.labReviews"), hint: t("cld.labsHint"), n: data.pendingLabReviews, icon: "flask" as IconName, tone: "bg-sky-50 text-sky-700" },
    { href: "/dashboard/appointments", label: t("clinic.unpaid"), hint: data.unpaidVisitsMinor > 0 ? t("cl.unpaidTotal", { m: money(data.unpaidVisitsMinor, currency, locale) }) : t("cld.unpaidHint"), n: data.unpaidVisitsCount, icon: "wallet" as IconName, tone: "bg-rose-50 text-rose-700" },
  ];

  return (
    <div className="space-y-5">
      {/* Calm header: the date, a greeting, quick actions, and who is in the room / next */}
      <section className="relative animate-fade-up overflow-hidden rounded-[18px] bg-[linear-gradient(160deg,color-mix(in_srgb,var(--rail-accent)_11%,var(--surface)),var(--surface)_62%)] p-5 shadow-card sm:p-8">
        <span aria-hidden className="pointer-events-none absolute -end-16 -top-20 h-56 w-56 rounded-full bg-[color-mix(in_srgb,var(--rail-accent)_10%,transparent)] blur-2xl" />
        <div className="relative grid gap-6 lg:grid-cols-[1fr_auto] lg:items-end">
          <div className="min-w-0">
            <p className="eyebrow">{longDate}</p>
            <h1 className="mt-2 font-display text-[1.75rem] font-semibold leading-tight text-ink sm:text-[2.35rem]">{t("cl.greet", { name: me?.firstName ?? "" })}</h1>
            <p className="mt-2 max-w-xl text-[15px] leading-relaxed text-muted">
              {sorted.length === 0 ? t("cl.dayEmpty") : t("cl.daySummary", { n: num(sorted.length, locale), done: num(done, locale) })}
            </p>
            <div className="mt-5 flex flex-wrap gap-2">
              {quick.filter(([, , , perm]) => can(perm)).map(([href, label, icon], i) => (
                <Link key={href} href={href} className={`press inline-flex h-10 items-center gap-2 rounded-[11px] px-3.5 text-[13px] font-semibold transition pointer-coarse:min-h-11 ${i === 0 ? "bg-ink text-surface hover:opacity-90" : "bg-surface text-ink shadow-[0_0_0_1px_var(--line-strong)] hover:bg-surface-2"}`}><Icon name={icon} className="h-4 w-4" />{label}</Link>
              ))}
            </div>
          </div>

          {/* Now / next */}
          <div className="w-full rounded-[16px] bg-surface p-4 shadow-[0_0_0_1px_var(--line),0_14px_34px_-20px_rgb(16_18_24/.3)] lg:w-[21rem]">
            <div className="flex items-center justify-between gap-3">
              <p className="flex items-center gap-2 text-xs font-semibold text-muted"><span className={`h-2 w-2 rounded-full ${live ? "live-dot bg-[var(--rail-accent)]" : "bg-slate-300"}`} />{live ? t("cl.inRoom") : t("cl.nextUp")}</p>
              <Link href="/dashboard/queue" className="text-[12px] font-semibold text-[var(--rail-accent-ink)] hover:underline">{t("cl.waitingN", { n: num(data.checkedIn, locale) })}</Link>
            </div>
            {focus ? (
              <div className="mt-3 flex items-center gap-3">
                <Avatar name={focus.patientName} className="h-11 w-11 text-sm" />
                <div className="min-w-0">
                  <p className="truncate font-display text-[17px] font-semibold text-ink">{focus.patientName}</p>
                  <p className="truncate text-[13px] text-muted">{focus.serviceName} · <span dir="ltr" className="tnum">{timeOnly(focus.startAt, locale, timezone)}</span></p>
                </div>
              </div>
            ) : <p className="mt-3 text-sm text-muted">{t("cl.noneNext")}</p>}
            <div className="mt-4">
              <div className="flex justify-between text-[11px] text-faint"><span>{t("cl.dayProgress")}</span><span className="tnum">{num(done, locale)}/{num(sorted.length, locale)}</span></div>
              <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_6%,transparent)]">
                <motion.div className="h-full rounded-full bg-[var(--rail-accent)]" initial={reduce ? false : { width: 0 }} animate={{ width: `${progress * 100}%` }} transition={{ duration: 0.6, ease: [0.2, 0.8, 0.2, 1] }} style={reduce ? { width: `${progress * 100}%` } : undefined} />
              </div>
            </div>
          </div>
        </div>
      </section>

      <div className="grid gap-5 lg:grid-cols-[1.7fr_1fr]">
        {/* Today's day, like a clinic schedule: time, status rail, patient */}
        <Paper>
          <PaperHead icon="calendar" title={t("clinic.todaySchedule")} subtitle={t("cl.apptsN", { n: num(sorted.length, locale) })} action={<SectionLink href="/dashboard/appointments">{t("nav.appointments")}</SectionLink>} />
          <div className="px-3 pb-4 sm:px-4">
            {sorted.length === 0 ? <div className="px-2 pb-2"><Empty icon="calendar" title={t("clinic.noToday")} compact>{t("cl.noTodayHint")}</Empty></div> : (
              <ol className="relative">
                {sorted.map((a, i) => {
                  const active = a.id === live?.id || (!live && a.id === next?.id);
                  const past = a.status === "COMPLETED" || a.status === "DONE" || a.status === "CANCELLED" || a.status === "NO_SHOW";
                  return (
                    <motion.li key={a.id} initial={reduce ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.22, delay: Math.min(i, 8) * 0.03 }}
                      className={`relative flex items-stretch gap-3 rounded-[14px] px-2 py-2.5 transition sm:gap-4 ${active ? "bg-[color-mix(in_srgb,var(--rail-accent)_9%,transparent)]" : "hover:bg-surface-2"}`}>
                      <div className={`w-12 shrink-0 pt-1 text-end text-[13px] font-semibold tnum sm:w-14 ${past ? "text-faint" : "text-ink-2"}`} dir="ltr">{timeOnly(a.startAt, locale, timezone)}</div>
                      <div className="relative flex flex-col items-center">
                        <span className={`relative z-10 mt-2 h-2.5 w-2.5 rounded-full ring-4 ring-surface ${active ? "live-dot" : ""}`} style={{ background: calm(a.status).dot }} />
                        {i < sorted.length - 1 && <span className="absolute bottom-[-0.75rem] top-5 w-px bg-line" />}
                      </div>
                      <div className="flex min-w-0 flex-1 flex-wrap items-center justify-between gap-x-3 gap-y-1.5">
                        <div className={`min-w-0 ${past ? "opacity-70" : ""}`}>
                          <p className="truncate text-[14px] font-semibold text-ink">{a.patientName} <span className="ms-1 font-mono text-[11px] font-normal text-faint">{a.patientCode}</span></p>
                          <p className="truncate text-xs text-muted">{a.serviceName} · {a.doctorName}</p>
                        </div>
                        <span className="flex items-center gap-2">
                          {a.queueNumber ? <Pill>#{num(a.queueNumber, locale)}</Pill> : null}
                          <CalmStatus status={a.status} />
                        </span>
                      </div>
                    </motion.li>
                  );
                })}
              </ol>
            )}
          </div>
        </Paper>

        <div className="grid content-start gap-5">
          <Paper>
            <PaperHead icon="bell" title={t("cl.needsYou")} />
            <ul className="px-2 pb-2">
              {attention.map((a) => (
                <li key={a.label}>
                  <Link href={a.href} className="group flex items-center gap-3 rounded-[12px] px-3 py-3 transition hover:bg-surface-2">
                    <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-[11px] ${a.n > 0 ? a.tone : "bg-slate-100 text-slate-500"}`}><Icon name={a.icon} className="h-4 w-4" /></span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13.5px] font-medium text-ink-2">{a.label}</span>
                      <span className="block truncate text-[11.5px] text-faint">{a.hint}</span>
                    </span>
                    <span className={`font-display text-lg font-semibold tnum ${a.n > 0 ? "text-ink" : "text-faint"}`}><AnimatedNumber value={a.n} /></span>
                    <Icon name="chevron" className="h-4 w-4 text-faint transition-transform group-hover:translate-x-0.5 flip-rtl rtl:group-hover:-translate-x-0.5" />
                  </Link>
                </li>
              ))}
            </ul>
          </Paper>

          <Paper>
            <PaperHead icon="chart" title={t("cl.weekAhead")} subtitle={t("cld.weekHint")} />
            <div className="px-5 pb-5">
              <div className="flex h-32 items-end gap-2">
                {days.map((d, i) => (
                  <div key={i} className="flex h-full flex-1 flex-col items-center justify-end gap-1.5">
                    <span className="text-[11px] font-semibold text-muted tnum">{perDay[i] ? num(perDay[i], locale) : ""}</span>
                    <motion.div className="w-full max-w-9 origin-bottom rounded-[7px]" initial={reduce ? false : { scaleY: 0 }} animate={{ scaleY: 1 }} transition={{ duration: 0.4, delay: i * 0.04, ease: [0.2, 0.8, 0.2, 1] }}
                      style={{ height: `${Math.max(4, (perDay[i] / maxDay) * 80)}%`, background: i === 0 ? "var(--rail-accent)" : "color-mix(in srgb, var(--rail-accent) 26%, var(--surface))" }} />
                    <span className={`text-[11px] ${i === 0 ? "font-semibold text-ink" : "text-faint"}`}>{new Intl.DateTimeFormat(fmtLocale, { weekday: "short", timeZone: timezone }).format(d)}</span>
                  </div>
                ))}
              </div>
            </div>
          </Paper>
        </div>
      </div>

      <div>
        <p className="eyebrow mb-2.5 px-1">{t("cld.thisMonth")}</p>
        <KpiStrip className="!rounded-[18px]" items={[
          { label: t("clinic.revenueMonth"), value: money(data.revenueThisMonthMinor, currency, locale), icon: "billing", tone: "green" },
          { label: t("clinic.completed"), value: data.completedThisMonth, icon: "check", tone: "brand" },
          { label: t("clinic.newPatients"), value: data.newPatientsThisMonth, icon: "patients", tone: "violet" },
          { label: t("clinic.noShows"), value: data.noShowsThisMonth, icon: "xcircle", tone: "slate", hint: t("cl.noShowRate", { n: num(noShowRate, locale) }) },
        ]} />
      </div>
    </div>
  );
}
