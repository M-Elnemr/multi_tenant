"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { dayKey, money, timeOnly } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Loading, PageHeader, Select } from "@/components/ui";

type PublicProfile = {
  bookingEnabled: boolean; cardEnabled: boolean; cashEnabled: boolean;
  doctors: { id: string; displayName: string }[];
  services: { id: string; name: string; durationMinutes: number; priceMinor?: number; currency: string }[];
  branches: { id: string; name: string }[];
};
type PatientRef = { id: string; firstName: string; lastName: string; patientCode: string };
type Booked = { id: string; status: string; checkoutUrl?: string; priceMinor?: number };

/** Choose service, doctor, branch, day and time. Only free slots are ever shown; the server re-checks when you confirm. */
export default function Book() {
  const { t, locale, timezone } = useI18n();
  const router = useRouter();
  const { me, ready } = useMe();
  const profile = useApi<PublicProfile>("clinic/public/profile");
  const patients = useApi<PatientRef[]>(me?.roles.includes("PATIENT") ? "portal/patients" : null);
  const p = profile.data;

  // Choices default to the first option and only store what the visitor actively changed.
  const [pick, setPick] = useState({ serviceId: "", doctorId: "", branchId: "", patientId: "", payment: "" });
  const [startMs] = useState(() => Date.now());
  const [picked, setPicked] = useState<string | null>(null);
  const [slotChoice, setSlotChoice] = useState<{ key: string; value: string }>({ key: "", value: "" });
  const [note, setNote] = useState("");

  const serviceId = pick.serviceId || p?.services[0]?.id || "";
  const doctorId = pick.doctorId || p?.doctors[0]?.id || "";
  const branchId = pick.branchId || p?.branches[0]?.id || "";
  const patientId = pick.patientId || patients.data?.[0]?.id || "";
  const payment = pick.payment || (p?.cashEnabled === false ? "CARD" : "CASH_AT_CLINIC");

  const days = Array.from({ length: 14 }, (_, i) => dayKey(new Date(startMs + i * 86400000), timezone));
  // Days the doctor does not work (for example the weekend) are dimmed and cannot be picked; the page opens on the first working day after today.
  const workdays = useApi<{ weekdays: number[] }>(doctorId && branchId ? `clinic/public/working-days?doctorId=${doctorId}&branchId=${branchId}` : null);
  const works = (d: string) => { const n = new Date(d + "T12:00:00Z").getUTCDay(); return !workdays.data || workdays.data.weekdays.includes(n === 0 ? 7 : n); };
  const noHours = !!workdays.data && workdays.data.weekdays.length === 0;
  const date = picked && works(picked) ? picked : (days.slice(1).find(works) ?? days[1]);
  const slotsKey = `${serviceId}|${doctorId}|${branchId}|${date}`;
  const slots = useApi<string[]>(serviceId && doctorId && branchId ? `clinic/public/slots?doctorId=${doctorId}&branchId=${branchId}&serviceId=${serviceId}&date=${date}` : null);
  const slot = slotChoice.key === slotsKey ? slotChoice.value : "";

  const service = p?.services.find((s) => s.id === serviceId);
  const book = useAction(async () => {
    const r = await api<Booked>("portal/appointments", { body: { patientId, doctorId, branchId, serviceId, startAt: slot, paymentMethod: payment, patientNote: note || undefined } });
    if (r.checkoutUrl) {
      if (r.checkoutUrl.startsWith("http")) window.location.href = r.checkoutUrl;
      else router.replace(`/portal/pay?kind=appointment&id=${r.id}&amount=${r.priceMinor ?? 0}`);
    } else router.replace("/portal?booked=1");
  });

  if (!ready || profile.loading) return <Loading />;
  if (!p?.bookingEnabled) return <Alert tone="amber">{t("book.disabled")}</Alert>;
  const isPatient = !!me?.roles.includes("PATIENT");

  return (
    <>
      <PageHeader title={t("clinic.book")} />
      {!me && <div className="mb-4"><Alert tone="blue">{t("book.loginFirst")} <Link href="/login?next=/book" className="font-medium underline">{t("nav.login")}</Link></Alert></div>}
      {me && !isPatient && <div className="mb-4"><Alert tone="amber">{t("book.noFile")} <Link href="/link" className="font-medium underline">{t("link.title")}</Link></Alert></div>}
      <div className="grid gap-5 lg:grid-cols-3">
        <Card className="space-y-4 lg:col-span-1">
          <Field label={t("book.service")}><Select value={serviceId} onChange={(e) => setPick({ ...pick, serviceId: e.target.value })}>{p.services.map((s) => <option key={s.id} value={s.id}>{s.name} · {s.durationMinutes} {t("clinic.minutes")}{s.priceMinor ? ` · ${money(s.priceMinor, s.currency, locale)}` : ""}</option>)}</Select></Field>
          <Field label={t("book.doctor")}><Select value={doctorId} onChange={(e) => setPick({ ...pick, doctorId: e.target.value })}>{p.doctors.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select></Field>
          {p.branches.length > 1 && <Field label={t("book.branch")}><Select value={branchId} onChange={(e) => setPick({ ...pick, branchId: e.target.value })}>{p.branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></Field>}
          {isPatient && (patients.data?.length ?? 0) > 1 && <Field label={t("book.forWhom")}><Select value={patientId} onChange={(e) => setPick({ ...pick, patientId: e.target.value })}>{patients.data?.map((x) => <option key={x.id} value={x.id}>{x.firstName} {x.lastName}</option>)}</Select></Field>}
        </Card>
        <Card className="space-y-4 lg:col-span-2">
          <div>
            <p className="mb-2 text-sm font-medium">{t("book.day")}</p>
            <div className="flex gap-2 overflow-x-auto pb-1">{days.map((d) => (
              <button key={d} onClick={() => setPicked(d)} disabled={!works(d)} title={works(d) ? undefined : t("book.dayOff")} className={`shrink-0 rounded-lg border px-3 py-2 text-sm ${d === date ? "border-brand bg-brand text-white" : works(d) ? "bg-white hover:border-brand" : "cursor-not-allowed bg-slate-50 text-slate-300 line-through"}`}>
                {new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" }).format(new Date(d + "T12:00:00Z"))}
              </button>
            ))}</div>
          </div>
          <div>
            <p className="mb-2 text-sm font-medium">{t("book.time")}</p>
            {slots.loading ? <Loading /> : (slots.data?.length ?? 0) === 0 ? <p className="rounded-lg bg-slate-50 p-4 text-sm text-slate-500">{noHours ? t("book.noWorkingDays") : t("book.noSlots")}</p> : (
              <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">{slots.data?.map((s) => <button key={s} onClick={() => setSlotChoice({ key: slotsKey, value: s })} className={`rounded-lg border px-2 py-2 text-sm ${slot === s ? "border-brand bg-brand text-white" : "bg-white hover:border-brand"}`}>{timeOnly(s, locale, timezone)}</button>)}</div>
            )}
          </div>
          {slot && (
            <div className="space-y-3 border-t pt-4">
              <Field label={t("book.payment")}>
                <Select value={payment} onChange={(e) => setPick({ ...pick, payment: e.target.value })}>
                  {p.cashEnabled && <option value="CASH_AT_CLINIC">{t("pay.CASH_AT_CLINIC")}</option>}
                  {p.cardEnabled && service?.priceMinor ? <option value="CARD">{t("pay.CARD")}</option> : null}
                </Select>
              </Field>
              <Field label={t("book.note")}><textarea rows={2} className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" value={note} onChange={(e) => setNote(e.target.value)} /></Field>
              <ErrorText error={book.error} />
              <Button className="w-full" loading={book.loading} disabled={!isPatient || !patientId} onClick={() => book.run()}>{t("book.confirm")}</Button>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
