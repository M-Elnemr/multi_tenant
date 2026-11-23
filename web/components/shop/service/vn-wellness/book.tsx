"use client";

import Link from "next/link";
import { useI18n } from "@/components/i18n-provider";
import { PhoneInput } from "@/components/inputs";
import { ErrorText } from "@/components/ui";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";
import { isoDay, useBookFlow, useManage } from "../vn-core/use-book-flow";
import type { BookInit, VnConfig } from "../vn-core/types";
import "./wellness.css";

/** Booking in three quiet steps. Times come only from the backend's free slots; the result shown is exactly the status the backend answers. */
export function WellnessBook({ cfg, init }: { cfg: VnConfig; init: BookInit }) {
  const { t, locale } = useI18n();
  const f = useBookFlow(cfg, init);
  const c = f.chosen;
  if (f.done) {
    const ok = f.done.status === "CONFIRMED";
    return (
      <div className="wl-page wl-wrap"><div className="wl-done">
        <span className="wl-done-mark"><Icon name={ok ? "check" : "clock"} className="h-8 w-8" /></span>
        <h1 className="wl-h1 wl-h1-sm">{ok ? t("book.doneConfirmed") : t("book.doneWaiting")}</h1>
        <p className="wl-copy" style={{ marginInline: "auto" }}>{ok ? t("book.doneConfirmedBody") : t("book.doneWaitingBody")}</p>
        <div className="wl-ticket"><b>{f.done.serviceName}</b><bdi dir="ltr" className="tnum">{f.done.date} · {f.done.time}</bdi><code dir="ltr">{f.done.bookingNumber}</code></div>
        <p className="wl-muted">{t("book.keepNumber")}</p>
        <p className="wl-cta" style={{ justifyContent: "center" }}><Link href="/book/manage" className="wl-btn wl-btn-ghost">{t("vn.nav.myBooking")}</Link><Link href="/" className="wl-btn">{t("shop.home")}</Link></p>
      </div></div>
    );
  }
  const e = f.errors, show = f.touched;
  return (
    <div className="wl-page wl-wrap"><div className="wl-book">
      <div className="wl-head wl-head-l"><p className="wl-kicker">{t("book.eyebrow")}</p><h1 className="wl-h1 wl-h1-sm">{t("book.title")}</h1><p className="wl-muted">{cfg.autoConfirm ? t("book.subAuto") : t("book.sub")}</p></div>
      <form className="wl-form" noValidate onSubmit={(ev) => { ev.preventDefault(); f.submit(); }}>
        <fieldset className="wl-step"><legend><i>1</i>{t("book.pickService")}</legend>
          <div className="wl-choices">{cfg.services.map((s) => (
            <button key={s.id} type="button" className="wl-choice" aria-pressed={f.svc === s.id} onClick={() => f.pick(s.id)}>
              <b>{s.name}</b><span><bdi>{s.durationMinutes} {t("bk.min")}</bdi><bdi className="tnum">{money(s.priceMinor, s.currency, locale)}</bdi></span>
            </button>))}</div>
        </fieldset>

        {c && ((c.staff ?? []).length > 0 || (c.addons ?? []).length > 0 || f.mode === "BOTH") && (
          <fieldset className="wl-step"><legend><i>+</i>{t("book.options")}</legend>
            {(c.staff ?? []).length > 0 && <div className="wl-pills" style={{ marginBottom: 14 }}>{[{ id: "", name: t("book.anyone") }, ...(c.staff ?? [])].map((x) => <button key={x.id || "any"} type="button" className="wl-pill" aria-pressed={f.staff === x.id} onClick={() => f.setStaff(x.id)}>{x.name}</button>)}</div>}
            {f.mode === "BOTH" && <div className="wl-pills" style={{ marginBottom: 14 }}>{(["SHOP", "HOME"] as const).map((w) => <button key={w} type="button" className="wl-pill" aria-pressed={f.where === w} onClick={() => f.setWhere(w)}>{t(`svc.loc.${w}`)}</button>)}</div>}
            <div className="wl-fields">{(c.addons ?? []).map((a) => (
              <label key={a.id} className="wl-check"><input type="checkbox" checked={f.addons.includes(a.id)} onChange={(ev) => f.setAddons(ev.target.checked ? [...f.addons, a.id] : f.addons.filter((x) => x !== a.id))} /><span>{a.name}</span><em className="tnum">+{money(a.priceMinor, c.currency, locale)} · +{a.durationMinutes} {t("bk.min")}</em></label>))}</div>
          </fieldset>)}

        <fieldset className="wl-step"><legend><i>2</i>{t("book.when")}</legend>
          <input type="date" className="wl-date" dir="ltr" min={isoDay(f.today)} max={isoDay(f.max)} value={f.date} aria-label={t("bk.date")} aria-invalid={show && e.date} onChange={(ev) => f.setDate(ev.target.value)} />
          {f.date && !f.slots.data && !f.slots.error && <p className="wl-slotnote">…</p>}
          {f.slots.error ? <p className="wl-slotnote">{t("vn.loadFailed")}</p> : null}
          {f.slots.data && f.slots.data.length === 0 && <p className="wl-slotnote">{t("book.noTimes")}</p>}
          {f.slots.data && f.slots.data.length > 0 && <div className="wl-pills" style={{ marginTop: 14 }} role="group">{f.slots.data.map((s) => <button key={s} type="button" className="wl-pill" aria-pressed={f.time === s} onClick={() => f.setTime(s)}>{s}</button>)}</div>}
          {show && e.date && <p className="wl-field-err">{t("vn.pickDay")}</p>}
          {show && !e.date && e.time && <p className="wl-field-err">{t("vn.pickTime")}</p>}
        </fieldset>

        <fieldset className="wl-step"><legend><i>3</i>{t("book.you")}</legend>
          <div className="wl-fields">
            <label className="wl-field">{t("bk.customerName")}<input value={f.name} autoComplete="name" aria-invalid={show && e.name} onChange={(ev) => f.setName(ev.target.value)} />{show && e.name && <span className="wl-field-err">{t("vn.required")}</span>}</label>
            <label className="wl-field">{t("book.phone")}<PhoneInput value={f.phone} onValue={f.setPhone} required />{show && e.phone && <span className="wl-field-err">{t("vn.phoneInvalid")}</span>}</label>
            {(c?.requiredFields ?? []).map((q) => <label key={q.key} className="wl-field">{q.label}{q.required === false ? ` (${t("ch.optional")})` : ""}<input value={f.answers[q.key] ?? ""} onChange={(ev) => f.setAnswers({ ...f.answers, [q.key]: ev.target.value })} /></label>)}
            {f.place === "HOME" && <label className="wl-field">{t("book.address")}<textarea rows={2} value={f.address} aria-invalid={show && e.address} onChange={(ev) => f.setAddress(ev.target.value)} />{c?.serviceArea && <span>{t("book.area", { area: c.serviceArea })}</span>}{show && e.address && <span className="wl-field-err">{t("vn.required")}</span>}</label>}
            <label className="wl-field">{t("bk.note")} ({t("ch.optional")})<textarea rows={2} value={f.note} onChange={(ev) => f.setNote(ev.target.value)} /></label>
          </div>
        </fieldset>

        {c && f.date && f.time && <div className="wl-summary"><b>{c.name}</b><bdi dir="ltr" className="tnum">{f.date} · {f.time}</bdi><span>{t("book.summary", { minutes: String(f.minutes), total: money(f.total, c.currency, locale) })}</span></div>}
        {c?.instructions && <p className="wl-muted">{c.instructions}</p>}
        {c?.cancellationPolicy && <p className="wl-muted">{c.cancellationPolicy}</p>}
        {f.send.error ? <ErrorText error={f.send.error} /> : null}
        <div className="wl-submit"><button className="wl-btn" disabled={f.send.loading}>{f.send.loading ? "…" : c && f.date && f.time ? t("book.confirmBtn", { service: c.name, date: f.date, time: f.time }) : t("book.btn")}</button></div>
      </form>
    </div></div>
  );
}

