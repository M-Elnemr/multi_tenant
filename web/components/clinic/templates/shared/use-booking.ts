"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { dayKey } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import type { CnProfile } from "./kit";

type PatientRef = { id: string; firstName: string; lastName: string; patientCode: string };
type Booked = { id: string; status: string; checkoutUrl?: string; priceMinor?: number };
export type Confirmed = { id: string; status: string; doctorId: string; serviceId: string; branchId: string; startAt: string; patientId: string };

/**
 * The booking logic every cn-* template shares (data only, no visuals): same endpoints and rules as the original booking page.
 * Only free slots are listed; success is reported ONLY after the backend answered the booking call (`done`).
 */
export function useBooking(p: CnProfile) {
  const { timezone } = useI18n();
  const router = useRouter();
  const qs = useSearchParams();
  const { me, ready } = useMe();
  const patients = useApi<PatientRef[]>(me?.roles.includes("PATIENT") ? "portal/patients" : null);
  const [pick, setPick] = useState({ serviceId: "", doctorId: "", branchId: "", patientId: "", payment: "" });
  const [startMs] = useState(() => Date.now());
  const [picked, setPicked] = useState<string | null>(null);
  const [slotChoice, setSlotChoice] = useState<{ key: string; value: string }>({ key: "", value: "" });
  const [note, setNote] = useState("");
  const [done, setDone] = useState<Confirmed | null>(null);

  const qDoctor = qs.get("doctor"), qService = qs.get("service");
  const valid = (id: string | null, list: { id: string }[]) => (id && list.some((x) => x.id === id) ? id : "");
  const serviceId = pick.serviceId || valid(qService, p.services) || p.services[0]?.id || "";
  const doctorId = pick.doctorId || valid(qDoctor, p.doctors) || p.doctors[0]?.id || "";
  const branchId = pick.branchId || p.branches[0]?.id || "";
  const patientId = pick.patientId || patients.data?.[0]?.id || "";
  const payment = pick.payment || (p.cashEnabled === false ? "CARD" : "CASH_AT_CLINIC");

  const days = Array.from({ length: 14 }, (_, i) => dayKey(new Date(startMs + i * 86400000), timezone));
  const workdays = useApi<{ weekdays: number[] }>(doctorId && branchId ? `clinic/public/working-days?doctorId=${doctorId}&branchId=${branchId}` : null);
  const works = (d: string) => { const n = new Date(d + "T12:00:00Z").getUTCDay(); return !workdays.data || workdays.data.weekdays.includes(n === 0 ? 7 : n); };
  const noHours = !!workdays.data && workdays.data.weekdays.length === 0;
  const date = picked && works(picked) ? picked : (days.slice(1).find(works) ?? days[1]);
  const slotsKey = `${serviceId}|${doctorId}|${branchId}|${date}`;
  const slots = useApi<string[]>(serviceId && doctorId && branchId ? `clinic/public/slots?doctorId=${doctorId}&branchId=${branchId}&serviceId=${serviceId}&date=${date}` : null);
  const slot = slotChoice.key === slotsKey ? slotChoice.value : "";
  const service = p.services.find((s) => s.id === serviceId);
  const doctor = p.doctors.find((d) => d.id === doctorId);
  const branch = p.branches.find((b) => b.id === branchId);
  const isPatient = !!me?.roles.includes("PATIENT");

  const submit = useAction(async () => {
    const r = await api<Booked>("portal/appointments", { body: { patientId, doctorId, branchId, serviceId, startAt: slot, paymentMethod: payment, patientNote: note || undefined } });
    if (r.checkoutUrl) {
      if (r.checkoutUrl.startsWith("http")) window.location.href = r.checkoutUrl;
      else router.replace(`/portal/pay?kind=appointment&id=${r.id}&amount=${r.priceMinor ?? 0}`);
      return;
    }
    setDone({ id: r.id, status: r.status, doctorId, serviceId, branchId, startAt: slot, patientId });
  });

  return {
    me, ready, isPatient, patients: patients.data ?? [], serviceId, doctorId, branchId, patientId, payment, note, setNote,
    setService: (v: string) => setPick({ ...pick, serviceId: v }), setDoctor: (v: string) => setPick({ ...pick, doctorId: v }),
    setBranch: (v: string) => setPick({ ...pick, branchId: v }), setPatient: (v: string) => setPick({ ...pick, patientId: v }), setPayment: (v: string) => setPick({ ...pick, payment: v }),
    days, works, date, setDate: setPicked, noHours, slots: slots.data, slotsLoading: slots.loading, slotsError: slots.error, reloadSlots: slots.reload, slot, setSlot: (v: string) => setSlotChoice({ key: slotsKey, value: v }),
    service, doctor, branch, submit, done, canSubmit: isPatient && !!patientId && !!slot && !submit.loading,
    patientName: (id: string) => { const x = patients.data?.find((q) => q.id === id); return x ? `${x.firstName} ${x.lastName}` : ""; },
  };
}
export type Booking = ReturnType<typeof useBooking>;
