import Link from "next/link";
import { HoursList } from "@/components/shop/hours";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { whatsappLink } from "@/lib/whatsapp";
import type { ContactProps, PolicyProps } from "../types";

/** Policy text set as an essay: narrow serif column, large title, a line to the other policies. */
export function EditorialPolicy({ p, slug, title, body, t }: PolicyProps) {
  const others = ["shipping", "returns", "privacy", "terms"].filter((x) => x !== slug);
  return (
    <article className="ed-wrap ed-page ed-essay">
      <p className="ed-eyebrow">{p.storeName}</p>
      <h1>{title}</h1>
      {body ? <p className="ed-prose">{body}</p> : <p className="ed-muted">{t("policy.empty")}</p>}
      <nav className="ed-subcats" aria-label={t("footer.help")}>{others.map((x) => <Link key={x} href={`/p/${x}`}>{t(`policy.${x}`)}</Link>)}</nav>
    </article>
  );
}

export function EditorialContact({ p, t }: ContactProps) {
  const phones = [p.supportPhone, ...(p.extraPhones ?? [])].filter(Boolean) as string[];
  const row = (label: string, body: React.ReactNode) => <div className="ed-crow"><dt>{label}</dt><dd>{body}</dd></div>;
  return (
    <div className="ed-wrap ed-page">
      <header className="ed-title-block"><p className="ed-eyebrow">{t("shop.contact")}</p><h1>{t("contact.title")}</h1><p className="ed-lede">{t("contact.subtitle")}</p></header>
      <div className="ed-contact">
        <dl>
          {p.whatsapp && row("WhatsApp", <a href={whatsappLink(p.whatsapp, `${p.storeName}`)} target="_blank" rel="noopener noreferrer" dir="ltr">{p.whatsapp}</a>)}
          {phones.length > 0 && row(t("identity.phone"), phones.map((x) => <a key={x} href={`tel:${x}`} dir="ltr">{x}</a>))}
          {p.supportEmail && row(t("identity.email"), <a href={`mailto:${p.supportEmail}`} dir="ltr">{p.supportEmail}</a>)}
          {p.addressText && row(t("identity.address"), <>{p.addressText}{p.mapsUrl && <a href={p.mapsUrl} target="_blank" rel="noopener noreferrer">{t("identity.mapsUrl")}</a>}</>)}
          {hasSocial(p) && row(t("contact.follow"), <SocialLinks p={p} withLabel />)}
        </dl>
        <div>
          <h2 className="ed-h2">{t("identity.hours")}</h2>
          {p.workingHours && Object.keys(p.workingHours).length > 0 ? <HoursList hours={p.workingHours} t={t} /> : <p className="ed-muted">{t("contact.noHours")}</p>}
        </div>
      </div>
      {p.about && <section className="ed-gap"><h2 className="ed-h2">{t("settings.about")}</h2><p className="ed-prose">{p.about}</p></section>}
    </div>
  );
}
