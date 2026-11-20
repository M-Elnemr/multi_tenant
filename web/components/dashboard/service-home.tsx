"use client";

import Link from "next/link";
import { useState } from "react";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Button, Empty, KpiStrip, Loading, PageHeader, Section } from "@/components/ui";
import { AiSellerCard, InboxPreview } from "@/components/dashboard/home-ai";
import { BookingStatus, type Booking, type Service } from "@/components/dashboard/bookings/common";
import { NewBooking } from "@/components/dashboard/bookings/appointments";
import { money } from "@/lib/format";

const today = (tz: string) => new Intl.DateTimeFormat("en-CA", { timeZone: tz }).format(new Date());

/** The home of a SERVICE business: what is happening today in the diary comes first (not orders and stock). */
export function ServiceOverview() {
  const { t, timezone, currency, locale } = useI18n();

  const { can, canSee } = useMe();
  const day = today(timezone);
  const summary = useApi<{ pending: number; next24: number; upcoming: number }>(can("booking.read") ? "store/bookings/summary" : null);
  const todays = useApi<Page<Booking>>(can("booking.read") ? `store/bookings?from=${day}&to=${day}&pageSize=30` : null);
  const pending = useApi<Page<Booking>>(can("booking.read") ? "store/bookings?status=PENDING&pageSize=5" : null);
  const services = useApi<Service[]>(can("booking.read") ? "store/services" : null);
  const [add, setAdd] = useState(false);
  const [v, setV] = useState(0);
  const list = todays.data?.data ?? [];
  const live = list.filter((b) => b.status === "CONFIRMED" || b.status === "PENDING");
  const done = list.filter((b) => b.status === "COMPLETED").length;
  const missed = list.filter((b) => b.status === "NO_SHOW").length;
  const cancelled = list.filter((b) => b.status === "CANCELLED" || b.status === "DECLINED").length;
  const earned = list.filter((b) => b.status === "COMPLETED").reduce((s, b) => s + b.priceMinor, 0);
  return (
    <div key={v} className="space-y-6">
      <PageHeader title={t("svc.home.title")} subtitle={t("svc.home.sub")} actions={can("booking.manage") ? <Button onClick={() => setAdd(true)}><Icon name="plus" className="h-4 w-4" />{t("bk.new")}</Button> : undefined} />
      <KpiStrip items={[
        { label: t("svc.home.today"), value: live.length, hint: t("svc.home.todayHint", { n: String(done) }), icon: "calendar", href: "/dashboard/bookings" },
        { label: t("svc.home.pending"), value: summary.data?.pending ?? "–", hint: t("svc.home.pendingHint"), icon: "clock", tone: (summary.data?.pending ?? 0) > 0 ? "amber" : undefined, href: "/dashboard/bookings" },
        { label: t("svc.home.next24"), value: summary.data?.next24 ?? "–", icon: "bell" },
        { label: t("svc.home.earned"), value: money(earned, currency, locale), hint: t("svc.home.earnedHint"), icon: "wallet" },
      ]} />
      <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
        <Section title={t("svc.home.diary")} action={<Link href="/dashboard/bookings" className="text-[13px] font-semibold text-brand hover:underline">{t("svc.home.all")}</Link>}>
          {todays.loading && !todays.data ? <Loading variant="table" /> : list.length === 0 ? <Empty icon="calendar" title={t("svc.home.none")}>{t("svc.home.noneHint")}</Empty> : (
            <ul className="divide-y divide-line rounded-[14px] bg-surface shadow-card">
              {list.map((b) => (
                <li key={b.id} className="flex items-center gap-3 p-3.5">
                  <span className="grid w-16 shrink-0 place-items-center rounded-xl bg-canvas py-1.5 text-center tnum"><span className="text-[15px] font-bold text-ink">{b.time}</span><span className="text-[11px] text-muted">{b.endTime}</span></span>
                  <div className="min-w-0 flex-1"><p className="truncate font-semibold text-ink">{b.serviceName}</p><p className="truncate text-[13px] text-muted">{b.customerName} · <span dir="ltr">{b.phone}</span></p></div>
                  <div className="flex flex-col items-end gap-1"><BookingStatus status={b.status} /><span className="text-[11px] text-faint">{t(`bk.src.${b.source}`)}</span></div>
                </li>
              ))}
            </ul>
          )}
          {(missed > 0 || cancelled > 0) && <p className="mt-2 text-[12.5px] text-muted">{t("svc.home.missed", { missed: String(missed), cancelled: String(cancelled) })}</p>}
        </Section>
        <div className="space-y-6">
          <Section title={t("svc.home.requests")}>
            {(pending.data?.data ?? []).length === 0 ? <Empty title={t("svc.home.noRequests")} /> : (
              <ul className="divide-y divide-line rounded-[14px] bg-surface shadow-card">
                {(pending.data?.data ?? []).map((b) => (
                  <li key={b.id}><Link href="/dashboard/bookings" className="flex items-center gap-3 p-3.5 hover:bg-canvas"><div className="min-w-0 flex-1"><p className="truncate font-semibold text-ink">{b.serviceName}</p><p className="truncate text-[13px] text-muted tnum">{b.date} · {b.time} · {b.customerName}</p></div><Icon name="chevron" className="h-4 w-4 text-faint" /></Link></li>
                ))}
              </ul>
            )}
          </Section>
          {(services.data ?? []).filter((s) => s.isActive).length === 0 && can("booking.manage") && (
            <div className="rounded-[14px] bg-brand-soft p-4 text-[13.5px] text-ink"><p className="font-semibold">{t("svc.home.setup")}</p><p className="mt-1 text-muted">{t("svc.home.setupHint")}</p><Link href="/dashboard/bookings?tab=services" className="mt-2 inline-block font-semibold text-brand hover:underline">{t("svc.home.addServices")}</Link></div>
          )}
          {canSee("ai") && <AiSellerCard />}
        </div>
      </div>
      {canSee("ai") && <InboxPreview />}
      <NewBooking open={add} onClose={() => setAdd(false)} services={services.data ?? []} onDone={() => { setAdd(false); setV((x) => x + 1); }} />
    </div>
  );
}
