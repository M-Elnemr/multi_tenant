"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Suspense, useEffect, useState, type ReactNode } from "react";
import { ErrorText } from "@/components/ui";
import { useI18n } from "@/components/i18n-provider";
import { IArrow } from "./icons";
import { STATUS_TONE, useLnBooking, useLnTrack, type Svc } from "./booking";
import type { LnNav } from "./ctx";

const Arrow = () => <IArrow className="kx-arrow" />;

/** Burger + full-screen sheet; `className` lets a template place and shape the burger itself. */
export function KxMenu({ nav, book, bookLabel, className = "" }: { nav: LnNav[]; book: boolean; bookLabel: string; className?: string }) {
  const { t } = useI18n();
  const path = usePathname();
  const [openAt, setOpenAt] = useState<string | null>(null);
  const open = openAt === path;
  const setOpen = (v: boolean) => setOpenAt(v ? path : null);
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.body.style.overflow = "hidden"; window.addEventListener("keydown", k);
    return () => { document.body.style.overflow = ""; window.removeEventListener("keydown", k); };
  }, [open]);
  return (
    <>
      <button type="button" className={`kx-burger ${className}`} aria-label={t("lnx.menu")} aria-expanded={open} onClick={() => setOpen(true)}>
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M3 8h18M3 16h18" /></svg>
      </button>
      {open && (
        <div className="kx-sheet" role="dialog" aria-modal="true" aria-label={t("lnx.menu")}>
          <div className="kx-sheet-top"><span>{t("lnx.menu")}</span>
            <button type="button" className="kx-burger" aria-label={t("lnx.close")} onClick={() => setOpen(false)}><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M5 5l14 14M19 5L5 19" /></svg></button>
          </div>
          <nav><Link href="/">{t("lnx.home")}</Link>{nav.map((n) => <Link key={n.key} href={n.href}>{n.label}</Link>)}</nav>
          {book && <Link href="/book" className="kx-btn">{bookLabel}<Arrow /></Link>}
        </div>
      )}
    </>
  );
}

/** Link that marks itself current (for template navigation bars). */
export function KxLink({ href, className, children }: { href: string; className?: string; children: ReactNode }) {
  const path = usePathname();
  const cur = path === href || (href !== "/" && path.startsWith(href + "/"));
  return <Link href={href} className={className} aria-current={cur ? "page" : undefined}>{children}</Link>;
}

function Fld({ label, err, children }: { label: string; err?: string | null; children: ReactNode }) {
  return <label className={`kx-fld${err ? " bad" : ""}`}><span>{label}</span>{children}{err && <span className="kx-err" role="alert">{err}</span>}</label>;
}

