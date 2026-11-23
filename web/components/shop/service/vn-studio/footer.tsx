import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { whatsappLink } from "@/lib/whatsapp";
import { phonesOf } from "../vn-core/shared";
import type { FooterProps } from "../vn-core/types";

/** A lime footer with the name set huge, then the plain facts in a row. */
export function StudioFooter({ name, hasProducts, hasBooking, data, poweredLabel, poweredName }: FooterProps) {
  const { p, t } = data;
  const phones = phonesOf(data);
  return (
    <footer className="st-footer">
      <div className="st-wrap">
        <p className="st-foot-name" aria-hidden>{name}</p>
        <div className="st-foot-grid">
          <nav aria-label={t("shop.menu")}><Link href="/services">{t("vn.st.nav.sessions")}</Link>{hasBooking && <Link href="/book">{t("vn.st.book")}</Link>}{hasBooking && <Link href="/book/manage">{t("vn.nav.myBooking")}</Link>}{hasProducts && <Link href="/products">{t("vn.st.nav.shop")}</Link>}<Link href="/contact">{t("vn.st.nav.contact")}</Link></nav>
          <div>{phones.map((x) => <a key={x} href={`tel:${x}`} dir="ltr">{x}</a>)}{p?.whatsapp && !phones.includes(p.whatsapp) && <a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" dir="ltr">{p.whatsapp}</a>}{p?.supportEmail && <a href={`mailto:${p.supportEmail}`}>{p.supportEmail}</a>}{p?.addressText && <span>{p.addressText}</span>}</div>
          <div>{p && hasSocial(p) && <SocialLinks p={p} />}</div>
        </div>
        <div className="st-foot-base"><span>© {new Date().getFullYear()} {name}</span>{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</div>
      </div>
    </footer>
  );
}
