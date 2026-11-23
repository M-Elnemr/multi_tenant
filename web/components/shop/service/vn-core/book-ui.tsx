"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { PhoneInput } from "@/components/inputs";
import { ErrorText } from "@/components/ui";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";
import { isoDay, useBookFlow, useManage, type BookFlow } from "./use-book-flow";
import type { BookInit, VnConfig } from "./types";
import "./form.css";

export type BookLayout = "steps" | "request" | "calendar";

/** A month grid. Days are disabled when the shop is closed that weekday (from its hours), in the past, or beyond how far ahead it books; the free TIMES still come only from the backend. */
function Calendar({ f, cfg }: { f: BookFlow; cfg: VnConfig }) {
  const { t, locale } = useI18n();
  const [shift, setShift] = useState(() => { const m = /^(\d{4})-(\d{2})/.exec(f.date); return m ? Math.max(0, (Number(m[1]) - f.today.getFullYear()) * 12 + Number(m[2]) - 1 - f.today.getMonth()) : 0; });
  const first = useMemo(() => { const d = new Date(f.today.getFullYear(), f.today.getMonth() + shift, 1); return d; }, [f.today, shift]);
  const open = new Set(cfg.hours.map((h) => h.weekday));
  const lead = (first.getDay() + 1) % 7;   // week starts on Saturday
  const days = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  const min = isoDay(f.today), max = isoDay(f.max);
  const title = new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-US", { month: "long", year: "numeric" }).format(first);
  const dows = [6, 7, 1, 2, 3, 4, 5];
  return (
    <div className="vf-cal">
      <div className="vf-cal-top">
        <button type="button" className="vf-cal-nav" disabled={shift === 0} onClick={() => setShift(shift - 1)} aria-label={t("common.back")}>{locale === "ar" ? "›" : "‹"}</button>
        <span>{title}</span>
        <button type="button" className="vf-cal-nav" disabled={isoDay(new Date(first.getFullYear(), first.getMonth() + 1, 1)) > max} onClick={() => setShift(shift + 1)} aria-label={t("common.next")}>{locale === "ar" ? "‹" : "›"}</button>
      </div>
      <div className="vf-cal-dow">{dows.map((w) => <span key={w}>{t(`bk.day.${w}`).slice(0, locale === "ar" ? 3 : 3)}</span>)}</div>
      <div className="vf-cal-days">
        {Array.from({ length: lead }, (_, i) => <span key={`b${i}`} className="vf-cal-blank" />)}
        {Array.from({ length: days }, (_, i) => {
          const d = new Date(first.getFullYear(), first.getMonth(), i + 1); const iso = isoDay(d); const wd = d.getDay() === 0 ? 7 : d.getDay();
          const off = iso < min || iso > max || !open.has(wd);
          return <button key={iso} type="button" className="vf-cal-day" disabled={off} aria-pressed={f.date === iso} onClick={() => f.setDate(iso)}>{i + 1}</button>;
        })}
      </div>
    </div>
  );
}

/**
 * The booking page of every vn-* design. The data flow is identical (the shared useBookFlow: same endpoints and payload as the original page, times
 * only from the backend's free slots, the status shown is the one the backend answers); `layout` changes how it is composed:
 * steps = pick from cards, request = one compact request form with a service list, calendar = month grid beside the times.
 */
