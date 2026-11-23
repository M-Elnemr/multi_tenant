"use client";

import Link from "next/link";
import { Suspense } from "react";
import { ErrorText } from "@/components/ui";
import { IArrow } from "../shared/icons";
import { STATUS_TONE, useLnBooking, useLnTrack, type Svc } from "../shared/booking";

const Arrow = () => <IArrow className="lg-arrow" />;

function Fld({ label, err, children }: { label: string; err?: string | null; children: React.ReactNode }) {
  return <label className={`lg-fld${err ? " bad" : ""}`}><span>{label}</span>{children}{err && <span className="lg-err" role="alert">{err}</span>}</label>;
}

function BookInner({ services, phone, open }: { services: Svc[]; phone: string; open: boolean }) {
  const b = useLnBooking(services);
  const { t, fe } = b;
  if (!open) return <div className="lg-empty"><h3>{t("lws.bookingClosed")}</h3>{phone && <a href={`tel:${phone}`} className="lg-btn" dir="ltr">{phone}</a>}</div>;
  if (b.done) return (
    <div className="lg-done">
      <p className="lg-up lg-signal">{b.done.status === "APPROVED" ? t("lws.doneApproved") : t("lws.donePending")}</p>
      <h1>{b.done.guestName}</h1>
      <p style={{ fontSize: "1.3rem" }}>{b.fmtWhen(b.done.startsAt)}</p>
      <div><p style={{ color: "var(--text2)" }}>{t("lws.yourCode")}</p><p className="lg-code" dir="ltr">{b.done.code}</p><p style={{ color: "var(--text2)", marginBlockStart: 10 }}>{t("lws.codeHint")}</p></div>
      <div className="lg-hero-cta"><Link href={`/booking?code=${b.done.code}`} className="lg-btn">{t("lws.track")}<Arrow /></Link><Link href="/" className="lg-btn lg-btn-line">{t("lws.backHome")}</Link></div>
    </div>
  );
  const fullyBooked = b.days.data && !b.firstFree;
  return (
    <form className="lg-bk" onSubmit={(e) => { e.preventDefault(); void b.send.run(); }} noValidate>
      <div>
        {services.length > 1 && (
          <div className="lg-step">
            <div className="lg-step-h"><span>1</span><h2>{t("lws.chooseService")}</h2></div>
            <div className="lg-opt">{services.map((s) => (
              <button type="button" key={s.id} className="lg-pick" aria-pressed={b.service === s.id} onClick={() => b.pickService(s.id)}>
                <b>{s.name}</b><small>{t("lws.minutes", { n: s.durationMinutes })} · {t(`lws.mode.${s.mode}`)}{b.price(s) ? ` · ${b.price(s)}` : ""}</small>
              </button>
            ))}</div>
          </div>
        )}
        <div className="lg-step">
          <div className="lg-step-h"><span>{services.length > 1 ? 2 : 1}</span><h2>{t("lws.chooseTime")}</h2></div>
          {b.loadError && <div className="lg-note" role="alert">{b.loadError} <button type="button" className="lg-btn lg-btn-line" onClick={() => void b.reloadTimes()}>{t("lnx.err.retry")}</button></div>}
          {b.days.loading && !b.days.data && <div className="lg-sk" aria-hidden="true" />}
          {fullyBooked && <div className="lg-note">{t("lws.fullyBooked")}</div>}
          {b.days.data && b.firstFree && (
            <>
              <div className="lg-days" role="group">{b.days.data.map((d) => (
                <button type="button" key={d.date} className="lg-pick" disabled={d.free === 0} aria-pressed={b.chosenDay === d.date} onClick={() => b.pickDay(d.date)}>
                  <small>{b.fmtDay(d.date, { weekday: "short" })}</small><b>{b.fmtDay(d.date, { day: "numeric" })}</b><small>{b.fmtDay(d.date, { month: "short" })}</small>
                </button>
              ))}</div>
              {b.slots.loading && !b.slots.data ? <div className="lg-sk" aria-hidden="true" />
                : b.slots.data && b.slots.data.length === 0 ? <div className="lg-note">{t("lws.noTimes")}</div>
                : <div className="lg-times" role="group">{(b.slots.data ?? []).map((s) => <button type="button" key={s} className="lg-pick" aria-pressed={b.time === s} onClick={() => b.pickTime(s)}>{b.fmtTime(s)}</button>)}</div>}
              {fe.err("startsAt") && <p className="lg-err" role="alert">{fe.err("startsAt")}</p>}
            </>
          )}
        </div>
        <div className="lg-step">
          <div className="lg-step-h"><span>{services.length > 1 ? 3 : 2}</span><h2>{t("lws.yourDetails")}</h2></div>
          <Fld label={t("lws.name")} err={fe.err("name")}><input className="lg-in" autoComplete="name" value={b.f.name} onChange={(e) => b.setField("name", e.target.value)} /></Fld>
          <Fld label={t("lws.phone")} err={fe.err("phone")}><input className="lg-in" dir="ltr" inputMode="tel" autoComplete="tel" value={b.f.phone} onChange={(e) => b.setField("phone", e.target.value)} /></Fld>
          <Fld label={t("lws.topic")} err={fe.err("topic")}><textarea className="lg-in" rows={3} placeholder={t("lws.topicPh")} value={b.f.topic} onChange={(e) => b.setField("topic", e.target.value)} /></Fld>
          <p className="lg-note">{t("lws.private")}</p>
        </div>
      </div>
      <aside>
        <div className="lg-sum">
          <h3>{t("lws.summary")}</h3>
          <dl style={{ margin: 0 }}>
            <div><dt>{t("lws.session")}</dt><dd>{b.svc?.name ?? "—"}</dd></div>
            <div><dt>{t("lws.when")}</dt><dd>{b.time ? b.fmtWhen(b.time, false) : "—"}</dd></div>
            {b.svc && <div><dt>{t("lws.duration")}</dt><dd>{t("lws.minutes", { n: b.svc.durationMinutes })}</dd></div>}
            {b.price(b.svc) && <div><dt>{t("lws.fee")}</dt><dd>{b.price(b.svc)}</dd></div>}
          </dl>
          <p style={{ fontSize: ".85rem", opacity: .75, marginBlockStart: 8 }}>{t("lws.payAtOffice")}</p>
          {b.errorText && <p className="lg-err" role="alert">{b.errorText}</p>}
          <button type="submit" className="lg-btn" disabled={b.send.loading}>{t("lws.confirm")}<Arrow /></button>
        </div>
      </aside>
    </form>
  );
}