function BookInner({ services, phone, open }: { services: Svc[]; phone: string; open: boolean }) {
  const b = useLnBooking(services);
  const { t, fe } = b;
  if (!open) return <div className="kx-empty" style={{ marginBlock: 48 }}><h3>{t("lws.bookingClosed")}</h3>{phone && <a href={`tel:${phone}`} className="kx-btn" dir="ltr">{phone}</a>}</div>;
  if (b.done) return (
    <div className="kx-done">
      <p className="kx-eyebrow">{b.done.status === "APPROVED" ? t("lws.doneApproved") : t("lws.donePending")}</p>
      <h1>{b.done.guestName}</h1>
      <p style={{ fontSize: "1.25rem" }}>{b.fmtWhen(b.done.startsAt)}</p>
      <div><p style={{ color: "var(--text2)" }}>{t("lws.yourCode")}</p><p className="kx-code" dir="ltr">{b.done.code}</p><p style={{ color: "var(--text2)", marginBlockStart: 10 }}>{t("lws.codeHint")}</p></div>
      <div className="kx-cta-row"><Link href={`/booking?code=${b.done.code}`} className="kx-btn">{t("lws.track")}<Arrow /></Link><Link href="/" className="kx-btn kx-btn-line">{t("lws.backHome")}</Link></div>
    </div>
  );
  const fullyBooked = b.days.data && !b.firstFree;
  const n = services.length > 1 ? 1 : 0;
  return (
    <form className="kx-bk" onSubmit={(e) => { e.preventDefault(); void b.send.run(); }} noValidate>
      <div>
        {services.length > 1 && (
          <div className="kx-step"><div className="kx-step-h"><span>1</span><h2>{t("lws.chooseService")}</h2></div>
            <div className="kx-opt">{services.map((s) => (
              <button type="button" key={s.id} className="kx-pick" aria-pressed={b.service === s.id} onClick={() => b.pickService(s.id)}>
                <b>{s.name}</b><small>{t("lws.minutes", { n: s.durationMinutes })} · {t(`lws.mode.${s.mode}`)}{b.price(s) ? ` · ${b.price(s)}` : ""}</small>
              </button>))}</div>
          </div>
        )}
        <div className="kx-step"><div className="kx-step-h"><span>{n + 1}</span><h2>{t("lws.chooseTime")}</h2></div>
          {b.loadError && <div className="kx-note" role="alert">{b.loadError} <button type="button" className="kx-btn kx-btn-line" onClick={() => void b.reloadTimes()}>{t("lnx.err.retry")}</button></div>}
          {b.days.loading && !b.days.data && <div className="kx-sk" aria-hidden="true" />}
          {fullyBooked && <div className="kx-note">{t("lws.fullyBooked")}</div>}
          {b.days.data && b.firstFree && (
            <>
              <div className="kx-days" role="group">{b.days.data.map((d) => (
                <button type="button" key={d.date} className="kx-pick" disabled={d.free === 0} aria-pressed={b.chosenDay === d.date} onClick={() => b.pickDay(d.date)}>
                  <small>{b.fmtDay(d.date, { weekday: "short" })}</small><b>{b.fmtDay(d.date, { day: "numeric" })}</b><small>{b.fmtDay(d.date, { month: "short" })}</small>
                </button>))}</div>
              {b.slots.loading && !b.slots.data ? <div className="kx-sk" aria-hidden="true" />
                : b.slots.data && b.slots.data.length === 0 ? <div className="kx-note">{t("lws.noTimes")}</div>
                : <div className="kx-times" role="group">{(b.slots.data ?? []).map((s) => <button type="button" key={s} className="kx-pick" aria-pressed={b.time === s} onClick={() => b.pickTime(s)}>{b.fmtTime(s)}</button>)}</div>}
              {fe.err("startsAt") && <p className="kx-err" role="alert">{fe.err("startsAt")}</p>}
            </>
          )}
        </div>
        <div className="kx-step"><div className="kx-step-h"><span>{n + 2}</span><h2>{t("lws.yourDetails")}</h2></div>
          <Fld label={t("lws.name")} err={fe.err("name")}><input className="kx-in" autoComplete="name" value={b.f.name} onChange={(e) => b.setField("name", e.target.value)} /></Fld>
          <Fld label={t("lws.phone")} err={fe.err("phone")}><input className="kx-in" dir="ltr" inputMode="tel" autoComplete="tel" value={b.f.phone} onChange={(e) => b.setField("phone", e.target.value)} /></Fld>
          <Fld label={t("lws.topic")} err={fe.err("topic")}><textarea className="kx-in" rows={3} placeholder={t("lws.topicPh")} value={b.f.topic} onChange={(e) => b.setField("topic", e.target.value)} /></Fld>
          <p className="kx-note">{t("lws.private")}</p>
        </div>
      </div>
      <aside>
        <div className="kx-sum"><h3>{t("lws.summary")}</h3>
          <dl style={{ margin: 0 }}>
            <div><dt>{t("lws.session")}</dt><dd>{b.svc?.name ?? "—"}</dd></div>
            <div><dt>{t("lws.when")}</dt><dd>{b.time ? b.fmtWhen(b.time, false) : "—"}</dd></div>
            {b.svc && <div><dt>{t("lws.duration")}</dt><dd>{t("lws.minutes", { n: b.svc.durationMinutes })}</dd></div>}
            {b.price(b.svc) && <div><dt>{t("lws.fee")}</dt><dd>{b.price(b.svc)}</dd></div>}
          </dl>
          <p style={{ fontSize: ".85rem", opacity: .75, marginBlockStart: 8 }}>{t("lws.payAtOffice")}</p>
          {b.errorText && <p className="kx-err" role="alert">{b.errorText}</p>}
          <button type="submit" className="kx-btn" disabled={b.send.loading}>{t("lws.confirm")}<Arrow /></button>
        </div>
      </aside>
    </form>
  );
}
export function KxBook(props: { services: Svc[]; phone: string; open: boolean }) { return <Suspense><BookInner {...props} /></Suspense>; }

function TrackInner() {
  const k = useLnTrack();
  const { t, b } = k;
  return (
    <div className="kx-wrap" style={{ paddingBlock: "clamp(36px,6vw,72px)", maxInlineSize: 740, display: "grid", gap: 24 }}>
      <form className="kx-step" onSubmit={(e) => { e.preventDefault(); void k.find.run(); }}>
        <Fld label={t("lws.code")}><input className="kx-in" dir="ltr" style={{ textTransform: "uppercase", letterSpacing: ".15em" }} value={k.code} onChange={(e) => k.setCode(e.target.value)} /></Fld>
        <Fld label={t("lws.phone")}><input className="kx-in" dir="ltr" inputMode="tel" value={k.phone} onChange={(e) => k.setPhone(e.target.value)} /></Fld>
        <ErrorText error={k.find.error} />
        <div><button type="submit" className="kx-btn" disabled={k.find.loading}>{t("lws.find")}<Arrow /></button></div>
      </form>
      {b && (
        <div className="kx-box" style={{ display: "grid", gap: 14 }}>
          <span className={`kx-st ${STATUS_TONE[b.status] ?? "off"}`}>{t(`lws.st.${b.status}`)}</span>
          <h3 style={{ fontSize: "1.7rem" }}>{k.when(b.startsAt)}</h3>
          {b.serviceName && <p style={{ color: "var(--text2)" }}>{b.serviceName}</p>}
          {b.decisionNote && <p className="kx-note">{b.decisionNote}</p>}
          <ErrorText error={k.cancel.error} />
          {(b.status === "PENDING" || b.status === "APPROVED") && <div><button type="button" className="kx-btn kx-btn-line" disabled={k.cancel.loading} onClick={() => void k.cancel.run()}>{t("lws.cancelBooking")}</button></div>}
        </div>
      )}
    </div>
  );
}
export function KxTrack() { return <Suspense><TrackInner /></Suspense>; }
