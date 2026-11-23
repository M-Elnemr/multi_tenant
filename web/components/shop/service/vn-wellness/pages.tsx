import Link from "next/link";
import { Reveal } from "@/components/shop/templates/reveal";
import { Icon } from "@/components/icons";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { HoursList } from "@/components/shop/hours";
import { governorateName } from "@/lib/governorates";
import { money } from "@/lib/format";
import { whatsappLink } from "@/lib/whatsapp";
import { WEEK_ORDER, dayRanges, groupServices, todayWeekday } from "../vn-core/data";
import { Pic } from "../vn-core/tools";
import type { DetailProps, VnData } from "../vn-core/types";
import { ArchCard, StaffRow, WlPrice, whereLabel } from "./parts";

/** Weekly hours from the booking schedule (the times customers can really book); the shop profile's hours are used only when there is no schedule. */
function Week({ d }: { d: VnData }) {
  const { cfg, p, t } = d;
  if (cfg.hours.length === 0) return p?.workingHours && Object.keys(p.workingHours).length ? <HoursList hours={p.workingHours} t={t} className="wl-hours" /> : <p className="wl-muted">{t("contact.noHours")}</p>;
  const now = todayWeekday(cfg.timezone);
  return (
    <ul className="wl-hours">
      {WEEK_ORDER.map((w) => { const r = dayRanges(cfg, w); return <li key={w} data-today={w === now ? "true" : undefined}><span>{t(`bk.day.${w}`)}</span><bdi dir="ltr" className="tnum">{r ?? t("hours.closed")}</bdi></li>; })}
    </ul>
  );
}

