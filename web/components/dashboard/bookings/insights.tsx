"use client";

import { useState } from "react";
import { money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { KpiStrip, Loading, Segmented } from "@/components/ui";

type A = { totalAppointments: number; completed: number; cancelled: number; noShow: number; open: number; byAssistant: number; byWebsite: number; byOwner: number; serviceRevenueMinor: number; productOrders: number; productRevenueMinor: number; noShowRate: number | null; assistantConversion: number | null; chats: number };
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** What the business did, with services and products kept apart: a service is earned only when the appointment is completed. */
export function Insights() {
  const { t, locale, currency, timezone } = useI18n();
  const [days, setDays] = useState("30");
  const today = new Intl.DateTimeFormat("en-CA", { timeZone: timezone }).format(new Date());
  const from = (() => { const d = new Date(today + "T00:00:00"); d.setDate(d.getDate() - Number(days)); return iso(d); })();
  const { data } = useApi<A>(`store/bookings/analytics?from=${from}&to=${today}`);
  const pct = (v: number | null) => (v == null ? "–" : `${v}%`);
  return (
    <div className="space-y-5">
      <Segmented value={days} onChange={setDays} tabs={[{ key: "7", label: t("ins.d7") }, { key: "30", label: t("ins.d30") }, { key: "90", label: t("ins.d90") }]} />
      {!data ? <Loading variant="table" /> : (
        <>
          <KpiStrip items={[
            { label: t("ins.total"), value: data.totalAppointments, icon: "calendar" },
            { label: t("ins.completed"), value: data.completed, icon: "check", tone: "green" },
            { label: t("ins.cancelled"), value: data.cancelled, icon: "close" },
            { label: t("ins.noshow"), value: data.noShow, hint: t("ins.noshowRate", { r: pct(data.noShowRate) }), icon: "alert" },
          ]} />
          <KpiStrip items={[
            { label: t("ins.serviceRevenue"), value: money(data.serviceRevenueMinor, currency, locale), hint: t("ins.serviceRevenueHint"), icon: "wallet" },
            { label: t("ins.productRevenue"), value: money(data.productRevenueMinor, currency, locale), hint: t("ins.productOrders", { n: String(data.productOrders) }), icon: "bag" },
            { label: t("ins.byAssistant"), value: data.byAssistant, hint: t("ins.conversion", { r: pct(data.assistantConversion) }), icon: "sparkles" },
            { label: t("ins.byOwner"), value: data.byOwner, hint: t("ins.byWebsite", { n: String(data.byWebsite) }), icon: "users" },
          ]} />
        </>
      )}
    </div>
  );
}
