import { Icon } from "@/components/icons";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { HoursList } from "@/components/shop/hours";
import { governorateName } from "@/lib/governorates";
import { whatsappLink } from "@/lib/whatsapp";
import { WEEK_ORDER, dayRanges, todayWeekday } from "./data";
import type { VnData } from "./types";
import "./form.css";

/** Weekly hours from the booking schedule (the times customers can really book); the profile's hours only when there is no schedule. */
export function VnWeek({ d }: { d: VnData }) {
  const { cfg, p, t } = d;
  if (cfg.hours.length === 0) return p?.workingHours && Object.keys(p.workingHours).length ? <HoursList hours={p.workingHours} t={t} /> : <p className="vf-muted">{t("contact.noHours")}</p>;
  const now = todayWeekday(cfg.timezone);
  return (
    <ul className="vf-hours">
      {WEEK_ORDER.map((w) => { const r = dayRanges(cfg, w); return <li key={w} data-today={w === now ? "true" : undefined}><span>{t(`bk.day.${w}`)}</span><bdi dir="ltr" className="tnum">{r ?? t("hours.closed")}</bdi></li>; })}
    </ul>
  );
}

export const phonesOf = (d: VnData) => [d.p?.supportPhone, ...(d.p?.extraPhones ?? [])].filter(Boolean) as string[];

/** The contact page shared by the vn-* designs: how to reach the business, the weekly hours, branches and the about text. Only real saved data is shown. */
export function VnContact(d: VnData) {
  const { p, t, branches, locale } = d;
  const phones = phonesOf(d);
  return (
    <div className="vf-page vf-wrap">
      <div className="vf-head"><p className="vf-kicker">{t("shop.contact")}</p><h1 className="vf-h1">{t("contact.title")}</h1></div>
      <div className="vf-two">
        <div className="vf-panel">
          <ul className="vf-contact">
            {p?.whatsapp && <li><Icon name="chat" className="h-5 w-5" /><a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" dir="ltr">{p.whatsapp}</a></li>}
            {phones.map((x) => <li key={x}><Icon name="phone" className="h-5 w-5" /><a href={`tel:${x}`} dir="ltr">{x}</a></li>)}
            {p?.supportEmail && <li><Icon name="mail" className="h-5 w-5" /><a href={`mailto:${p.supportEmail}`} className="break-all">{p.supportEmail}</a></li>}
            {p?.addressText && <li><Icon name="pin" className="h-5 w-5" /><span>{p.addressText}{p.mapsUrl && <> · <a href={p.mapsUrl} target="_blank" rel="noopener noreferrer" className="vf-link">{t("shop.openMap")}</a></>}</span></li>}
          </ul>
          {p && hasSocial(p) && <div style={{ marginTop: 18 }}><SocialLinks p={p} withLabel /></div>}
          {!p?.whatsapp && phones.length === 0 && !p?.supportEmail && !p?.addressText && <p className="vf-muted">{t("vn.noContact")}</p>}
        </div>
        <div className="vf-panel"><h2 className="vf-h2">{t("identity.hours")}</h2><VnWeek d={d} /></div>
      </div>
      {branches.length > 0 && (
        <section className="vf-sec"><h2 className="vf-h2">{t("shop.branches")}</h2>
          <div className="vf-three">{branches.map((b) => (
            <div key={b.id} className="vf-panel"><b>{b.name}</b><p className="vf-muted">{[b.addressLine1, b.area, governorateName(b.governorateCode, locale) || b.city].filter(Boolean).join("، ")}</p>
              {b.phone && <p><a href={`tel:${b.phone}`} dir="ltr" className="vf-link">{b.phone}</a></p>}{b.mapsUrl && <p><a href={b.mapsUrl} target="_blank" rel="noopener noreferrer" className="vf-link">{t("shop.openMap")}</a></p>}</div>
          ))}</div></section>
      )}
      {p?.about && <section className="vf-sec"><div className="vf-panel"><h2 className="vf-h2">{t("settings.about")}</h2><p className="vf-muted" style={{ whiteSpace: "pre-line" }}>{p.about}</p></div></section>}
    </div>
  );
}

export const firstLetter = (s: string) => Array.from(s.trim())[0] ?? "";
