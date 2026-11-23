import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks } from "@/components/social-icons";
import { whatsappLink } from "@/lib/whatsapp";
import type { FooterProps } from "../types";

export function EditorialFooter({ p, name, branches, t, poweredLabel, poweredName }: FooterProps) {
  const phones = [p.supportPhone, ...(p.extraPhones ?? [])].filter(Boolean) as string[];
  return (
    <footer className="ed-foot">
      <p className="ed-foot-mark">{name}</p>
      {(p.shortDescription || p.about) && <p className="ed-foot-tag">{p.shortDescription || p.about}</p>}
      <nav className="ed-foot-links" aria-label={t("footer.shop")}>
        <Link href="/products">{t("shop.allProducts")}</Link><Link href="/products?onSale=true&sort=discount">{t("shop.offers")}</Link><Link href="/track">{t("shop.trackOrder")}</Link>
        <Link href="/p/shipping">{t("policy.shipping")}</Link><Link href="/p/returns">{t("policy.returns")}</Link><Link href="/p/privacy">{t("policy.privacy")}</Link><Link href="/p/terms">{t("policy.terms")}</Link><Link href="/contact">{t("footer.contact")}</Link>
        {branches.length > 0 && <Link href="/branches">{t("shop.branches")}</Link>}
      </nav>
      <div className="ed-foot-contact">
        {phones.map((ph) => <a key={ph} href={`tel:${ph}`} dir="ltr">{ph}</a>)}
        {p.supportEmail && <a href={`mailto:${p.supportEmail}`} dir="ltr">{p.supportEmail}</a>}
        {p.whatsapp && <a href={whatsappLink(p.whatsapp)} target="_blank" rel="noopener noreferrer">WhatsApp</a>}
      </div>
      <div className="ed-foot-social"><SocialLinks p={p} /></div>
      <p className="ed-foot-base"><span>© {new Date().getFullYear()} {name}</span>{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</p>
    </footer>
  );
}
