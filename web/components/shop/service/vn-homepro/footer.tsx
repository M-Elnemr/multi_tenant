import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { Icon } from "@/components/icons";
import { whatsappLink } from "@/lib/whatsapp";
import { phonesOf } from "../vn-core/shared";
import type { FooterProps } from "../vn-core/types";

/** A compact one-row footer: contact first, a few links, nothing decorative. */
export function HomeproFooter({ name, hasProducts, hasBooking, data, poweredLabel, poweredName }: FooterProps) {
  const { p, t } = data;
  const phones = phonesOf(data);
  return (
    <footer className="hp-footer">
      <div className="hp-wrap hp-foot-in">
        <div className="hp-foot-col"><b className="hp-foot-name">{name}</b>{p?.addressText && <p>{p.addressText}</p>}{p && hasSocial(p) && <SocialLinks p={p} />}</div>
        <div className="hp-foot-col"><h3>{t("shop.contact")}</h3>
          {phones.map((x) => <a key={x} href={`tel:${x}`} dir="ltr"><Icon name="phone" className="h-4 w-4" />{x}</a>)}
          {p?.whatsapp && !phones.includes(p.whatsapp) && <a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" dir="ltr"><Icon name="chat" className="h-4 w-4" />{p.whatsapp}</a>}
          {p?.supportEmail && <a href={`mailto:${p.supportEmail}`}><Icon name="mail" className="h-4 w-4" />{p.supportEmail}</a>}</div>
        <nav className="hp-foot-col" aria-label={t("shop.menu")}><h3>{t("shop.menu")}</h3><Link href="/services">{t("svh.services")}</Link>{hasBooking && <Link href="/book">{t("vn.hp.request")}</Link>}{hasBooking && <Link href="/book/manage">{t("vn.nav.myBooking")}</Link>}{hasProducts && <Link href="/products">{t("svh.products")}</Link>}<Link href="/contact">{t("shop.contact")}</Link></nav>
      </div>
      <div className="hp-wrap hp-foot-base"><span>© {new Date().getFullYear()} {name}</span>{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</div>
    </footer>
  );
}
