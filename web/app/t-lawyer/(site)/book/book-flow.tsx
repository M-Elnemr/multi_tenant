"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText } from "@/components/ui";
import type { LawyerSite } from "../types";

type Done = { code: string; guestName: string; startsAt: string; status: string; serviceName?: string };

/** Service → day → time → name & phone → done (with a code to follow the request). */
export function BookFlow({ services }: { services: LawyerSite["services"] }) {
  const { t, locale, currency } = useI18n();
  const sp = useSearchParams();
  const fe = useFieldErrors();
  const [service, setService] = useState<string | null>(sp.get("service") ?? services[0]?.id ?? null);
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
  const svc = services.find((s) => s.id === service);
  const send = useAction(async () => {
    if (!fe.check({ startsAt: !time && t("lws.pickTime"), name: f.name.trim().length < 2 && t("srv.Write your name"), phone: f.phone.replace(/\D/g, "").length < 8 && t("srv.Write a phone number we can call") })) return;
    try { setDone(await api<Done>("lawyer/public/bookings", { body: { serviceId: service, startsAt: time, name: f.name.trim(), phone: f.phone.trim(), topic: f.topic.trim() } })); }
    catch (e) { if (!fe.fromError(e)) throw e; if (fe.err("startsAt")) { setTime(null); await slots.reload(); } }
  });

  if (done) return (
    <div className="lw-card lw-reveal mt-8 overflow-hidden">
      <div className="lw-hero p-8 text-center">
        <p className="text-5xl">{done.status === "APPROVED" ? "✅" : "📩"}</p>
        <h2 className="lw-display mt-3 text-3xl font-bold">{done.status === "APPROVED" ? t("lws.doneApproved") : t("lws.donePending")}</h2>
        <p className="mt-2 opacity-85">{new Date(done.startsAt).toLocaleString(intl, { weekday: "long", day: "numeric", month: "long", hour: "numeric", minute: "2-digit" })}</p>
      </div>
      <div className="space-y-4 p-8 text-center">
        <p style={{ color: "var(--lw-mute)" }}>{t("lws.yourCode")}</p>
        <p className="lw-display text-4xl font-bold tracking-[0.25em]" dir="ltr">{done.code}</p>
        <p className="text-sm" style={{ color: "var(--lw-mute)" }}>{t("lws.codeHint")}</p>
        <div className="flex flex-wrap justify-center gap-3"><Link href={`/booking?code=${done.code}`} className="lw-btn">{t("lws.track")}</Link><Link href="/" className="lw-btn lw-btn-ghost">{t("lws.backHome")}</Link></div>
      </div>
    </div>
  );

  const step = (n: number, title: string) => <h2 className="flex items-center gap-3 text-lg font-bold"><span className="grid h-8 w-8 place-items-center rounded-full text-sm" style={{ background: "var(--lw-accent)", color: "var(--lw-accent-ink)" }}>{n}</span>{title}</h2>;
  return (
    <form onSubmit={(e) => { e.preventDefault(); void send.run(); }} className="mt-8 grid gap-6 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
      <div className="min-w-0 space-y-6">
        {services.length > 1 && (
          <div className="lw-card p-6">
            {step(1, t("lws.chooseService"))}
            <div className="mt-4 grid gap-3 sm:grid-cols-2">{services.map((s) => (
              <button key={s.id} type="button" onClick={() => { setService(s.id); setDay(null); setTime(null); }} aria-pressed={service === s.id} className="lw-slot p-4 text-start" >
                <span className="block font-bold">{s.name}</span>
                <span className="mt-1 block text-xs font-semibold opacity-75">{t("lws.minutes", { n: s.durationMinutes })}{s.priceMinor != null ? ` · ${s.priceMinor === 0 ? t("lws.free") : money(s.priceMinor, currency, locale)}` : ""}</span>
              </button>
            ))}</div>
          </div>
        )}
        <div className="lw-card min-w-0 overflow-hidden p-6" data-field="startsAt">
          {step(services.length > 1 ? 2 : 1, t("lws.chooseTime"))}
          <div className="-mx-1 mt-4 flex gap-2 overflow-x-auto px-1 pb-2">
            {days.data?.map((d) => {
              const dt = new Date(`${d.date}T00:00:00`);
              return <button key={d.date} type="button" disabled={d.free === 0} aria-pressed={chosenDay === d.date} onClick={() => { setDay(d.date); setTime(null); }} className="lw-day">
                <span className="block text-[11px] font-bold opacity-70">{new Intl.DateTimeFormat(intl, { weekday: "short" }).format(dt)}</span>
                <span className="lw-display block text-2xl font-bold leading-tight">{dt.getDate()}</span>
                <span className="block text-[11px] font-bold opacity-70">{new Intl.DateTimeFormat(intl, { month: "short" }).format(dt)}</span>
              </button>;
            })}
            {days.loading && !days.data && <p className="py-6 text-sm" style={{ color: "var(--lw-mute)" }}>…</p>}
          </div>
          <div className="mt-4 grid grid-cols-3 gap-2 sm:grid-cols-4">
            {slots.data?.map((s) => <button key={s} type="button" aria-pressed={time === s} onClick={() => { setTime(s); fe.clear("startsAt"); }} className="lw-slot" dir="ltr">{new Intl.DateTimeFormat(intl, { hour: "numeric", minute: "2-digit" }).format(new Date(s))}</button>)}
          </div>
          {slots.data && slots.data.length === 0 && <p className="mt-3 text-sm" style={{ color: "var(--lw-mute)" }}>{t("lws.noTimes")}</p>}
          {days.data && !firstFree && <p className="mt-3 text-sm" style={{ color: "var(--lw-mute)" }}>{t("lws.fullyBooked")}</p>}
          {fe.err("startsAt") && <p className="mt-3 text-sm font-semibold text-red-600">{fe.err("startsAt")}</p>}
        </div>
        <div className="lw-card space-y-4 p-6">
          {step(services.length > 1 ? 3 : 2, t("lws.yourDetails"))}
          <label className={`block space-y-1.5 ${fe.err("name") ? "lw-field-error" : ""}`} data-field="name"><span className="text-sm font-bold">{t("lws.name")}</span><input className="lw-input" value={f.name} onChange={(e) => { setF({ ...f, name: e.target.value }); fe.clear("name"); }} autoComplete="name" />{fe.err("name") && <span className="text-xs font-semibold text-red-600">{fe.err("name")}</span>}</label>
          <label className={`block space-y-1.5 ${fe.err("phone") ? "lw-field-error" : ""}`} data-field="phone"><span className="text-sm font-bold">{t("lws.phone")}</span><input className="lw-input" dir="ltr" inputMode="tel" autoComplete="tel" value={f.phone} onChange={(e) => { setF({ ...f, phone: e.target.value }); fe.clear("phone"); }} placeholder="01xxxxxxxxx" />{fe.err("phone") && <span className="text-xs font-semibold text-red-600">{fe.err("phone")}</span>}</label>
          <label className="block space-y-1.5" data-field="topic"><span className="text-sm font-bold">{t("lws.topic")}</span><textarea className="lw-input" rows={3} value={f.topic} onChange={(e) => setF({ ...f, topic: e.target.value })} placeholder={t("lws.topicPh")} /></label>
          <p className="text-xs" style={{ color: "var(--lw-mute)" }}>🔒 {t("lws.private")}</p>
        </div>
      </div>
      <aside className="lg:sticky lg:top-24 lg:self-start">
        <div className="lw-card overflow-hidden">
          <div className="lw-hero p-5"><p className="text-xs font-bold uppercase tracking-widest opacity-70">{t("lws.summary")}</p><p className="lw-display mt-1 text-xl font-bold">{svc?.name ?? t("lws.session")}</p></div>
          <dl className="space-y-3 p-5 text-sm">
            <div className="flex justify-between gap-2"><dt style={{ color: "var(--lw-mute)" }}>{t("lws.when")}</dt><dd className="font-bold">{time ? new Date(time).toLocaleString(intl, { weekday: "short", day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }) : "—"}</dd></div>
            {svc && <div className="flex justify-between gap-2"><dt style={{ color: "var(--lw-mute)" }}>{t("lws.duration")}</dt><dd className="font-bold">{t("lws.minutes", { n: svc.durationMinutes })}</dd></div>}
            {svc?.priceMinor != null && <div className="flex justify-between gap-2"><dt style={{ color: "var(--lw-mute)" }}>{t("lws.fee")}</dt><dd className="font-bold">{svc.priceMinor === 0 ? t("lws.free") : money(svc.priceMinor, currency, locale)}</dd></div>}
            {svc?.priceMinor ? <p className="rounded-xl p-3 text-xs" style={{ background: "color-mix(in srgb, var(--lw-accent) 10%, transparent)" }}>{t("lws.payAtOffice")}</p> : null}
          </dl>
          <div className="p-5 pt-0">
            {!fe.any && <ErrorText error={send.error} />}
            <button type="submit" disabled={send.loading} className="lw-btn mt-2 w-full">{send.loading ? "…" : t("lws.confirm")}</button>
          </div>
        </div>
      </aside>
    </form>
  );
}
