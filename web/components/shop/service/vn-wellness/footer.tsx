import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { whatsappLink } from "@/lib/whatsapp";
import { dayRanges, todayWeekday } from "../vn-core/data";
import type { FooterProps } from "../vn-core/types";

export function WellnessFooter({ name, hasProducts, hasBooking, data, poweredLabel, poweredName }: FooterProps) {
  const { p, t, cfg } = data;
  const today = dayRanges(cfg, todayWeekday(cfg.timezone));
  const phones = [p?.supportPhone, ...(p?.extraPhones ?? [])].filter(Boolean) as string[];
  return (
    <footer className="wl-footer">
      <div className="wl-wrap">
        <div className="wl-foot-grid">
          <div><p className="wl-foot-brand">{name}</p>{(p?.shortDescription || p?.about) && <p>{(p.shortDescription || p.about || "").slice(0, 220)}</p>}{p && hasSocial(p) && <div style={{ marginTop: 18 }}><SocialLinks p={p} /></div>}</div>
          <nav aria-label={t("shop.menu")}><h3>{t("shop.menu")}</h3><ul>
            <li><Link href="/services">{t("vn.wl.nav.treatments")}</Link></li>
            {hasBooking && <li><Link href="/book">{t("vn.wl.reserve")}</Link></li>}
            {hasBooking && <li><Link href="/book/manage">{t("vn.nav.myBooking")}</Link></li>}
            {hasProducts && <li><Link href="/products">{t("vn.wl.nav.boutique")}</Link></li>}
            <li><Link href="/contact">{t("shop.contact")}</Link></li>
          </ul></nav>
          <div><h3>{t("vn.wl.visitTitle")}</h3><ul>
            {today && <li><span>{t("vn.today")}:&nbsp;<bdi dir="ltr" className="tnum">{today}</bdi></span></li>}
            {p?.whatsapp && !phones.includes(p.whatsapp) && <li><a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" dir="ltr">{p.whatsapp}</a></li>}
            {phones.map((x) => <li key={x}><a href={`tel:${x}`} dir="ltr">{x}</a></li>)}
            {p?.addressText && <li><span>{p.addressText}</span></li>}
          </ul></div>
        </div>
        <div className="wl-foot-base"><span>© {new Date().getFullYear()} {name}</span>{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</div>
      </div>
    </footer>
  );
}
