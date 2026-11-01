"use client";

import Link from "next/link";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Badge, Loading, PageHeader, Section, Stat } from "@/components/ui";
import { calColor, type Booking, type CalItem } from "@/lib/lawyer";
import { money, timeOnly } from "@/lib/format";

type Overview = {
  openCases: number; underStudy: number; won: number; lost: number; clients: number; pendingBookings: number; todaySessions: number; weekSteps: number; winRate: number | null; dueMinor: number; collectedThisMonthMinor: number;
  upcoming: { id: string; kind: string; startsAt: string; description: string; location: string; caseId: string; number: string; title: string }[];
  casesByType: { caseType: string; n: number }[];
};

export default function LawyerHome() {
  const { t, locale, currency } = useI18n();
  const { me } = useMe();
  const o = useApi<Overview>("lawyer/overview");
  const today = new Date();
  const d = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  const agenda = useApi<CalItem[]>(`lawyer/schedule?from=${d}&to=${d}`);
  const pending = useApi<{ data: Booking[] }>("lawyer/bookings?status=PENDING&upcoming=true&pageSize=5");
  const fmt = (m: number) => money(m, currency, locale);
  const hour = today.getHours();
  const greet = t(hour < 12 ? "lw.home.morning" : hour < 18 ? "lw.home.afternoon" : "lw.home.evening", { name: me?.firstName ?? "" });
  if (o.loading && !o.data) return <Loading />;
  const x = o.data;
  const totalTypes = (x?.casesByType ?? []).reduce((s, c) => s + c.n, 0) || 1;
  return (
    <>
      <PageHeader title={greet} subtitle={t("lw.home.subtitle", { n: agenda.data?.length ?? 0 })}
        actions={<><Link href="/dashboard/cases?new=1" className="inline-flex items-center gap-2 rounded-xl bg-brand-gradient px-4 py-2.5 text-sm font-semibold text-white shadow-brand"><Icon name="plus" className="h-4 w-4" />{t("lw.case.new")}</Link>
          <Link href="/dashboard/schedule" className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700"><Icon name="calendar" className="h-4 w-4" />{t("lw.nav.schedule")}</Link></>} />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={t("lw.kpi.today")} value={x?.todaySessions ?? 0} icon="calendar" hint={t("lw.kpi.weekSteps", { n: x?.weekSteps ?? 0 })} />
        <Stat label={t("lw.kpi.pending")} value={x?.pendingBookings ?? 0} icon="clock" tone={(x?.pendingBookings ?? 0) > 0 ? "amber" : "brand"} />
        <Stat label={t("lw.kpi.open")} value={x?.openCases ?? 0} icon="briefcase" hint={t("lw.kpi.underStudy", { n: x?.underStudy ?? 0 })} />
        <Stat label={t("lw.kpi.winRate")} value={x?.winRate == null ? "—" : `${x.winRate}%`} icon="award" tone="green" hint={t("lw.kpi.wonLost", { w: x?.won ?? 0, l: x?.lost ?? 0 })} />
      </div>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <Stat label={t("lw.kpi.collected")} value={fmt(x?.collectedThisMonthMinor ?? 0)} icon="wallet" tone="green" />
        <Stat label={t("lw.kpi.due")} value={fmt(x?.dueMinor ?? 0)} icon="receipt" tone={(x?.dueMinor ?? 0) > 0 ? "red" : "brand"} hint={<Link href="/dashboard/finance" className="font-semibold text-brand">{t("lw.kpi.seeFinance")}</Link>} />
      </div>
      <div className="mt-6 grid gap-5 lg:grid-cols-[1.4fr_1fr]">
        <Section title={t("lw.home.today")} action={<Link href="/dashboard/schedule" className="text-sm font-semibold text-brand">{t("lw.home.openCalendar")}</Link>}>
          {agenda.data && agenda.data.length === 0 ? <p className="py-6 text-center text-sm text-slate-500">☕ {t("lw.home.freeDay")}</p> : (
            <ol className="relative space-y-3 border-s-2 border-slate-100 ps-5">
              {agenda.data?.map((i) => {
                const c = calColor(i);
                return (
                  <li key={i.kind + i.id} className="relative">
                    <span className="absolute -start-[27px] top-1.5 h-3.5 w-3.5 rounded-full ring-4 ring-white" style={{ background: c.dot }} />
                    <Link href={i.caseId ? `/dashboard/cases/${i.caseId}` : i.kind === "BOOKING" ? "/dashboard/bookings" : "/dashboard/schedule"} className="block rounded-xl p-3 transition hover:bg-slate-50" style={{ background: c.bg }}>
                      <p className="flex items-center justify-between gap-2 text-xs font-bold" style={{ color: c.fg }}><span>{timeOnly(i.startsAt, locale)} · {t(`lw.cal.${i.kind === "STEP" ? i.stepKind : i.kind}`)}</span>{i.caseNumber && <span>#{i.caseNumber}</span>}</p>
                      <p className="mt-0.5 font-semibold text-slate-800">{i.title}</p>
                      {i.location && <p className="text-xs text-slate-500">📍 {i.location}</p>}
                    </Link>
                  </li>
                );
              })}
            </ol>
          )}
        </Section>
        <div className="space-y-5">
          <Section title={t("lw.home.requests")} action={<Link href="/dashboard/bookings" className="text-sm font-semibold text-brand">{t("lw.home.all")}</Link>}>
            {(pending.data?.data.length ?? 0) === 0 ? <p className="py-3 text-center text-sm text-slate-500">{t("lw.home.noRequests")}</p> : (
              <ul className="divide-y divide-slate-100">
                {pending.data!.data.map((b) => (
                  <li key={b.id} className="flex items-center justify-between gap-2 py-2.5">
                    <div className="min-w-0"><p className="truncate font-semibold text-slate-800">{b.guestName}</p><p className="text-xs text-slate-500">{new Date(b.startsAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "short", day: "numeric", month: "short", hour: "numeric", minute: "2-digit" })}</p></div>
                    <Link href="/dashboard/bookings" className="shrink-0"><Badge tone="amber">{t("lw.bk.review")}</Badge></Link>
                  </li>
                ))}
              </ul>
            )}
          </Section>
          <Section title={t("lw.home.upcoming")}>
            {(x?.upcoming.length ?? 0) === 0 ? <p className="py-3 text-center text-sm text-slate-500">{t("lw.home.noUpcoming")}</p> : (
              <ul className="space-y-2">
                {x!.upcoming.map((s) => (
                  <li key={s.id}><Link href={`/dashboard/cases/${s.caseId}`} className="flex items-center gap-3 rounded-xl p-2 hover:bg-slate-50">
                    <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-indigo-50 text-center text-[11px] font-bold leading-tight text-indigo-700">{new Date(s.startsAt).toLocaleDateString(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short" })}</span>
                    <div className="min-w-0"><p className="truncate text-sm font-semibold text-slate-800">{s.description}</p><p className="truncate text-xs text-slate-500">#{s.number} · {s.title}</p></div>
                  </Link></li>
                ))}
              </ul>
            )}
          </Section>
          {(x?.casesByType.length ?? 0) > 0 && (
            <Section title={t("lw.home.byType")}>
              <ul className="space-y-2">{x!.casesByType.slice(0, 6).map((c) => (
                <li key={c.caseType}><div className="flex justify-between text-sm"><span>{t(`lw.type.${c.caseType}`)}</span><b>{c.n}</b></div>
                  <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-brand" style={{ width: `${(c.n / totalTypes) * 100}%` }} /></div></li>
              ))}</ul>
            </Section>
          )}
        </div>
      </div>
    </>
  );
}
