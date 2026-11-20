"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { PhoneInput } from "@/components/inputs";
import { ErrorText } from "@/components/ui";

type Svc = { id: string; name: string; description?: string | null; durationMinutes: number; priceMinor: number; currency: string };
export type BookConfig = { services: Svc[]; hours: { weekday: number; opens: string; closes: string }[]; autoConfirm: boolean; maxDaysAhead: number; timezone: string };
type Done = { bookingNumber: string; status: string; serviceName: string; date: string; time: string };

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** The shop's booking page: choose a service, a day and one of the free times, leave a name and a number. The shop confirms (or the time is confirmed at once when the shop chose that). */
export function BookClient({ cfg }: { cfg: BookConfig }) {
  const { t, locale } = useI18n();
  const [svc, setSvc] = useState(cfg.services[0]?.id ?? "");
  const [date, setDate] = useState("");
  const [time, setTime] = useState("");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [note, setNote] = useState("");
  const [done, setDone] = useState<Done | null>(null);
  const slots = useApi<string[]>(svc && date ? `shop/booking/slots?serviceId=${svc}&date=${date}` : null);
  const today = new Date();
  const max = new Date(today.getTime() + cfg.maxDaysAhead * 86400000);
  const chosen = cfg.services.find((s) => s.id === svc);
  const send = useAction(async () => { setDone(await api<Done>("shop/booking", { body: { serviceId: svc, date, time, name: name.trim(), phone, note: note.trim() || undefined } })); });
  const ready = svc && date && time && name.trim() && phone.replace(/\D/g, "").length >= 9;
  if (done) {
    return (
      <div className="mx-auto max-w-xl space-y-4">
        <div className="s-card space-y-3 p-6 text-center sm:p-8">
          <div className="mx-auto grid h-14 w-14 place-items-center rounded-full bg-emerald-100 text-2xl text-emerald-700">✓</div>
          <h1 className="text-2xl font-extrabold">{done.status === "CONFIRMED" ? t("book.doneConfirmed") : t("book.doneWaiting")}</h1>
          <p className="text-[var(--s-mute)]">{done.status === "CONFIRMED" ? t("book.doneConfirmedBody") : t("book.doneWaitingBody")}</p>
          <p className="rounded-2xl bg-[var(--s-soft)] p-4 font-bold">{done.serviceName}<br /><span className="tnum" dir="ltr">{done.date} · {done.time}</span><br /><span className="font-mono text-sm" dir="ltr">{done.bookingNumber}</span></p>
          <p className="text-sm text-[var(--s-mute)]">{t("book.keepNumber")}</p>
        </div>
      </div>
    );
  }
  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div><p className="s-eyebrow">{t("book.eyebrow")}</p><h1 className="mt-1 text-3xl font-extrabold">{t("book.title")}</h1><p className="mt-1 text-[var(--s-mute)]">{cfg.autoConfirm ? t("book.subAuto") : t("book.sub")}</p></div>
      <form onSubmit={(e) => { e.preventDefault(); if (ready) void send.run(); }} className="space-y-5">
        <fieldset className="s-card space-y-3 p-5">
          <legend className="px-1 text-sm font-extrabold">1. {t("book.pickService")}</legend>
          <div className="grid gap-2 sm:grid-cols-2">
            {cfg.services.map((s) => (
              <button key={s.id} type="button" aria-pressed={svc === s.id} onClick={() => { setSvc(s.id); setTime(""); }}
                className={`rounded-2xl border p-4 text-start transition ${svc === s.id ? "border-[var(--s-accent)] bg-[var(--s-soft)] ring-2 ring-[var(--s-accent)]/30" : "border-[var(--s-line)] hover:border-[var(--s-accent)]/50"}`}>
                <span className="block font-extrabold">{s.name}</span>
                {s.description && <span className="mt-0.5 line-clamp-2 block text-sm text-[var(--s-mute)]">{s.description}</span>}
                <span className="mt-2 flex items-center justify-between text-sm"><span className="text-[var(--s-mute)]">{s.durationMinutes} {t("bk.min")}</span><span className="font-extrabold tnum">{money(s.priceMinor, s.currency, locale)}</span></span>
              </button>
            ))}
          </div>
        </fieldset>
        <fieldset className="s-card space-y-3 p-5">
          <legend className="px-1 text-sm font-extrabold">2. {t("book.when")}</legend>
          <input type="date" className="s-input" dir="ltr" min={iso(today)} max={iso(max)} value={date} onChange={(e) => { setDate(e.target.value); setTime(""); }} aria-label={t("bk.date")} />
          {date && !slots.data && <div className="skeleton h-11 rounded-xl" />}
          {slots.data && slots.data.length === 0 && <p className="rounded-2xl bg-[var(--s-soft)] p-3 text-sm font-bold">{t("book.noTimes")}</p>}
          {slots.data && slots.data.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {slots.data.map((s) => <button key={s} type="button" aria-pressed={time === s} onClick={() => setTime(s)} className={`min-h-11 rounded-full border px-4 text-sm font-bold tnum transition ${time === s ? "border-[var(--s-accent)] bg-[var(--s-accent)] text-white" : "border-[var(--s-line)] hover:border-[var(--s-accent)]/60"}`}>{s}</button>)}
            </div>
          )}
        </fieldset>
        <fieldset className="s-card space-y-3 p-5">
          <legend className="px-1 text-sm font-extrabold">3. {t("book.you")}</legend>
          <label className="block space-y-1.5"><span className="text-sm font-bold">{t("bk.customerName")}</span><input className="s-input" value={name} onChange={(e) => setName(e.target.value)} autoComplete="given-name" /></label>
          <label className="block space-y-1.5"><span className="text-sm font-bold">{t("book.phone")}</span><PhoneInput value={phone} onValue={setPhone} required /></label>
          <label className="block space-y-1.5"><span className="text-sm font-bold">{t("bk.note")} ({t("ch.optional")})</span><textarea className="s-input" rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        </fieldset>
        <ErrorText error={send.error} />
        <div className="sticky bottom-3 z-10">
          <button className="s-btn w-full" disabled={!ready || send.loading}>{send.loading ? "…" : chosen && date && time ? t("book.confirmBtn", { service: chosen.name, date, time }) : t("book.btn")}</button>
        </div>
      </form>
    </div>
  );
}
