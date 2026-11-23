import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { SocialLinks } from "@/components/social-icons";
import type { FooterProps } from "../types";

export function MinimalFooter({ p, name, t, poweredLabel, poweredName }: FooterProps) {
  const phone = p.supportPhone;
  return (
    <footer className="mn-foot">
      <div className="mn-wrap mn-foot-row">
        <span className="mn-foot-name">{name}</span>
        <nav aria-label={t("footer.help")}>
          <Link href="/p/shipping">{t("policy.shipping")}</Link><Link href="/p/returns">{t("policy.returns")}</Link><Link href="/p/privacy">{t("policy.privacy")}</Link><Link href="/p/terms">{t("policy.terms")}</Link><Link href="/track">{t("shop.trackOrder")}</Link><Link href="/contact">{t("footer.contact")}</Link>
        </nav>
        <span className="mn-foot-c">{phone && <a href={`tel:${phone}`} dir="ltr">{phone}</a>}{p.supportEmail && <a href={`mailto:${p.supportEmail}`} dir="ltr">{p.supportEmail}</a>}</span>
      </div>
      <div className="mn-wrap mn-foot-row mn-foot-base"><span>© {new Date().getFullYear()} {name}</span><SocialLinks p={p} size={32} />{poweredLabel && <PoweredBy label={poweredLabel} name={poweredName} />}</div>
    </footer>
  );
}
