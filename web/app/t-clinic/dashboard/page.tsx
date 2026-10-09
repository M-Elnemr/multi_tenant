"use client";

import Link from "next/link";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Loading, PageHeader, Stat, StatusBadge, Table, Td } from "@/components/ui";
import { money, timeOnly } from "@/lib/format";

type Dash = {
  today: { id: string; startAt: string; status: string; queueNumber?: number; patientName: string; patientCode: string; doctorName: string; serviceName: string }[];
  pendingRequests: number; checkedIn: number; pendingLabReviews: number; noShowsThisMonth: number; completedThisMonth: number; revenueThisMonthMinor: number; newPatientsThisMonth: number; unpaidVisitsCount: number; unpaidVisitsMinor: number;
};

export default function ClinicOverview() {
  const { t, locale, timezone, currency } = useI18n();
  const { data, loading, error } = useApi<Dash>("clinic/dashboard");
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  const stats = [[t("clinic.today"), data.today.length, "calendar", "brand"], [t("clinic.pending"), data.pendingRequests, "clock", "amber"], [t("clinic.waiting"), data.checkedIn, "queue", "brand"], [t("clinic.labReviews"), data.pendingLabReviews, "file", "amber"], [t("clinic.revenueMonth"), money(data.revenueThisMonthMinor, currency, locale), "billing", "green"], [t("clinic.noShows"), data.noShowsThisMonth, "close", "red"], [t("clinic.completed"), data.completedThisMonth, "check", "green"], [t("clinic.newPatients"), data.newPatientsThisMonth, "patients", "brand"], [t("clinic.unpaid"), `${money(data.unpaidVisitsMinor, currency, locale)} (${data.unpaidVisitsCount})`, "coupon", "red"]] as const;
  return (
    <>
      <PageHeader title={t("nav.overview")} />
      <div className="stagger mb-6 grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-5">{stats.map(([l, v, icon, tone], i) => <div key={String(l)} style={{ "--i": i } as React.CSSProperties}><Stat label={String(l)} value={v} icon={icon} tone={tone} /></div>)}</div>
      <h2 className="mb-3 text-lg font-bold">{t("clinic.todaySchedule")}</h2>
      <Table head={[t("book.time"), t("clinic.patient"), t("book.service"), t("book.doctor"), t("admin.status")]}>
        {data.today.map((a) => (
          <tr key={a.id}><Td className="font-mono">{timeOnly(a.startAt, locale, timezone)}</Td><Td><span className="font-medium">{a.patientName}</span><div className="font-mono text-xs text-slate-500">{a.patientCode}</div></Td><Td>{a.serviceName}</Td><Td>{a.doctorName}</Td><Td><StatusBadge status={a.status} />{a.queueNumber ? <span className="ms-2 text-xs">#{a.queueNumber}</span> : null}</Td></tr>
        ))}
      </Table>
      {data.today.length === 0 && <p className="mt-3 text-sm text-slate-500">{t("clinic.noToday")} <Link href="/dashboard/appointments" className="text-brand underline">{t("nav.appointments")}</Link></p>}
    </>
  );
}
