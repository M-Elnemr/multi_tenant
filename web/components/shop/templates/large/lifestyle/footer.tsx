import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks } from "@/components/social-icons";
import { whatsappLink } from "@/lib/whatsapp";
import type { FooterProps } from "../types";

export function LifestyleFooter({ p, name, branches, t, poweredLabel, poweredName }: FooterProps) {
  const phones = [p.supportPhone, ...(p.extraPhones ?? [])].filter(Boolean) as string[];
  return (
    <footer className="lf-foot">
      <div className="lf-wrap">
        <section className="lf-promise" aria-label={name}>
          <h2>{name}</h2>
          {(p.shortDescription || p.about) && <p>{p.shortDescription || p.about}</p>}
          <div className="lf-promise-act"><SocialLinks p={p} />{p.whatsapp && <a href={whatsappLink(p.whatsapp)} target="_blank" rel="noopener noreferrer" className="lf-btn">WhatsApp</a>}</div>
        </section>
        <div className="lf-foot-grid">
          <nav aria-label={t("footer.shop")}><h3>{t("footer.shop")}</h3><Link href="/products">{t("shop.allProducts")}</Link><Link href="/products?onSale=true&sort=discount">{t("shop.offers")}</Link><Link href="/track">{t("shop.trackOrder")}</Link><Link href="/account">{t("nav.account")}</Link></nav>
          <nav aria-label={t("footer.help")}><h3>{t("footer.help")}</h3><Link href="/p/shipping">{t("policy.shipping")}</Link><Link href="/p/returns">{t("policy.returns")}</Link><Link href="/p/privacy">{t("policy.privacy")}</Link><Link href="/p/terms">{t("policy.terms")}</Link></nav>
          <div><h3>{t("footer.contact")}</h3>{phones.map((x) => <a key={x} href={`tel:${x}`} dir="ltr">{x}</a>)}{p.supportEmail && <a href={`mailto:${p.supportEmail}`} dir="ltr">{p.supportEmail}</a>}{branches.length > 0 && <Link href="/branches">{t("shop.branches")} ({branches.length})</Link>}<Link href="/contact">{t("footer.contact")}</Link></div>
        </div>
        <p className="lf-foot-base"><span>© {new Date().getFullYear()} {name}</span>{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</p>
      </div>
    </footer>
  );
}