export function BookUI({ cfg, init, layout = "steps", noteLabel }: { cfg: VnConfig; init: BookInit; layout?: BookLayout; noteLabel?: string }) {
  const { t, locale } = useI18n();
  const f = useBookFlow(cfg, init);
  const c = f.chosen;
  if (f.done) {
    const ok = f.done.status === "CONFIRMED";
    return (
      <div className="vf-page vf-wrap"><div className="vf-done">
        <span className="vf-done-mark"><Icon name={ok ? "check" : "clock"} className="h-8 w-8" /></span>
        <h1 className="vf-h1">{ok ? t("book.doneConfirmed") : t("book.doneWaiting")}</h1>
        <p className="vf-muted">{ok ? t("book.doneConfirmedBody") : t("book.doneWaitingBody")}</p>
        <div className="vf-ticket"><b>{f.done.serviceName}</b><bdi dir="ltr" className="tnum">{f.done.date} · {f.done.time}</bdi><code dir="ltr">{f.done.bookingNumber}</code></div>
        <p className="vf-muted">{t("book.keepNumber")}</p>
        <p className="vf-cta" style={{ justifyContent: "center" }}><Link href="/book/manage" className="vf-btn vf-btn-ghost">{t("vn.nav.myBooking")}</Link><Link href="/" className="vf-btn">{t("shop.home")}</Link></p>
      </div></div>
    );
  }
  const e = f.errors, show = f.touched;
  const times = (
    <>
      {layout !== "calendar" && <input type="date" className="vf-date" dir="ltr" min={isoDay(f.today)} max={isoDay(f.max)} value={f.date} aria-label={t("bk.date")} aria-invalid={show && e.date} onChange={(ev) => f.setDate(ev.target.value)} />}
      {f.date && !f.slots.data && !f.slots.error && <p className="vf-slotnote">…</p>}
      {f.slots.error ? <p className="vf-slotnote">{t("vn.loadFailed")}</p> : null}
      {f.slots.data && f.slots.data.length === 0 && <p className="vf-slotnote">{t("book.noTimes")}</p>}
      {f.slots.data && f.slots.data.length > 0 && <div className="vf-pills" style={{ marginTop: 12 }} role="group">{f.slots.data.map((s) => <button key={s} type="button" className="vf-pill" aria-pressed={f.time === s} onClick={() => f.setTime(s)}>{s}</button>)}</div>}
      {show && e.date && <p className="vf-err">{t("vn.pickDay")}</p>}
      {show && !e.date && e.time && <p className="vf-err">{t("vn.pickTime")}</p>}
    </>
  );
  const service = layout === "request" ? (
    <label className="vf-field">{t("book.pickService")}
      <select value={f.svc} onChange={(ev) => f.pick(ev.target.value)}>{cfg.services.map((s) => <option key={s.id} value={s.id}>{s.name} · {money(s.priceMinor, s.currency, locale)}</option>)}</select>
    </label>
  ) : (
    <div className="vf-choices">{cfg.services.map((s) => (
      <button key={s.id} type="button" className="vf-choice" aria-pressed={f.svc === s.id} onClick={() => f.pick(s.id)}>
        <b>{s.name}</b><span><bdi>{s.durationMinutes} {t("bk.min")}</bdi><bdi className="tnum">{money(s.priceMinor, s.currency, locale)}</bdi></span>
      </button>))}</div>
  );
  const options = c && ((c.staff ?? []).length > 0 || (c.addons ?? []).length > 0 || f.mode === "BOTH") && (
    <fieldset className="vf-step"><legend><i>+</i>{t("book.options")}</legend>
      {(c.staff ?? []).length > 0 && <div className="vf-pills" style={{ marginBottom: 12 }}>{[{ id: "", name: t("book.anyone") }, ...(c.staff ?? [])].map((x) => <button key={x.id || "any"} type="button" className="vf-pill" aria-pressed={f.staff === x.id} onClick={() => f.setStaff(x.id)}>{x.name}</button>)}</div>}
      {f.mode === "BOTH" && <div className="vf-pills" style={{ marginBottom: 12 }}>{(["SHOP", "HOME"] as const).map((w) => <button key={w} type="button" className="vf-pill" aria-pressed={f.where === w} onClick={() => f.setWhere(w)}>{t(`svc.loc.${w}`)}</button>)}</div>}
      <div className="vf-fields">{(c.addons ?? []).map((a) => (
        <label key={a.id} className="vf-check"><input type="checkbox" checked={f.addons.includes(a.id)} onChange={(ev) => f.setAddons(ev.target.checked ? [...f.addons, a.id] : f.addons.filter((x) => x !== a.id))} /><span>{a.name}</span><em className="tnum">+{money(a.priceMinor, c.currency, locale)} · +{a.durationMinutes} {t("bk.min")}</em></label>))}</div>
    </fieldset>
  );
  const details = (
    <fieldset className="vf-step"><legend><i>3</i>{t("book.you")}</legend>
      <div className="vf-fields">
        <label className="vf-field">{t("bk.customerName")}<input value={f.name} autoComplete="name" aria-invalid={show && e.name} onChange={(ev) => f.setName(ev.target.value)} />{show && e.name && <span className="vf-err">{t("vn.required")}</span>}</label>
        <label className="vf-field">{t("book.phone")}<PhoneInput value={f.phone} onValue={f.setPhone} required />{show && e.phone && <span className="vf-err">{t("vn.phoneInvalid")}</span>}</label>
        {(c?.requiredFields ?? []).map((q) => <label key={q.key} className="vf-field">{q.label}{q.required === false ? ` (${t("ch.optional")})` : ""}<input value={f.answers[q.key] ?? ""} onChange={(ev) => f.setAnswers({ ...f.answers, [q.key]: ev.target.value })} /></label>)}
        {f.place === "HOME" && <label className="vf-field">{t("book.address")}<textarea rows={2} value={f.address} aria-invalid={show && e.address} onChange={(ev) => f.setAddress(ev.target.value)} />{c?.serviceArea && <span>{t("book.area", { area: c.serviceArea })}</span>}{show && e.address && <span className="vf-err">{t("vn.required")}</span>}</label>}
        <label className="vf-field">{noteLabel ?? (layout === "request" ? t("vn.hp.problem") : t("bk.note"))} ({t("ch.optional")})<textarea rows={layout === "request" ? 4 : 2} value={f.note} onChange={(ev) => f.setNote(ev.target.value)} /></label>
      </div>
    </fieldset>
  );
  const footer = (
    <>
      {c && f.date && f.time && <div className="vf-summary"><b>{c.name}</b><bdi dir="ltr" className="tnum">{f.date} · {f.time}</bdi><span>{t("book.summary", { minutes: String(f.minutes), total: money(f.total, c.currency, locale) })}</span></div>}
      {c?.instructions && <p className="vf-muted">{c.instructions}</p>}
      {c?.cancellationPolicy && <p className="vf-muted">{c.cancellationPolicy}</p>}
      {f.send.error ? <ErrorText error={f.send.error} /> : null}
      <div className="vf-submit"><button className="vf-btn" disabled={f.send.loading}>{f.send.loading ? "…" : c && f.date && f.time ? t("book.confirmBtn", { service: c.name, date: f.date, time: f.time }) : t("book.btn")}</button></div>
    </>
  );
  return (
    <div className="vf-page vf-wrap"><div className={`vf-book ${layout === "calendar" ? "vf-book-cal" : ""}`}>
      <div className="vf-head"><p className="vf-kicker">{t("book.eyebrow")}</p><h1 className="vf-h1">{layout === "request" ? t("vn.hp.requestTitle") : t("book.title")}</h1><p className="vf-muted">{cfg.autoConfirm ? t("book.subAuto") : t("book.sub")}</p></div>
      <form className="vf-form" noValidate onSubmit={(ev) => { ev.preventDefault(); f.submit(); }}>
        {layout === "calendar" ? (
          <>
            <fieldset className="vf-step"><legend><i>1</i>{t("book.pickService")}</legend>{service}</fieldset>
            {options}
            <fieldset className="vf-step"><legend><i>2</i>{t("book.when")}</legend><div className="vf-cal-grid"><div><Calendar f={f} cfg={cfg} />{show && e.date && <p className="vf-err">{t("vn.pickDay")}</p>}</div><div>{times}</div></div></fieldset>
          </>
        ) : (
          <>
            <fieldset className="vf-step"><legend><i>1</i>{t("book.pickService")}</legend>{service}</fieldset>
            {options}
            <fieldset className="vf-step"><legend><i>2</i>{t("book.when")}</legend>{times}</fieldset>
          </>
        )}
        {details}
        {footer}
      </form>
    </div></div>
  );
}

