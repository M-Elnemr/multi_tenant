"use client";

import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { errorMessage } from "@/lib/error-text";
import { money } from "@/lib/format";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import type { LawyerSite } from "@/app/t-lawyer/(site)/types";

export type Done = { code: string; guestName: string; startsAt: string; status: string; serviceName?: string };
export type Svc = LawyerSite["services"][number];

/**
 * Booking logic shared by the five templates (data and booking may be shared, the look may not).
 * `done` is set ONLY from the backend's reply to POST lawyer/public/bookings; days and slots come only from lawyer/public/availability and /slots.
 */
export function useLnBooking(services: Svc[]) {
  const { t, locale, currency } = useI18n();
  const sp = useSearchParams();
  const fe = useFieldErrors();
  const wanted = sp.get("service");
  const [service, setService] = useState<string | null>(services.find((s) => s.id === wanted)?.id ?? services[0]?.id ?? null);
  const days = useApi<{ date: string; free: number }[]>(`lawyer/public/availability?days=21${service ? `&serviceId=${service}` : ""}`);
  const [day, setDay] = useState<string | null>(null);
  const firstFree = days.data?.find((d) => d.free > 0)?.date ?? null;
  const chosenDay = day ?? firstFree;
  const slots = useApi<string[]>(chosenDay ? `lawyer/public/slots?date=${chosenDay}${service ? `&serviceId=${service}` : ""}` : null);
  const [time, setTime] = useState<string | null>(null);
  const [f, setF] = useState({ name: "", phone: "", topic: "" });
  const [done, setDone] = useState<Done | null>(null);
  useEffect(() => { if (done) window.scrollTo({ top: 0, behavior: "smooth" }); }, [done]);
  const intl = locale === "ar" ? "ar-EG" : "en-GB";
  const svc = services.find((s) => s.id === service) ?? null;
  const send = useAction(async () => {
    if (!fe.check({ startsAt: !time && t("lws.pickTime"), name: f.name.trim().length < 2 && t("srv.Write your name"), phone: f.phone.replace(/\D/g, "").length < 8 && t("srv.Write a phone number we can call") })) return;
    try { setDone(await api<Done>("lawyer/public/bookings", { body: { serviceId: service, startsAt: time, name: f.name.trim(), phone: f.phone.trim(), topic: f.topic.trim() } })); }
    catch (e) { if (!fe.fromError(e)) throw e; if (fe.err("startsAt")) { setTime(null); await slots.reload(); } }
  });
  const fmtDay = (d: string, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(intl, o).format(new Date(`${d}T00:00:00`));
  return {
    t, locale, currency, intl, fe, service, svc, pickService: (id: string) => { setService(id); setDay(null); setTime(null); },
    days, chosenDay, pickDay: (d: string) => { setDay(d); setTime(null); }, firstFree, slots, time, pickTime: (s: string) => { setTime(s); fe.clear("startsAt"); },
    f, setField: (k: "name" | "phone" | "topic", v: string) => { setF((x) => ({ ...x, [k]: v })); fe.clear(k); },
    done, send, fmtDay,
    fmtTime: (s: string) => new Intl.DateTimeFormat(intl, { hour: "numeric", minute: "2-digit" }).format(new Date(s)),
    fmtWhen: (s: string, long = true) => new Date(s).toLocaleString(intl, { weekday: long ? "long" : "short", day: "numeric", month: long ? "long" : "short", hour: "numeric", minute: "2-digit" }),
    price: (s: Svc | null) => (s && s.priceMinor != null ? (s.priceMinor === 0 ? t("lws.free") : money(s.priceMinor, currency, locale)) : null),
    errorText: send.error && !fe.any ? errorMessage(t, locale, send.error) : null,
    loadError: days.error ? errorMessage(t, locale, days.error) : slots.error ? errorMessage(t, locale, slots.error) : null,
    reloadTimes: async () => { await days.reload(); await slots.reload(); },
  };
}

type B = { code: string; guestName: string; startsAt: string; status: string; decisionNote?: string; serviceName?: string };
export const STATUS_TONE: Record<string, "wait" | "ok" | "bad" | "off"> = { PENDING: "wait", APPROVED: "ok", DONE: "ok", DENIED: "bad", CANCELLED: "off", NO_SHOW: "off" };

/** Look-up and cancel of an existing booking by code + phone (the backend decides what is shown). */
export function useLnTrack() {
  const { t, locale } = useI18n();
  const [code, setCode] = useState(useSearchParams().get("code") ?? "");
  const [phone, setPhone] = useState("");
  const [b, setB] = useState<B | null>(null);
  const find = useAction(async () => setB(await api<B>("lawyer/public/bookings/track", { body: { code, phone } })));
  const cancel = useAction(async () => setB(await api<B>("lawyer/public/bookings/cancel", { body: { code, phone } })));
  const when = (s: string) => new Date(s).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "long", day: "numeric", month: "long", hour: "numeric", minute: "2-digit" });
  return { t, code, setCode, phone, setPhone, b, find, cancel, when, findError: find.error ? errorMessage(t, locale, find.error) : null, cancelError: cancel.error ? errorMessage(t, locale, cancel.error) : null };
}