export function LgBookClient(props: { services: Svc[]; phone: string; open: boolean }) {
  return <Suspense><BookInner {...props} /></Suspense>;
}

function TrackInner() {
  const k = useLnTrack();
  const { t, b } = k;
  return (
    <div className="lg-wrap" style={{ paddingBlock: "clamp(40px,6vw,80px)", maxInlineSize: 760 }}>
      <form className="lg-step" onSubmit={(e) => { e.preventDefault(); void k.find.run(); }}>
        <Fld label={t("lws.code")}><input className="lg-in" dir="ltr" style={{ textTransform: "uppercase", letterSpacing: ".15em" }} value={k.code} onChange={(e) => k.setCode(e.target.value)} /></Fld>
        <Fld label={t("lws.phone")}><input className="lg-in" dir="ltr" inputMode="tel" value={k.phone} onChange={(e) => k.setPhone(e.target.value)} /></Fld>
        <ErrorText error={k.find.error} />
        <div><button type="submit" className="lg-btn" disabled={k.find.loading}>{t("lws.find")}<Arrow /></button></div>
      </form>
      {b && (
        <div className="lg-box" style={{ display: "grid", gap: 14 }}>
          <span className={`lg-st ${STATUS_TONE[b.status] ?? "off"}`}>{t(`lws.st.${b.status}`)}</span>
          <h3 style={{ fontSize: "1.8rem" }}>{k.when(b.startsAt)}</h3>
          {b.serviceName && <p style={{ color: "var(--text2)" }}>{b.serviceName}</p>}
          {b.decisionNote && <p className="lg-note">{b.decisionNote}</p>}
          <ErrorText error={k.cancel.error} />
          {(b.status === "PENDING" || b.status === "APPROVED") && <div><button type="button" className="lg-btn lg-btn-line" disabled={k.cancel.loading} onClick={() => void k.cancel.run()}>{t("lws.cancelBooking")}</button></div>}
        </div>
      )}
    </div>
  );
}

export function LgTrackClient() {
  return <Suspense><TrackInner /></Suspense>;
}
