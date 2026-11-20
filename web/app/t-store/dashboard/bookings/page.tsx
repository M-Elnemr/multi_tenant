"use client";

import { Suspense, useState } from "react";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Button, KpiStrip, Loading, PageHeader, Tabs } from "@/components/ui";
import { useNavTitle, useSection } from "@/components/dashboard/section";
import { Appointments, NewBooking } from "@/components/dashboard/bookings/appointments";
import type { Service } from "@/components/dashboard/bookings/common";
import { HoursAndRules } from "@/components/dashboard/bookings/hours";
import { Calendar } from "@/components/dashboard/bookings/calendar";
import { Team } from "@/components/dashboard/bookings/team";
import { Insights } from "@/components/dashboard/bookings/insights";
import { Services } from "@/components/dashboard/bookings/services";

type Tab = "appointments" | "calendar" | "services" | "team" | "insights" | "hours";

export default function BookingsPage() {
  return <Suspense fallback={<Loading />}><Bookings /></Suspense>;
}

/** Appointments for a shop that sells services (a haircut, make-up...): requests to confirm, the services, the opening hours and the rules. */
function Bookings() {
  const { t } = useI18n();
  const me = useMe();
  const canManage = me.canWrite("bookings");
  const title = useNavTitle(t("nav.bookings"));
  const [tab, setTab] = useSection<Tab>(["appointments", "calendar", "services", "team", "insights", "hours"], "appointments");
  const [newOpen, setNewOpen] = useState(false);
  const services = useApi<Service[]>("store/services");
  const summary = useApi<{ pending: number; next24: number; upcoming: number }>("store/bookings/summary");
  const [v, setV] = useState(0);   // bumps to refresh the list after an action
  return (
    <>
      <PageHeader eyebrow={t("navgrp.service")} title={title} subtitle={t("bk.subtitle")}
        actions={canManage && (services.data?.length ?? 0) > 0 ? <Button variant="ink" onClick={() => setNewOpen(true)}><Icon name="plus" className="h-4 w-4" />{t("bk.new")}</Button> : undefined} />
      <div className="space-y-5">
        <KpiStrip items={[
          { label: t("bk.kpi.pending"), value: <span className="tnum">{summary.data?.pending ?? 0}</span>, icon: "clock", tone: "amber" },
          { label: t("bk.kpi.next24"), value: <span className="tnum">{summary.data?.next24 ?? 0}</span>, icon: "calendar", tone: "brand" },
          { label: t("bk.kpi.upcoming"), value: <span className="tnum">{summary.data?.upcoming ?? 0}</span>, icon: "check", tone: "green" },
        ]} />
        <Tabs tabs={[{ key: "appointments" as Tab, label: t("bk.tab.appointments") }, { key: "calendar" as Tab, label: t("cal.tab") }, { key: "services" as Tab, label: t("bk.tab.services") }, { key: "team" as Tab, label: t("team.tab") }, { key: "insights" as Tab, label: t("ins.tab") }, { key: "hours" as Tab, label: t("bk.tab.hours") }]} value={tab} onChange={setTab} />
        {tab === "team" && <Team services={services.data ?? []} canManage={canManage} />}
        {tab === "insights" && <Insights />}
        {tab === "calendar" && <Calendar key={v} canManage={canManage} onChanged={() => void summary.reload()} />}
        {tab === "appointments" && <Appointments key={v} canManage={canManage} services={services.data ?? []} onChanged={() => void summary.reload()} />}
        {tab === "services" && <Services services={services.data ?? []} canManage={canManage} onChanged={() => { void services.reload(); setV(v + 1); }} />}
        {tab === "hours" && <HoursAndRules canManage={canManage} />}
      </div>
      {newOpen && <NewBooking open onClose={() => setNewOpen(false)} services={services.data ?? []} onDone={() => { setNewOpen(false); void summary.reload(); setV(v + 1); setTab("appointments"); }} />}
    </>
  );
}
