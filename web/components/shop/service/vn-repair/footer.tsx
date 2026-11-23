import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { whatsappLink } from "@/lib/whatsapp";
import { dayRanges, groupServices, todayWeekday } from "../vn-core/data";
import { phonesOf } from "../vn-core/shared";
import type { FooterProps } from "../vn-core/types";

/** Workshop footer: categories as an index, today's hours as a status line, contact as a plain list. */
export function RepairFooter({ name, hasProducts, hasBooking, data, poweredLabel, poweredName }: FooterProps) {
  const { p, t, cfg } = data;
  const phones = phonesOf(data);
  const today = dayRanges(cfg, todayWeekday(cfg.timezone));
  const cats = groupServices(cfg).filter((g) => g.name).slice(0, 6);
  return (
    <footer className="rp-footer">
      <div className="rp-wrap">
        <div className="rp-foot-top"><b className="rp-foot-name">{name}</b>{today && <span className="rp-foot-status"><i aria-hidden />{t("vn.today")} <bdi dir="ltr" className="tnum">{today}</bdi></span>}</div>
        <div className="rp-foot-grid">
          {cats.length > 0 && <nav aria-label={t("vn.categories")}><h3>{t("vn.categories")}</h3><ul>{cats.map((g) => <li key={g.key}><Link href={`/services?cat=${encodeURIComponent(g.key)}`}>{g.name}</Link></li>)}</ul></nav>}
          <nav aria-label={t("shop.menu")}><h3>{t("shop.menu")}</h3><ul><li><Link href="/services">{t("vn.rp.nav.catalog")}</Link></li>{hasBooking && <li><Link href="/book">{t("vn.rp.book")}</Link></li>}{hasBooking && <li><Link href="/book/manage">{t("vn.rp.track")}</Link></li>}{hasProducts && <li><Link href="/products">{t("vn.rp.nav.parts")}</Link></li>}<li><Link href="/contact">{t("vn.rp.nav.find")}</Link></li></ul></nav>
          <div><h3>{t("shop.contact")}</h3><ul>{phones.map((x) => <li key={x}><a href={`tel:${x}`} dir="ltr">{x}</a></li>)}{p?.whatsapp && !phones.includes(p.whatsapp) && <li><a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" dir="ltr">{p.whatsapp}</a></li>}{p?.addressText && <li><span>{p.addressText}</span></li>}</ul>{p && hasSocial(p) && <div style={{ marginTop: 14 }}><SocialLinks p={p} /></div>}</div>
        </div>
        <div className="rp-foot-base"><span>© {new Date().getFullYear()} {name}</span>{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</div>
      </div>
    </footer>
  );
}