/** Find a booking by its number and phone, and cancel it (the two public endpoints). */
export function WellnessManage() {
  const { t, locale } = useI18n();
  const m = useManage();
  const f = m.found;
  return (
    <div className="wl-page wl-wrap"><div className="wl-manage">
      <div className="wl-head wl-head-l"><p className="wl-kicker">{t("vn.nav.myBooking")}</p><h1 className="wl-h1 wl-h1-sm">{t("vn.manage.title")}</h1><p className="wl-muted">{t("vn.manage.sub")}</p></div>
      {!f ? (
        <form className="wl-fields" onSubmit={(ev) => { ev.preventDefault(); if (m.ready && !m.look.loading) void m.look.run(); }}>
          <label className="wl-field">{t("vn.manage.number")}<input value={m.number} dir="ltr" autoCapitalize="characters" onChange={(ev) => m.setNumber(ev.target.value)} /></label>
          <label className="wl-field">{t("book.phone")}<PhoneInput value={m.phone} onValue={m.setPhone} required /></label>
          {m.look.error ? <ErrorText error={m.look.error} /> : null}
          <button className="wl-btn" disabled={!m.ready || m.look.loading}>{m.look.loading ? "…" : t("vn.manage.find")}</button>
        </form>
      ) : (
        <div className="wl-ticket" style={{ textAlign: "start" }}>
          <span className="wl-badge-s" data-s={f.status}>{t(`bk.status.${f.status}`)}</span>
          <b>{f.serviceName}</b><bdi dir="ltr" className="tnum">{f.date} · {f.time}</bdi>
          {f.priceMinor != null && f.currency && <bdi className="tnum">{money(f.priceMinor, f.currency, locale)}</bdi>}
          <code dir="ltr">{f.bookingNumber}</code>
          {m.cancelled && <p className="wl-muted">{t("vn.manage.cancelled")}</p>}
          {m.cancel.error ? <ErrorText error={m.cancel.error} /> : null}
          <p className="wl-cta wl-cta-left">
            {(f.status === "PENDING" || f.status === "CONFIRMED") && <button type="button" className="wl-btn wl-btn-ghost" disabled={m.cancel.loading} onClick={() => { if (window.confirm(t("vn.manage.confirmCancel"))) void m.cancel.run(); }}>{m.cancel.loading ? "…" : t("vn.manage.cancel")}</button>}
            <button type="button" className="wl-btn wl-btn-ghost" onClick={m.reset}>{t("common.back")}</button>
          </p>
        </div>
      )}
    </div></div>
  );
}