/** Find a booking by its number and phone, and cancel it (the two public endpoints). */
export function ManageUI() {
  const { t, locale } = useI18n();
  const m = useManage();
  const sp = useSearchParams();
  const pre = sp.get("n");
  const { setNumber } = m;
  useEffect(() => { if (pre) setNumber(pre); }, [pre, setNumber]);
  const f = m.found;
  return (
    <div className="vf-page vf-wrap"><div className="vf-manage">
      <div className="vf-head"><p className="vf-kicker">{t("vn.nav.myBooking")}</p><h1 className="vf-h1">{t("vn.manage.title")}</h1><p className="vf-muted">{t("vn.manage.sub")}</p></div>
      {!f ? (
        <form className="vf-fields" onSubmit={(ev) => { ev.preventDefault(); if (m.ready && !m.look.loading) void m.look.run(); }}>
          <label className="vf-field">{t("vn.manage.number")}<input value={m.number} dir="ltr" autoCapitalize="characters" onChange={(ev) => m.setNumber(ev.target.value)} /></label>
          <label className="vf-field">{t("book.phone")}<PhoneInput value={m.phone} onValue={m.setPhone} required /></label>
          {m.look.error ? <ErrorText error={m.look.error} /> : null}
          <button className="vf-btn" disabled={!m.ready || m.look.loading}>{m.look.loading ? "…" : t("vn.manage.find")}</button>
        </form>
      ) : (
        <div className="vf-ticket">
          <span className="vf-status" data-s={f.status}>{t(`bk.status.${f.status}`)}</span>
          <b>{f.serviceName}</b><bdi dir="ltr" className="tnum">{f.date} · {f.time}</bdi>
          {f.priceMinor != null && f.currency && <bdi className="tnum">{money(f.priceMinor, f.currency, locale)}</bdi>}
          <code dir="ltr">{f.bookingNumber}</code>
          {m.cancelled && <p className="vf-muted">{t("vn.manage.cancelled")}</p>}
          {m.cancel.error ? <ErrorText error={m.cancel.error} /> : null}
          <p className="vf-cta">
            {(f.status === "PENDING" || f.status === "CONFIRMED") && <button type="button" className="vf-btn vf-btn-ghost" disabled={m.cancel.loading} onClick={() => { if (window.confirm(t("vn.manage.confirmCancel"))) void m.cancel.run(); }}>{m.cancel.loading ? "…" : t("vn.manage.cancel")}</button>}
            <button type="button" className="vf-btn vf-btn-ghost" onClick={m.reset}>{t("common.back")}</button>
          </p>
        </div>
      )}
    </div></div>
  );
}