/** Opening hours and the place, as two quiet cards. Used on the home page and the contact page. */
export function WlVisit({ d }: { d: VnData }) {
  const { p, t, branches, locale } = d;
  const phones = [p?.supportPhone, ...(p?.extraPhones ?? [])].filter(Boolean) as string[];
  const place = [p?.addressText].filter(Boolean)[0] ?? "";
  const b0 = branches[0];
  const addr = place || (b0 ? [b0.addressLine1, b0.area, governorateName(b0.governorateCode, locale) || b0.city].filter(Boolean).join("، ") : "");
  const map = p?.mapsUrl || b0?.mapsUrl;
  return (
    <section id="visit" className="wl-sec wl-wrap">
      <Reveal className="wl-head"><p className="wl-kicker">{t("shop.visitUs")}</p><h2 className="wl-h2">{t("vn.wl.visitTitle")}</h2></Reveal>
      <div className="wl-visit">
        <Reveal className="wl-panel"><h3 className="wl-h3">{t("identity.hours")}</h3><Week d={d} /></Reveal>
        <Reveal className="wl-panel" delay={0.08}>
          <h3 className="wl-h3">{t("identity.address")}</h3>
          {addr ? <p className="wl-addr">{addr}</p> : <p className="wl-muted">{t("vn.noAddress")}</p>}
          {map && <a href={map} target="_blank" rel="noopener noreferrer" className="wl-more">{t("shop.openMap")}</a>}
          {phones.length > 0 && <p className="wl-phones">{phones.map((x) => <a key={x} href={`tel:${x}`} dir="ltr">{x}</a>)}</p>}
          <p className="wl-cta wl-cta-left"><Link href="/contact" className="wl-btn wl-btn-ghost wl-btn-sm">{t("shop.contact")}</Link>{p?.whatsapp && <a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" className="wl-btn wl-btn-sm"><Icon name="chat" className="h-4 w-4" />{t("shop.chatWithUs")}</a>}</p>
        </Reveal>
      </div>
    </section>
  );
}

/** All treatments, grouped by category, with category tabs. A visitor with many services sees every one; few services get large cards. */
export function WellnessCatalog({ d, cat }: { d: VnData; cat?: string }) {
  const { cfg, t, locale, failed } = d;
  const groups = groupServices(cfg);
  const shown = cat ? groups.filter((g) => g.key === cat) : groups;
  return (
    <div className="wl-page wl-wrap">
      <Reveal className="wl-head wl-head-l"><p className="wl-kicker">{t("vn.wl.menuKicker")}</p><h1 className="wl-h1 wl-h1-sm">{t("svh.services")}</h1></Reveal>
      {groups.length > 1 && <nav className="wl-tabs" aria-label={t("vn.categories")}><Link href="/services" data-on={!cat ? "true" : undefined}>{t("vn.all")}</Link>{groups.map((g) => <Link key={g.key} href={`/services?cat=${encodeURIComponent(g.key)}`} data-on={cat === g.key ? "true" : undefined}>{g.name || t("vn.other")}</Link>)}</nav>}
      {failed ? <p className="wl-empty">{t("vn.loadFailed")}</p> : cfg.services.length === 0 ? <p className="wl-empty">{t("svh.noServices")}</p> : shown.map((g) => (
        <section key={g.key} className="wl-group">
          {(g.name || groups.length > 1) && <h2 className="wl-h3 wl-group-title">{g.name || t("vn.other")}<span className="tnum">{g.items.length}</span></h2>}
          <div className="wl-grid">{g.items.map((s, i) => <ArchCard key={s.id} s={s} t={t} locale={locale} i={i} />)}</div>
        </section>
      ))}
    </div>
  );
}

export function WellnessDetail(d: DetailProps) {
  const { service: s, cfg, t, locale } = d;
  const pics = [s.imageFileId, ...(s.gallery ?? [])].filter((x): x is string => !!x);
  const related = cfg.services.filter((x) => x.id !== s.id && (x.categoryName || "") === (s.categoryName || "")).slice(0, 3);
  return (
    <div className="wl-page wl-wrap">
      <nav className="wl-crumbs" aria-label="breadcrumb"><Link href="/services">{t("svh.services")}</Link><span aria-hidden>/</span><span>{s.name}</span></nav>
      <div className="wl-detail">
        <div className="wl-detail-art">
          <div className="wl-arch wl-arch-d"><Pic id={pics[0]} variant="original" className="wl-arch-img" fallback={<div className="wl-arch-ph" aria-hidden><span>{Array.from(s.name.trim())[0] ?? ""}</span></div>} /></div>
          {pics.length > 1 && <ul className="wl-thumbs">{pics.slice(1, 5).map((id) => <li key={id}><Pic id={id} variant="thumb" className="wl-thumb" /></li>)}</ul>}
        </div>
        <div className="wl-detail-text">
          {s.categoryName && <p className="wl-kicker">{s.categoryName}</p>}
          <h1 className="wl-h1 wl-h1-sm">{s.name}</h1>
          <p className="wl-detail-price"><WlPrice s={s} locale={locale} /></p>
          <ul className="wl-facts"><li><Icon name="clock" className="h-4 w-4" />{s.durationMinutes} {t("bk.min")}</li><li><Icon name="pin" className="h-4 w-4" />{whereLabel(s, t)}</li></ul>
          {s.description && <p className="wl-copy">{s.description}</p>}
          {s.instructions && <div className="wl-note-box"><h3 className="wl-h3">{t("vn.beforeVisit")}</h3><p>{s.instructions}</p></div>}
          {(s.addons ?? []).length > 0 && (
            <div className="wl-addons"><h3 className="wl-h3">{t("book.options")}</h3><ul>{(s.addons ?? []).map((a) => <li key={a.id}><span>{a.name}</span><bdi className="tnum">+{money(a.priceMinor, s.currency, locale)} · +{a.durationMinutes} {t("bk.min")}</bdi></li>)}</ul></div>
          )}
          {s.serviceArea && <p className="wl-muted">{t("book.area", { area: s.serviceArea })}</p>}
          {s.cancellationPolicy && <p className="wl-muted">{s.cancellationPolicy}</p>}
          <p className="wl-cta wl-cta-left"><Link href={`/book?service=${s.id}`} className="wl-btn">{t("vn.wl.reserveThis")}</Link><Link href="/services" className="wl-btn wl-btn-ghost">{t("common.back")}</Link></p>
        </div>
      </div>
      <StaffRow staff={s.staff ?? []} kicker={t("vn.wl.teamKicker")} title={t("vn.withWhom")} />
      {related.length > 0 && <section className="wl-sec"><Reveal className="wl-head"><h2 className="wl-h2">{t("vn.related")}</h2></Reveal><div className="wl-grid">{related.map((x, i) => <ArchCard key={x.id} s={x} t={t} locale={locale} i={i} />)}</div></section>}
      <div className="wl-sticky"><Link href={`/book?service=${s.id}`} className="wl-btn">{t("vn.wl.reserveThis")}</Link></div>
    </div>
  );
}

export function WellnessContact(d: VnData) {
  const { p, t, branches, locale } = d;
  const phones = [p?.supportPhone, ...(p?.extraPhones ?? [])].filter(Boolean) as string[];
  return (
    <div className="wl-page wl-wrap">
      <Reveal className="wl-head wl-head-l"><p className="wl-kicker">{t("shop.contact")}</p><h1 className="wl-h1 wl-h1-sm">{t("contact.title")}</h1></Reveal>
      <div className="wl-visit">
        <div className="wl-panel">
          <ul className="wl-contact">
            {p?.whatsapp && <li><Icon name="chat" className="h-5 w-5" /><a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" dir="ltr">{p.whatsapp}</a></li>}
            {phones.map((x) => <li key={x}><Icon name="phone" className="h-5 w-5" /><a href={`tel:${x}`} dir="ltr">{x}</a></li>)}
            {p?.supportEmail && <li><Icon name="mail" className="h-5 w-5" /><a href={`mailto:${p.supportEmail}`} className="break-all">{p.supportEmail}</a></li>}
            {p?.addressText && <li><Icon name="pin" className="h-5 w-5" /><span>{p.addressText}{p.mapsUrl && <> · <a href={p.mapsUrl} target="_blank" rel="noopener noreferrer" className="wl-more">{t("shop.openMap")}</a></>}</span></li>}
          </ul>
          {p && hasSocial(p) && <div className="wl-social"><SocialLinks p={p} withLabel /></div>}
          {!p?.whatsapp && phones.length === 0 && !p?.supportEmail && !p?.addressText && <p className="wl-muted">{t("vn.noContact")}</p>}
        </div>
        <div className="wl-panel"><h2 className="wl-h3">{t("identity.hours")}</h2><Week d={d} /></div>
      </div>
      {branches.length > 0 && (
        <section className="wl-sec"><h2 className="wl-h2">{t("shop.branches")}</h2>
          <div className="wl-visit wl-visit-3">{branches.map((b) => (
            <div key={b.id} className="wl-panel"><h3 className="wl-h3">{b.name}</h3><p className="wl-addr">{[b.addressLine1, b.area, governorateName(b.governorateCode, locale) || b.city].filter(Boolean).join("، ")}</p>
              {b.phone && <a href={`tel:${b.phone}`} dir="ltr" className="wl-more">{b.phone}</a>}{b.mapsUrl && <a href={b.mapsUrl} target="_blank" rel="noopener noreferrer" className="wl-more">{t("shop.openMap")}</a>}</div>
          ))}</div></section>
      )}
      {p?.about && <section className="wl-sec"><div className="wl-panel"><h2 className="wl-h3">{t("settings.about")}</h2><p className="wl-copy">{p.about}</p></div></section>}
    </div>
  );
}
