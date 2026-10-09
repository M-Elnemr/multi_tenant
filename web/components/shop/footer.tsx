import Link from "next/link";
import { PoweredBy } from "../brand";
import { whatsappLink } from "@/lib/whatsapp";
import { HoursList } from "./hours";
import type { ShopBranch, ShopProfile } from "./types";

const SOCIAL = [
  { key: "facebookUrl", label: "Facebook", path: "M14 8h3V4h-3a4 4 0 0 0-4 4v2H7v4h3v8h4v-8h3l1-4h-4V8.5A.5.5 0 0 1 14 8Z" },
  { key: "instagramUrl", label: "Instagram", path: "M7 3h10a4 4 0 0 1 4 4v10a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4Zm5 5a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm5.5-1.5h.01" },
  { key: "tiktokUrl", label: "TikTok", path: "M14 3v11.5a3.5 3.5 0 1 1-3.5-3.5M14 3c.3 2.6 2 4.2 5 4.4" },
  { key: "websiteUrl", label: "Web", path: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" },
] as const;

export function ShopFooter({ p, name, logoUrl, branches, t, poweredLabel, poweredName }: {
  p: ShopProfile; name: string; logoUrl: string | null; branches: ShopBranch[]; t: (k: string, v?: Record<string, string | number>) => string; poweredLabel: string; poweredName: string;
}) {
  const phones = [p.supportPhone, ...(p.extraPhones ?? [])].filter(Boolean) as string[];
  return (
    <footer className="mt-24 bg-[#15120f] text-[#e9e3da]">
      <div className="s-container grid gap-10 py-14 sm:grid-cols-2 lg:grid-cols-4">
        <div className="space-y-4">
          <div className="flex items-center gap-3">
            {logoUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={logoUrl} alt={name} className="h-12 w-12 rounded-2xl bg-white object-contain p-0.5" />
            ) : (
              <span className="grid h-12 w-12 place-items-center rounded-2xl bg-brand-gradient text-xl font-extrabold text-white">{name.trim().charAt(0).toUpperCase()}</span>
            )}
            <span className="text-xl font-extrabold text-white">{name}</span>
          </div>
          {(p.shortDescription || p.about) && <p className="line-clamp-4 text-sm leading-relaxed text-[#b9b1a6]">{p.shortDescription || p.about}</p>}
          <div className="flex gap-2">
            {SOCIAL.map((s) => p[s.key] ? (
              <a key={s.key} href={p[s.key] as string} target="_blank" rel="noopener noreferrer" aria-label={s.label} className="grid h-10 w-10 place-items-center rounded-full bg-white/10 transition hover:bg-[var(--brand)]">
                <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round"><path d={s.path} /></svg>
              </a>
            ) : null)}
            {p.whatsapp && <a href={whatsappLink(p.whatsapp)} target="_blank" rel="noopener noreferrer" aria-label="WhatsApp" className="grid h-10 w-10 place-items-center rounded-full bg-emerald-500 text-white transition hover:bg-emerald-400"><svg viewBox="0 0 32 32" className="h-5 w-5" fill="currentColor"><path d="M16.04 3C9.4 3 4 8.4 4 15.03c0 2.12.56 4.19 1.62 6.01L4 29l8.14-1.59a12 12 0 0 0 3.9.65C22.68 28.06 28 22.66 28 16.03 28 9.4 22.68 3 16.04 3Zm5.5 19.57c-.3-.15-1.79-.88-2.07-.98-.28-.1-.48-.15-.68.15-.2.3-.78.98-.96 1.18-.18.2-.35.22-.65.07-.3-.15-1.28-.47-2.43-1.5-.9-.8-1.5-1.79-1.68-2.09-.18-.3-.02-.46.13-.61.14-.13.3-.35.45-.52.15-.18.2-.3.3-.5.1-.2.05-.38-.02-.53-.08-.15-.68-1.64-.93-2.25-.25-.59-.5-.51-.68-.52h-.58c-.2 0-.53.08-.8.38-.28.3-1.05 1.03-1.05 2.5 0 1.48 1.08 2.9 1.23 3.1.15.2 2.12 3.2 5.1 4.49.71.3 1.27.49 1.7.62.72.23 1.37.2 1.88.12.57-.08 1.79-.73 2.04-1.44.25-.7.25-1.3.18-1.44-.08-.12-.28-.2-.58-.35Z" /></svg></a>}
          </div>
        </div>
        <div>
          <h3 className="mb-4 text-sm font-extrabold uppercase tracking-wider text-white">{t("footer.shop")}</h3>
          <ul className="space-y-2.5 text-sm text-[#b9b1a6]">
            <li><Link href="/products" className="hover:text-white">{t("shop.allProducts")}</Link></li>
            <li><Link href="/products?onSale=true" className="hover:text-white">{t("shop.offers")}</Link></li>
            <li><Link href="/branches" className="hover:text-white">{t("shop.branches")}</Link></li>
            <li><Link href="/track" className="hover:text-white">{t("shop.trackOrder")}</Link></li>
            <li><Link href="/account" className="hover:text-white">{t("nav.account")}</Link></li>
          </ul>
          <h3 className="mb-4 mt-8 text-sm font-extrabold uppercase tracking-wider text-white">{t("footer.help")}</h3>
          <ul className="space-y-2.5 text-sm text-[#b9b1a6]">
            <li><Link href="/p/shipping" className="hover:text-white">{t("policy.shipping")}</Link></li>
            <li><Link href="/p/returns" className="hover:text-white">{t("policy.returns")}</Link></li>
            <li><Link href="/p/privacy" className="hover:text-white">{t("policy.privacy")}</Link></li>
            <li><Link href="/p/terms" className="hover:text-white">{t("policy.terms")}</Link></li>
          </ul>
        </div>
        <div>
          <h3 className="mb-4 text-sm font-extrabold uppercase tracking-wider text-white">{t("footer.contact")}</h3>
          <ul className="space-y-3 text-sm text-[#b9b1a6]">
            {p.addressText && <li className="flex gap-2"><span>📍</span><span>{p.addressText}{p.mapsUrl && <> · <a href={p.mapsUrl} target="_blank" rel="noopener noreferrer" className="font-semibold text-white underline">{t("shop.openMap")}</a></>}</span></li>}
            {phones.map((ph) => <li key={ph} className="flex gap-2"><span>📞</span><a href={`tel:${ph}`} dir="ltr" className="hover:text-white">{ph}</a></li>)}
            {p.supportEmail && <li className="flex gap-2"><span>✉️</span><a href={`mailto:${p.supportEmail}`} className="break-all hover:text-white">{p.supportEmail}</a></li>}
          </ul>
        </div>
        <div>
          <h3 className="mb-4 text-sm font-extrabold uppercase tracking-wider text-white">{t("identity.hours")}</h3>
          <HoursList hours={p.workingHours} t={t} className="text-[#b9b1a6]" />
          {branches.length > 1 && <p className="mt-4 text-sm text-[#b9b1a6]">{t("shop.branchesCount", { n: branches.length })} <Link href="/branches" className="font-semibold text-white underline">{t("shop.viewAll")}</Link></p>}
          <div className="mt-6 rounded-2xl bg-white/5 p-4 text-sm"><p className="font-bold text-white">💵 {t("shop.codTitle")}</p><p className="mt-1 text-[#b9b1a6]">{t("shop.codText")}</p></div>
        </div>
      </div>
      <div className="border-t border-white/10">
        <div className="s-container flex flex-col items-center justify-between gap-3 py-5 text-xs text-[#8f877c] sm:flex-row">
          <span>© {new Date().getFullYear()} {name}. {t("footer.rights")}</span>
          <PoweredBy label={poweredLabel} name={poweredName} />
        </div>
      </div>
    </footer>
  );
}
