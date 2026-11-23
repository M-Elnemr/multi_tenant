import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks } from "@/components/social-icons";
import { whatsappLink } from "@/lib/whatsapp";
import type { FooterProps } from "../types";

export function MarketFooter({ p, name, branches, t, poweredLabel, poweredName }: FooterProps) {
  const phones = [p.supportPhone, ...(p.extraPhones ?? [])].filter(Boolean) as string[];
  return (
    <footer className="mk-foot">
      <div className="lg-wrap mk-foot-grid">
        <div className="mk-foot-about">
          <b className="mk-foot-name">{name}</b>
          {(p.shortDescription || p.about) && <p>{p.shortDescription || p.about}</p>}
          <div className="mk-foot-social"><SocialLinks p={p} />{p.whatsapp && <a href={whatsappLink(p.whatsapp)} target="_blank" rel="noopener noreferrer" className="lg-chip">WhatsApp</a>}</div>
        </div>
        <nav aria-label={t("footer.shop")}>
          <h3>{t("footer.shop")}</h3>
          <ul>
            <li><Link href="/products">{t("shop.allProducts")}</Link></li>
            <li><Link href="/products?onSale=true&sort=discount">{t("shop.offers")}</Link></li>
            <li><Link href="/products?sort=newest">{t("shop.newest")}</Link></li>
            <li><Link href="/track">{t("shop.trackOrder")}</Link></li>
            <li><Link href="/account">{t("nav.account")}</Link></li>
          </ul>
        </nav>
        <nav aria-label={t("footer.help")}>
          <h3>{t("footer.help")}</h3>
          <ul>
            <li><Link href="/p/shipping">{t("policy.shipping")}</Link></li>
            <li><Link href="/p/returns">{t("policy.returns")}</Link></li>
            <li><Link href="/p/privacy">{t("policy.privacy")}</Link></li>
            <li><Link href="/p/terms">{t("policy.terms")}</Link></li>
            <li><Link href="/contact">{t("footer.contact")}</Link></li>
          </ul>
        </nav>
        <div>
          <h3>{t("footer.contact")}</h3>
          <ul>
            {phones.map((ph) => <li key={ph}><a href={`tel:${ph}`} dir="ltr">{ph}</a></li>)}
            {p.supportEmail && <li><a href={`mailto:${p.supportEmail}`} dir="ltr">{p.supportEmail}</a></li>}
            {p.addressText && <li>{p.addressText}</li>}
            {branches.length > 0 && <li><Link href="/branches">{t("shop.branches")} ({branches.length})</Link></li>}
          </ul>
        </div>
      </div>
      <div className="lg-wrap mk-foot-base"><span>© {new Date().getFullYear()} {name}</span>{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</div>
    </footer>
  );
}
