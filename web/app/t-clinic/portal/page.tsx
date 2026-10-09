"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Empty, ErrorText, Loading, PageHeader, StatusBadge } from "@/components/ui";
import { dateTime } from "@/lib/format";
import Link from "next/link";

type Place = { appointmentId: string; queueNumber: number; called: boolean; doctorName: string; serviceName: string; aheadOfYou: number; doctorBusy: boolean };
type Appt = { id: string; startAt: string; status: string; doctorName: string; serviceName: string; branchName: string; paymentStatus: string; paymentMethod: string; priceMinor?: number; patientName?: string };

type ClinicInfo = { clinicName: string; phone?: string; addressText?: string; queueCount?: number; doctors: { id: string; displayName: string; publicPhone?: string }[] };

/** Where the clinic is, how to call it and how long the line is right now. */
function ClinicCard() {
  const t = useI18n().t;
  const { data, reload } = useApi<ClinicInfo>("clinic/public/profile");
  useEffect(() => { const h = setInterval(() => { void reload(); }, 15_000); return () => clearInterval(h); }, [reload]);
  if (!data) return null;
  const phones = [data.phone, ...data.doctors.map((d) => d.publicPhone)].filter((x): x is string => !!x);
  return (
    <div className="mb-5 grid gap-3 rounded-2xl border border-slate-200/80 bg-white p-4 text-sm shadow-soft sm:grid-cols-3">
      <div><p className="text-xs text-slate-500">{t("clinic.address")}</p><p className="font-medium">{data.addressText || "-"}</p></div>
      <div><p className="text-xs text-slate-500">{t("clinic.phone")}</p>{phones.length === 0 ? "-" : phones.map((x) => <a key={x} href={`tel:${x}`} dir="ltr" className="block font-medium text-brand">{x}</a>)}</div>
      <div><p className="text-xs text-slate-500">{t("clinic.queueNow")}</p><p className="text-2xl font-extrabold text-brand">{data.queueCount ?? 0}</p></div>
    </div>
  );
}

function Inner() {
  const { t, locale, timezone } = useI18n();
  const booked = useSearchParams().get("booked");
  const { data, loading, error, reload } = useApi<Page<Appt>>("portal/appointments?pageSize=50");
  const place = useApi<Place[]>("portal/queue");
  useEffect(() => { const h = setInterval(() => { void place.reload(); }, 10_000); return () => clearInterval(h); }, [place.reload]); // eslint-disable-line react-hooks/exhaustive-deps
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
      <ClinicCard />
      {place.data?.map((p) => (
        <div key={p.appointmentId} className={`mb-4 rounded-2xl border-2 p-5 text-center ${p.called ? "border-emerald-500 bg-emerald-50" : "border-brand/40 bg-white"}`}>
          <p className="text-sm text-slate-500">{p.doctorName} · {p.serviceName}</p>
          {p.called ? <p className="mt-1 text-2xl font-bold text-emerald-700">{t("queue.yourTurn")}</p> : (
            <>
              <p className="mt-1 text-4xl font-bold">#{p.queueNumber}</p>
              <p className="mt-1 text-slate-600">{p.aheadOfYou === 0 ? t("queue.youAreNext") : t("queue.aheadOfYou", { n: p.aheadOfYou })}</p>
            </>
          )}
        </div>
      ))}
      {booked && <div className="mb-4"><Alert tone="green">{t("portal.booked")}</Alert></div>}
      <ErrorText error={error ?? cancel.error} />
      {loading && !data ? <Loading /> : (
        <div className="space-y-6">
          <section><h2 className="mb-2 font-medium">{t("portal.upcoming")}</h2>{upcoming.length === 0 ? <Empty>{t("portal.noUpcoming")}</Empty> : <ul className="space-y-2">{upcoming.map((a) => row(a, true))}</ul>}</section>
          {past.length > 0 && <section><h2 className="mb-2 font-medium">{t("portal.past")}</h2><ul className="space-y-2">{past.map((a) => row(a, false))}</ul></section>}
        </div>
      )}
    </>
  );
}

export default function Portal() {
  return <Suspense><Inner /></Suspense>;
}
