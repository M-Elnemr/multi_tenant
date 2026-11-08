"use client";

import Link from "next/link";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Badge, Empty, KpiStrip, Loading, PageHeader, Section, SectionLink } from "@/components/ui";
import { BRASS, DateTile, Eyebrow, IVORY, LinkButton, NAVY, Rise, useIntl } from "@/components/lawyer/kit";
import { calColor, type Booking, type CalItem } from "@/lib/lawyer";
import { money, timeOnly } from "@/lib/format";

type Overview = {
  openCases: number; underStudy: number; won: number; lost: number; clients: number; pendingBookings: number; todaySessions: number; weekSteps: number; winRate: number | null; dueMinor: number; collectedThisMonthMinor: number;
  upcoming: { id: string; kind: string; startsAt: string; description: string; location: string; caseId: string; number: string; title: string }[];
  casesByType: { caseType: string; n: number }[];
};

export default function LawyerHome() {
  const { t, locale, currency } = useI18n();
  const intl = useIntl();
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
  const hearingsToday = (agenda.data ?? []).filter((i) => i.kind === "STEP" && i.stepKind === "HEARING").length;
  return (
    <div className="space-y-5">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.office")}</Eyebrow>} title={greet} subtitle={t("lw.home.subtitle", { n: agenda.data?.length ?? 0 })}
        actions={<>
          <LinkButton href="/dashboard/schedule" icon="calendar">{t("lw.nav.schedule")}</LinkButton>
          <LinkButton href="/dashboard/cases?new=1" variant="primary" icon="plus">{t("lw.case.new")}</LinkButton>
        </>} />

      <KpiStrip items={[
        { label: t("lw.kpi.today"), value: x?.todaySessions ?? 0, icon: "calendar", tone: "brand", hint: t("lw.kpi.weekSteps", { n: x?.weekSteps ?? 0 }), href: "/dashboard/schedule" },
        { label: t("lw.kpi.pending"), value: x?.pendingBookings ?? 0, icon: "clock", tone: (x?.pendingBookings ?? 0) > 0 ? "amber" : "slate", href: "/dashboard/bookings" },
        { label: t("lw.kpi.open"), value: x?.openCases ?? 0, icon: "briefcase", tone: "slate", hint: t("lw.kpi.underStudy", { n: x?.underStudy ?? 0 }), href: "/dashboard/cases" },
        { label: t("lw.kpi.winRate"), value: x?.winRate == null ? "—" : `${x.winRate}%`, icon: "award", tone: "green", hint: t("lw.kpi.wonLost", { w: x?.won ?? 0, l: x?.lost ?? 0 }) },
      ]} />

      <div className="grid gap-3 sm:grid-cols-2">
        <Link href="/dashboard/finance" className="group flex items-center gap-4 rounded-[18px] bg-surface p-4 shadow-card transition hover:shadow-[0_0_0_1px_var(--line-strong)] sm:p-5">
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-[12px] bg-emerald-50 text-emerald-700"><Icon name="wallet" className="h-5 w-5" /></span>
          <span className="min-w-0 flex-1"><span className="block text-[12.5px] font-medium text-muted">{t("lw.kpi.collected")}</span><span className="mt-1 block truncate font-display text-[1.45rem] font-semibold leading-none text-ink tnum">{fmt(x?.collectedThisMonthMinor ?? 0)}</span></span>
          <Icon name="chevron" className="h-4 w-4 text-faint transition-transform group-hover:translate-x-0.5 flip-rtl" />
        </Link>
        <Link href="/dashboard/finance" className="group flex items-center gap-4 rounded-[18px] bg-surface p-4 shadow-card transition hover:shadow-[0_0_0_1px_var(--line-strong)] sm:p-5">
          <span className={`grid h-11 w-11 shrink-0 place-items-center rounded-[12px] ${(x?.dueMinor ?? 0) > 0 ? "bg-rose-50 text-rose-700" : "bg-surface-2 text-muted"}`}><Icon name="receipt" className="h-5 w-5" /></span>
          <span className="min-w-0 flex-1"><span className="block text-[12.5px] font-medium text-muted">{t("lw.kpi.due")}</span><span className={`mt-1 block truncate font-display text-[1.45rem] font-semibold leading-none tnum ${(x?.dueMinor ?? 0) > 0 ? "text-rose-700" : "text-ink"}`}>{fmt(x?.dueMinor ?? 0)}</span></span>
          <span className="hidden text-[12.5px] font-semibold text-muted group-hover:text-ink sm:inline">{t("lw.kpi.seeFinance")}</span>
          <Icon name="chevron" className="h-4 w-4 text-faint transition-transform group-hover:translate-x-0.5 flip-rtl" />
        </Link>
      </div>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.45fr)_minmax(0,1fr)]">
        {/* Today's court diary */}
        <Rise>
          <section className="overflow-hidden rounded-[18px] bg-surface shadow-card">
            <div className="relative flex items-center justify-between gap-3 px-5 py-4" style={{ background: NAVY, color: IVORY }}>
              <span className="pointer-events-none absolute inset-x-0 bottom-0 h-px" style={{ background: `linear-gradient(90deg, transparent, ${BRASS}, transparent)` }} aria-hidden />
              <div className="min-w-0">
                <p className="text-[11px] font-semibold uppercase tracking-[0.12em]" style={{ color: "#d9b97f" }}>{t("lw.home.today")}</p>
                <p className="mt-1 truncate font-display text-[19px] font-semibold">{new Intl.DateTimeFormat(intl, { weekday: "long", day: "numeric", month: "long" }).format(today)}</p>
              </div>
              <div className="flex shrink-0 items-center gap-3">
                {hearingsToday > 0 && <span className="hidden rounded-full px-2.5 py-1 text-[11.5px] font-semibold sm:inline" style={{ background: "rgb(217 185 127 / .16)", color: "#e6c993" }}>{t("lw.home.hearingsN", { n: hearingsToday })}</span>}
                <Link href="/dashboard/schedule" className="inline-flex items-center gap-1 rounded-[9px] px-2.5 py-1.5 text-[12.5px] font-semibold transition hover:bg-white/10">{t("lw.home.openCalendar")}<Icon name="chevron" className="h-3.5 w-3.5 flip-rtl" /></Link>
              </div>
            </div>
            <div className="p-3 sm:p-4">
              {agenda.loading && !agenda.data ? (
                <div className="space-y-2" aria-busy="true">{[0, 1, 2].map((i) => <div key={i} className="skeleton h-16 !rounded-[12px]" />)}</div>
              ) : agenda.data && agenda.data.length === 0 ? (
                <Empty compact icon="calendar" title={t("lw.home.freeDay")} action={<LinkButton href="/dashboard/schedule" icon="plus">{t("lw.sch.add")}</LinkButton>} />
              ) : (
                <ol className="relative">
                  {agenda.data?.map((i, n) => {
                    const c = calColor(i);
                    const href = i.caseId ? `/dashboard/cases/${i.caseId}` : i.kind === "BOOKING" ? "/dashboard/bookings" : "/dashboard/schedule";
                    return (
                      <li key={i.kind + i.id} className="relative flex gap-3">
                        <div className="w-14 shrink-0 pt-3 text-end text-[12.5px] font-semibold text-ink-2 tnum" dir="ltr">{i.allDay ? "—" : timeOnly(i.startsAt, locale)}</div>
                        <div className="relative flex flex-col items-center">
                          <span className="relative z-10 mt-4 h-2.5 w-2.5 rounded-full ring-4 ring-surface" style={{ background: c.dot }} />
                          {n < (agenda.data?.length ?? 0) - 1 && <span className="absolute top-6 -bottom-1 w-px bg-line" />}
                        </div>
                        <Link href={href} className={`mb-1.5 block min-w-0 flex-1 rounded-[12px] px-3 py-2.5 transition hover:bg-surface-2 ${i.done ? "opacity-60" : ""}`}>
                          <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11.5px] font-semibold" style={{ color: c.dot }}>
                            <span>{t(`lw.cal.${i.kind === "STEP" ? i.stepKind : i.kind}`)}</span>
                            {i.caseNumber && <span className="font-mono text-muted">#{i.caseNumber}</span>}
                          </p>
                          <p className={`mt-0.5 truncate text-[14px] font-semibold text-ink ${i.done ? "line-through" : ""}`}>{i.title}</p>
                          {i.location && <p className="mt-0.5 flex items-center gap-1 truncate text-xs text-muted"><Icon name="pin" className="h-3.5 w-3.5 shrink-0" />{i.location}</p>}
                        </Link>
                      </li>
                    );
                  })}
                </ol>
              )}
            </div>
          </section>
        </Rise>

        <div className="space-y-5">
          <Section title={t("lw.home.requests")} icon="clock" flush action={<SectionLink href="/dashboard/bookings">{t("lw.home.all")}</SectionLink>}>
            {pending.loading && !pending.data ? <div className="space-y-2 p-4" aria-busy="true">{[0, 1].map((i) => <div key={i} className="skeleton h-12 !rounded-[10px]" />)}</div>
              : (pending.data?.data.length ?? 0) === 0 ? <p className="px-5 py-6 text-center text-sm text-muted">{t("lw.home.noRequests")}</p> : (
                <ul className="divide-y divide-line">
                  {pending.data!.data.map((b) => (
                    <li key={b.id}>
                      <Link href="/dashboard/bookings" className="flex items-center gap-3 px-5 py-3 transition hover:bg-surface-2">
                        <DateTile date={new Date(b.startsAt)} tone="amber" size="sm" />
                        <span className="min-w-0 flex-1"><span className="block truncate text-[13.5px] font-semibold text-ink">{b.guestName}</span>
                          <span className="block truncate text-xs text-muted tnum">{new Date(b.startsAt).toLocaleTimeString(intl, { hour: "numeric", minute: "2-digit" })}{b.serviceName ? ` · ${b.serviceName}` : ""}</span></span>
                        <Badge tone="amber">{t("lw.bk.review")}</Badge>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
          </Section>

          <Section title={t("lw.home.upcoming")} icon="gavel" flush>
            {(x?.upcoming.length ?? 0) === 0 ? <p className="px-5 py-6 text-center text-sm text-muted">{t("lw.home.noUpcoming")}</p> : (
              <ul className="divide-y divide-line">
                {x!.upcoming.map((s) => (
                  <li key={s.id}><Link href={`/dashboard/cases/${s.caseId}`} className="flex items-center gap-3 px-5 py-3 transition hover:bg-surface-2">
                    <DateTile date={new Date(s.startsAt)} tone="navy" size="sm" />
                    <div className="min-w-0 flex-1"><p className="truncate text-[13.5px] font-semibold text-ink">{s.description}</p><p className="truncate text-xs text-muted"><span className="font-mono">#{s.number}</span> · {s.title}</p></div>
                  </Link></li>
                ))}
              </ul>
            )}
          </Section>

          {(x?.casesByType.length ?? 0) > 0 && (
            <Section title={t("lw.home.byType")} icon="scale">
              <ul className="space-y-3">{x!.casesByType.slice(0, 6).map((c) => (
                <li key={c.caseType}>
                  <div className="flex justify-between text-[13px]"><span className="text-ink-2">{t(`lw.type.${c.caseType}`)}</span><b className="font-semibold text-ink tnum">{c.n}</b></div>
                  <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_7%,transparent)]"><div className="grow-x h-full rounded-full" style={{ width: `${(c.n / totalTypes) * 100}%`, background: BRASS }} /></div>
                </li>
              ))}</ul>
            </Section>
          )}
        </div>
      </div>
    </div>
  );
}
