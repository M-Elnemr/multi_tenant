"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Empty, ErrorText, Loading, PageHeader, StatusBadge } from "@/components/ui";
import { dateTime } from "@/lib/format";
import Link from "next/link";

type Appt = { id: string; startAt: string; status: string; doctorName: string; serviceName: string; branchName: string; paymentStatus: string; paymentMethod: string; priceMinor?: number; patientName?: string };

function Inner() {
  const { t, locale, timezone } = useI18n();
  const booked = useSearchParams().get("booked");
  const { data, loading, error, reload } = useApi<Page<Appt>>("portal/appointments?pageSize=50");
  const cancel = useAction(async (id: string) => { await api(`portal/appointments/${id}/cancel`, { body: {} }); await reload(); });
  const [now] = useState(() => Date.now());
  const upcoming = data?.data.filter((a) => new Date(a.startAt).getTime() >= now - 3600_000 && !["CANCELLED", "REJECTED", "COMPLETED", "NO_SHOW"].includes(a.status)) ?? [];
  const past = data?.data.filter((a) => !upcoming.includes(a)).reverse() ?? [];
  const row = (a: Appt, actions: boolean) => (
    <li key={a.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-white p-4 text-sm">
      <div><p className="font-medium">{dateTime(a.startAt, locale, timezone)}</p><p className="text-slate-600">{a.serviceName} · {a.doctorName} · {a.branchName}</p></div>
      <div className="flex items-center gap-2">
        <StatusBadge status={a.status} />
        {actions && a.status === "REQUESTED" && a.paymentStatus === "PENDING" && <Link href={`/portal/pay?kind=appointment&id=${a.id}&amount=${a.priceMinor ?? 0}`} className="rounded-lg bg-brand px-3 py-1.5 text-xs text-white">{t("orders.payNow")}</Link>}
        {actions && ["REQUESTED", "PENDING_CONFIRMATION", "CONFIRMED"].includes(a.status) && <Button size="sm" variant="ghost" onClick={() => cancel.run(a.id)}>{t("orders.cancel")}</Button>}
      </div>
    </li>
  );
  return (
    <>
      <PageHeader title={t("portal.appointments")} />
      {booked && <div className="mb-4"><Alert tone="green">{t("portal.booked")}</Alert></div>}
      <ErrorText error={error ?? cancel.error} />
      {loading && !data ? <Loading /> : (
        <div className="space-y-6">
          <section><h2 className="mb-2 font-medium">{t("portal.upcoming")}</h2>{upcoming.length === 0 ? <Empty>{t("portal.noUpcoming")} <Link href="/book" className="text-brand underline">{t("clinic.book")}</Link></Empty> : <ul className="space-y-2">{upcoming.map((a) => row(a, true))}</ul>}</section>
          {past.length > 0 && <section><h2 className="mb-2 font-medium">{t("portal.past")}</h2><ul className="space-y-2">{past.map((a) => row(a, false))}</ul></section>}
        </div>
      )}
    </>
  );
}

export default function Portal() {
  return <Suspense><Inner /></Suspense>;
}
