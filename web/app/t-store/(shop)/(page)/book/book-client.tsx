"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { fileUrl } from "@/lib/media";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { PhoneInput } from "@/components/inputs";
import { ErrorText } from "@/components/ui";

type Addon = { id: string; name: string; priceMinor: number; durationMinutes: number };
type Svc = { id: string; name: string; description?: string | null; durationMinutes: number; priceMinor: number; currency: string; categoryName?: string | null; shortDescription?: string | null; priceBeforeMinor?: number | null;
  locationMode?: "SHOP" | "HOME" | "BOTH"; serviceArea?: string | null; instructions?: string | null; cancellationPolicy?: string | null; imageFileId?: string | null; requiredFields?: { key: string; label: string; required?: boolean }[]; addons?: Addon[] };
export type BookConfig = { services: Svc[]; hours: { weekday: number; opens: string; closes: string }[]; autoConfirm: boolean; maxDaysAhead: number; timezone: string; cancelDeadlineHours?: number };
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
  const [addons, setAddons] = useState<string[]>([]);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [where, setWhere] = useState<"SHOP" | "HOME">("SHOP");
  const [address, setAddress] = useState("");
  const [done, setDone] = useState<Done | null>(null);
  const slots = useApi<string[]>(svc && date ? `shop/booking/slots?serviceId=${svc}&date=${date}${addons.map((a) => `&addonIds=${a}`).join("")}` : null);
  const today = new Date();
  const max = new Date(today.getTime() + cfg.maxDaysAhead * 86400000);
  const chosen = cfg.services.find((s) => s.id === svc);
  const mode = chosen?.locationMode ?? "SHOP";
  const place = mode === "BOTH" ? where : mode;
  const extra = (chosen?.addons ?? []).filter((a) => addons.includes(a.id));
  const total = (chosen?.priceMinor ?? 0) + extra.reduce((s, a) => s + a.priceMinor, 0);
  const minutes = (chosen?.durationMinutes ?? 0) + extra.reduce((s, a) => s + a.durationMinutes, 0);
  const pick = (id: string) => { setSvc(id); setTime(""); setAddons([]); setAnswers({}); setWhere("SHOP"); };
  const send = useAction(async () => { setDone(await api<Done>("shop/booking", { body: { serviceId: svc, date, time, name: name.trim(), phone, note: note.trim() || undefined, addonIds: addons, answers, locationMode: place, address: place === "HOME" ? address.trim() : undefined } })); });
  const missing = (chosen?.requiredFields ?? []).some((q) => q.required !== false && !(answers[q.key] ?? "").trim());
  const ready = svc && date && time && name.trim() && phone.replace(/\D/g, "").length >= 9 && !missing && (place !== "HOME" || address.trim().length >= 6);
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
              <button key={s.id} type="button" aria-pressed={svc === s.id} onClick={() => pick(s.id)}
                className={`rounded-2xl border p-4 text-start transition ${svc === s.id ? "border-[var(--s-accent)] bg-[var(--s-soft)] ring-2 ring-[var(--s-accent)]/30" : "border-[var(--s-line)] hover:border-[var(--s-accent)]/50"}`}>
                {s.imageFileId && /* eslint-disable-next-line @next/next/no-img-element */ <img src={fileUrl(s.imageFileId, "thumb")} alt="" className="mb-2 h-28 w-full rounded-xl object-cover" />}
                {s.categoryName && <span className="mb-0.5 block text-[11px] font-bold uppercase tracking-wide text-[var(--s-accent)]">{s.categoryName}</span>}
                <span className="block font-extrabold">{s.name}</span>
                {(s.shortDescription || s.description) && <span className="mt-0.5 line-clamp-2 block text-sm text-[var(--s-mute)]">{s.shortDescription || s.description}</span>}
                <span className="mt-2 flex items-center justify-between text-sm"><span className="text-[var(--s-mute)]">{s.durationMinutes} {t("bk.min")}{s.locationMode === "HOME" ? ` · ${t("svc.loc.HOME")}` : ""}</span><span className="font-extrabold tnum">{s.priceBeforeMinor ? <s className="me-1.5 text-xs font-medium text-[var(--s-mute)]">{money(s.priceBeforeMinor, s.currency, locale)}</s> : null}{money(s.priceMinor, s.currency, locale)}</span></span>
              </button>
            ))}
          </div>
        </fieldset>
        {chosen && ((chosen.addons ?? []).length > 0 || mode === "BOTH") && (
          <fieldset className="s-card space-y-3 p-5">
            <legend className="px-1 text-sm font-extrabold">{t("book.options")}</legend>
            {mode === "BOTH" && <div className="flex gap-2">{(["SHOP", "HOME"] as const).map((w) => <button key={w} type="button" aria-pressed={where === w} onClick={() => setWhere(w)} className={`min-h-11 flex-1 rounded-full border px-4 text-sm font-bold ${where === w ? "border-[var(--s-accent)] bg-[var(--s-accent)] text-white" : "border-[var(--s-line)]"}`}>{t(`svc.loc.${w}`)}</button>)}</div>}
            {(chosen.addons ?? []).map((a) => (
              <label key={a.id} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-2xl border border-[var(--s-line)] p-3 text-sm"><input type="checkbox" checked={addons.includes(a.id)} onChange={(e) => { setAddons(e.target.checked ? [...addons, a.id] : addons.filter((x) => x !== a.id)); setTime(""); }} /><span className="flex-1 font-bold">{a.name}</span><span className="tnum text-[var(--s-mute)]">+{money(a.priceMinor, chosen.currency, locale)} · +{a.durationMinutes} {t("bk.min")}</span></label>
            ))}
          </fieldset>
        )}
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
          {(chosen?.requiredFields ?? []).map((q) => <label key={q.key} className="block space-y-1.5"><span className="text-sm font-bold">{q.label}{q.required === false ? ` (${t("ch.optional")})` : ""}</span><input className="s-input" value={answers[q.key] ?? ""} onChange={(e) => setAnswers({ ...answers, [q.key]: e.target.value })} /></label>)}
          {place === "HOME" && <label className="block space-y-1.5"><span className="text-sm font-bold">{t("book.address")}</span><textarea className="s-input" rows={2} value={address} onChange={(e) => setAddress(e.target.value)} />{chosen?.serviceArea && <span className="block text-xs text-[var(--s-mute)]">{t("book.area", { area: chosen.serviceArea })}</span>}</label>}
          <label className="block space-y-1.5"><span className="text-sm font-bold">{t("bk.note")} ({t("ch.optional")})</span><textarea className="s-input" rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        </fieldset>
        {chosen && date && time && <p className="rounded-2xl bg-[var(--s-soft)] p-3 text-sm font-bold">{t("book.summary", { minutes: String(minutes), total: money(total, chosen.currency, locale) })}</p>}
        {chosen?.instructions && <p className="rounded-2xl border border-[var(--s-line)] p-3 text-sm">{chosen.instructions}</p>}
        {chosen?.cancellationPolicy && <p className="text-xs text-[var(--s-mute)]">{chosen.cancellationPolicy}</p>}
        <ErrorText error={send.error} />
        <div className="sticky bottom-3 z-10">
          <button className="s-btn w-full" disabled={!ready || send.loading}>{send.loading ? "…" : chosen && date && time ? t("book.confirmBtn", { service: chosen.name, date, time }) : t("book.btn")}</button>
        </div>
      </form>
    </div>
  );
}
