import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { whatsappLink } from "@/lib/whatsapp";
import { groupServices } from "../vn-core/data";
import { phonesOf } from "../vn-core/shared";
import type { FooterProps } from "../vn-core/types";

/** A directory footer: every category is a link, then pages, then contact. */
export function MultiFooter({ name, hasProducts, hasBooking, data, poweredLabel, poweredName }: FooterProps) {
  const { p, t, cfg } = data;
  const phones = phonesOf(data);
  const cats = groupServices(cfg).filter((g) => g.name);
  return (
    <footer className="mu-footer">
      <div className="mu-wrap">
        <div className="mu-foot-grid">
          <div className="mu-foot-about"><b>{name}</b>{(p?.shortDescription || p?.about) && <p>{(p.shortDescription || p.about || "").slice(0, 200)}</p>}{p && hasSocial(p) && <SocialLinks p={p} />}</div>
          {cats.length > 0 && <nav aria-label={t("vn.categories")} className="mu-foot-cats"><h3>{t("vn.categories")}</h3><ul>{cats.map((g) => <li key={g.key}><Link href={`/services?cat=${encodeURIComponent(g.key)}`}>{g.name}</Link></li>)}</ul></nav>}
          <nav aria-label={t("shop.menu")}><h3>{t("shop.menu")}</h3><ul><li><Link href="/services">{t("svh.services")}</Link></li>{hasBooking && <li><Link href="/book">{t("vn.mu.book")}</Link></li>}{hasBooking && <li><Link href="/book/manage">{t("vn.nav.myBooking")}</Link></li>}{hasProducts && <li><Link href="/products">{t("svh.products")}</Link></li>}<li><Link href="/contact">{t("shop.contact")}</Link></li></ul></nav>
          <div><h3>{t("shop.contact")}</h3><ul>{phones.map((x) => <li key={x}><a href={`tel:${x}`} dir="ltr">{x}</a></li>)}{p?.whatsapp && !phones.includes(p.whatsapp) && <li><a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" dir="ltr">{p.whatsapp}</a></li>}{p?.addressText && <li><span>{p.addressText}</span></li>}</ul></div>
        </div>
        <div className="mu-foot-base"><span>© {new Date().getFullYear()} {name}</span>{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</div>
      </div>
    </footer>
  );
}
