"use client";

import Link from "next/link";
import { useI18n } from "@/components/i18n-provider";
import { useT } from "@/components/i18n-provider";
import { errorMessage } from "@/lib/error-text";
import { money, timeOnly } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import type { CnProfile } from "./kit";
import { useBooking } from "./use-booking";
import { useCnT } from "./ui";

const dayLabel = (d: string, locale: Locale, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-GB", { ...o, timeZone: "UTC" }).format(new Date(d + "T12:00:00Z"));

/**
 * The booking flow of every cn-* template (one markup, skinned per template). Same endpoints and rules as the original /book page:
 * only free times are offered, the server re-checks on confirm, and the confirmation appears only after the backend answered.
 */
export function BookingFlow({ p, dict, locale, phone }: { p: CnProfile; dict: Record<string, string>; locale: Locale; phone: string }) {
  const t = useCnT(dict);
  const { timezone } = useI18n();
  const base = useT();
  const b = useBooking(p);

  if (b.done) {
    const when = `${dayLabel(b.done.startAt.slice(0, 10), locale, { weekday: "long", day: "numeric", month: "long" })} · ${timeOnly(b.done.startAt, locale, timezone)}`;
    const dn = p.doctors.find((d) => d.id === b.done!.doctorId)?.displayName;
    const sn = p.services.find((s) => s.id === b.done!.serviceId)?.name;
    const bn = p.branches.find((x) => x.id === b.done!.branchId)?.name;
    return (
      <div className="cn-done" role="status">
        <span className="cn-done-mark" aria-hidden>✓</span>
        <h2 className="cn-h2">{b.done.status === "CONFIRMED" ? t("cn.book.confirmed") : t("cn.book.requested")}</h2>
        <dl className="cn-facts">
          {dn && <div><dt>{t("book.doctor")}</dt><dd>{dn}</dd></div>}
          {sn && <div><dt>{t("book.service")}</dt><dd>{sn}</dd></div>}
          <div><dt>{t("cn.book.when")}</dt><dd>{when}</dd></div>
          {bn && p.branches.length > 1 && <div><dt>{t("book.branch")}</dt><dd>{bn}</dd></div>}
          {b.patientName(b.done.patientId) && <div><dt>{t("book.forWhom")}</dt><dd>{b.patientName(b.done.patientId)}</dd></div>}
        </dl>
        <div className="cn-cta-row"><Link href="/portal" className="cn-btn">{t("cn.book.myAppointments")}</Link><Link href="/" className="cn-btn cn-btn-ghost">{t("cn.nav.home")}</Link></div>
      </div>
    );
  }

  const ready = b.ready;
  return (
    <div className="cn-book">
      {ready && !b.me && <div className="cn-note" role="note">{t("book.loginFirst")} <Link href="/login?next=/book" className="cn-link">{t("nav.login")}</Link></div>}
      {ready && b.me && !b.isPatient && <div className="cn-note" role="note">{t("book.noFile")} <Link href="/link" className="cn-link">{t("link.title")}</Link></div>}
      <div className="cn-book-grid">
        <div className="cn-card cn-book-pick">
          <label className="cn-field"><span>{t("book.service")}</span>
            <select value={b.serviceId} onChange={(e) => b.setService(e.target.value)}>{p.services.map((s) => <option key={s.id} value={s.id}>{s.name} · {s.durationMinutes} {t("clinic.minutes")}{s.priceMinor ? ` · ${money(s.priceMinor, s.currency, locale)}` : ""}</option>)}</select></label>
          <label className="cn-field"><span>{t("book.doctor")}</span>
            <select value={b.doctorId} onChange={(e) => b.setDoctor(e.target.value)}>{p.doctors.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</select></label>
          {p.branches.length > 1 && <label className="cn-field"><span>{t("book.branch")}</span>
            <select value={b.branchId} onChange={(e) => b.setBranch(e.target.value)}>{p.branches.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</select></label>}
          {b.isPatient && b.patients.length > 1 && <label className="cn-field"><span>{t("book.forWhom")}</span>
            <select value={b.patientId} onChange={(e) => b.setPatient(e.target.value)}>{b.patients.map((x) => <option key={x.id} value={x.id}>{x.firstName} {x.lastName}</option>)}</select></label>}
        </div>
        <div className="cn-card cn-book-time">
          <p className="cn-label">{t("book.day")}</p>
          <div className="cn-days" role="listbox" aria-label={t("book.day")}>{b.days.map((d) => (
            <button key={d} type="button" role="option" aria-selected={d === b.date} disabled={!b.works(d)} title={b.works(d) ? undefined : t("book.dayOff")} data-on={d === b.date || undefined} onClick={() => b.setDate(d)}>
              <small>{dayLabel(d, locale, { weekday: "short" })}</small><b>{dayLabel(d, locale, { day: "numeric" })}</b><small>{dayLabel(d, locale, { month: "short" })}</small>
            </button>
          ))}</div>
          <p className="cn-label">{t("book.time")}</p>
          {b.slotsLoading ? <p className="cn-muted" aria-busy>{t("cn.book.loadingTimes")}</p>
            : b.slotsError ? <p className="cn-err" role="alert">{t("cn.book.timesError")} <button type="button" className="cn-link" onClick={() => b.reloadSlots()}>{t("cn.book.retry")}</button></p>
            : (b.slots?.length ?? 0) === 0 ? <p className="cn-empty">{b.noHours ? t("book.noWorkingDays") : t("book.noSlots")}</p>
            : <div className="cn-slots">{b.slots?.map((s) => <button key={s} type="button" aria-pressed={b.slot === s} data-on={b.slot === s || undefined} onClick={() => b.setSlot(s)}>{timeOnly(s, locale, timezone)}</button>)}</div>}
          {b.slot && (
            <div className="cn-book-confirm">
              {(p.cashEnabled || p.cardEnabled) && <label className="cn-field"><span>{t("book.payment")}</span>
                <select value={b.payment} onChange={(e) => b.setPayment(e.target.value)}>
                  {p.cashEnabled && <option value="CASH_AT_CLINIC">{t("pay.CASH_AT_CLINIC")}</option>}
                  {p.cardEnabled && b.service?.priceMinor ? <option value="CARD">{t("pay.CARD")}</option> : null}
                </select></label>}
              <label className="cn-field"><span>{t("book.note")}</span><textarea rows={2} value={b.note} onChange={(e) => b.setNote(e.target.value)} /></label>
              <p className="cn-summary">{b.doctor?.displayName} · {b.service?.name} · {dayLabel(b.date, locale, { weekday: "short", day: "numeric", month: "short" })} {timeOnly(b.slot, locale, timezone)}</p>
              {!!b.submit.error && <p className="cn-err" role="alert">{errorMessage(base, locale, b.submit.error)}</p>}
              <button type="button" className="cn-btn cn-btn-block" disabled={!b.canSubmit} onClick={() => b.submit.run()}>{b.submit.loading ? t("cn.book.sending") : t("book.confirm")}</button>
              {ready && !b.isPatient && <p className="cn-muted cn-hint">{t("cn.book.needSignIn")}</p>}
            </div>
          )}
        </div>
      </div>
      {phone && <p className="cn-muted cn-book-help">{t("cn.book.help")} <a href={`tel:${phone}`} dir="ltr" className="cn-link">{phone}</a></p>}
    </div>
  );
}
