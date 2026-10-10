"use client";

import Link from "next/link";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon, type IconName } from "@/components/icons";
import { AnimatedNumber, Empty, ErrorText, Loading, Section, Stat, StatusBadge } from "@/components/ui";
import { money, timeOnly } from "@/lib/format";

type Dash = {
  today: { id: string; startAt: string; status: string; queueNumber?: number; patientName: string; patientCode: string; doctorName: string; serviceName: string }[];
  pendingRequests: number; checkedIn: number; pendingLabReviews: number; noShowsThisMonth: number; completedThisMonth: number; revenueThisMonthMinor: number; newPatientsThisMonth: number; unpaidVisitsCount: number; unpaidVisitsMinor: number;
};

export default function ClinicOverview() {
  const { t, locale, timezone, currency } = useI18n();
  const { me, can } = useMe();
  const { data, loading, error } = useApi<Dash>("clinic/dashboard");
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  const hero: [string, number][] = [[t("clinic.today"), data.today.length], [t("clinic.waiting"), data.checkedIn], [t("clinic.pending"), data.pendingRequests]];
  const quick: [string, string, IconName, string][] = [["/dashboard/queue", t("nav.queue"), "queue", "appointment.manage"], ["/dashboard/appointments", t("nav.appointments"), "calendar", "appointment.manage"], ["/dashboard/patients", t("nav.patients"), "patients", "patient.read"]];
  const stats = [[t("clinic.labReviews"), data.pendingLabReviews, "file", "amber"], [t("clinic.revenueMonth"), money(data.revenueThisMonthMinor, currency, locale), "billing", "green"], [t("clinic.completed"), data.completedThisMonth, "check", "green"], [t("clinic.newPatients"), data.newPatientsThisMonth, "patients", "brand"], [t("clinic.noShows"), data.noShowsThisMonth, "close", "red"], [t("clinic.unpaid"), `${money(data.unpaidVisitsMinor, currency, locale)} (${data.unpaidVisitsCount})`, "coupon", "red"]] as const;
  return (
    <div className="space-y-6">
      <div className="hero-card animate-fade-up p-6 sm:p-8">
        <div className="flex flex-wrap items-end justify-between gap-6">
          <div>
            <p className="flex items-center gap-2 text-sm font-medium text-white/80"><Icon name="sparkles" className="h-4 w-4" />{t("dash.hello")}</p>
            <h1 className="mt-1 text-2xl font-bold tracking-tight sm:text-3xl">{me?.firstName}</h1>
            <div className="mt-5 flex flex-wrap gap-2">
              {quick.filter(([, , , perm]) => can(perm)).map(([href, label, icon]) => <Link key={href} href={href} className="inline-flex items-center gap-2 rounded-xl bg-white/15 px-3.5 py-2 text-sm font-semibold backdrop-blur transition hover:bg-white/25 active:scale-95"><Icon name={icon} className="h-4 w-4" />{label}</Link>)}
            </div>
          </div>
          <div className="flex gap-3">
            {hero.map(([label, n]) => <div key={label} className="min-w-[5.5rem] rounded-2xl bg-white/12 px-4 py-3 text-center ring-1 ring-white/20 backdrop-blur"><p className="text-3xl font-bold leading-none"><AnimatedNumber value={n} /></p><p className="mt-1.5 text-xs text-white/80">{label}</p></div>)}
          </div>
        </div>
      </div>

      <div className="stagger grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-6">{stats.map(([l, v, icon, tone], i) => <div key={String(l)} style={{ "--i": i } as React.CSSProperties}><Stat label={String(l)} value={v} icon={icon} tone={tone} /></div>)}</div>

      <Section title={t("clinic.todaySchedule")} action={<Link href="/dashboard/appointments" className="inline-flex items-center gap-1 text-sm font-semibold text-brand hover:underline">{t("nav.appointments")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>}>
        {data.today.length === 0 ? <Empty>{t("clinic.noToday")}</Empty> : (
          <ol className="relative space-y-1">
            {data.today.map((a) => (
              <li key={a.id} className="group flex items-center gap-4 rounded-xl px-2 py-3 transition hover:bg-brand-soft/50">
                <span className="w-16 shrink-0 text-center font-mono text-sm font-semibold text-brand">{timeOnly(a.startAt, locale, timezone)}</span>
                <span className="h-9 w-1 shrink-0 rounded-full bg-brand/25 transition group-hover:bg-brand" />
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold text-ink">{a.patientName} <span className="ms-1 font-mono text-xs font-normal text-slate-400">{a.patientCode}</span></p>
                  <p className="truncate text-sm text-slate-500">{a.serviceName} · {a.doctorName}</p>
                </div>
                <div className="flex shrink-0 items-center gap-2">{a.queueNumber ? <span className="rounded-lg bg-slate-100 px-2 py-0.5 text-xs font-bold text-slate-600">#{a.queueNumber}</span> : null}<StatusBadge status={a.status} /></div>
              </li>
            ))}
          </ol>
        )}
      </Section>
    </div>
  );
}
