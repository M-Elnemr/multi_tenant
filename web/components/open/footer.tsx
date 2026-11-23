import Link from "next/link";
import type { Translate } from "@/lib/i18n";
import { Icon, type IconName } from "../icons";

export function OpenFooter({ t, rootDomain }: { t: Translate; rootDomain: string }) {
  const trust: { icon: IconName; a: string; b: string }[] = [
    { icon: "wallet", a: t("open.trust.cod"), b: t("open.trust.codSub") },
    { icon: "bag", a: t("open.trust.shops"), b: t("open.trust.shopsSub") },
    { icon: "navigate", a: t("open.trust.track"), b: t("open.trust.trackSub") },
    { icon: "star", a: t("open.trust.reviews"), b: t("open.trust.reviewsSub") },
  ];
  const col = "grid content-start text-sm text-white/65";
  const a = "flex min-h-11 items-center transition hover:text-white";
  return (
    <footer className="mt-16 bg-[var(--o-ink)] pb-5 text-white md:mt-24">
      <div className="border-b border-white/10">
        <div className="o-container grid grid-cols-1 gap-5 py-7 sm:grid-cols-2 md:grid-cols-4">
          {trust.map((x) => (
            <div key={x.a} className="flex items-start gap-3">
              <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full border border-white/20 text-[var(--o-gold)]"><Icon name={x.icon} className="h-[18px] w-[18px]" /></span>
              <div><p className="text-sm font-bold">{x.a}</p><p className="mt-0.5 text-xs leading-relaxed text-white/55">{x.b}</p></div>
            </div>
          ))}
        </div>
      </div>
      <div className="o-container grid gap-10 py-12 md:grid-cols-[1.6fr_1fr_1fr]">
        <div>
          <p className="o-display text-3xl">{t("brand.name")} <span className="ms-1 rounded-full border border-white/40 px-2.5 py-1 align-middle font-sans text-[10px] font-extrabold uppercase tracking-[.14em]">Open</span></p>
          <p className="mt-4 max-w-md text-sm leading-relaxed text-white/60">{t("open.footer.about")}</p>
        </div>
        <nav className={col} aria-label={t("open.footer.shop")}>
          <p className="o-eyebrow !text-white/45 mb-1">{t("open.footer.shop")}</p>
          <Link href="/search" className={a}>{t("open.allProducts")}</Link>
          <Link href="/shops" className={a}>{t("open.allShops")}</Link>
          <Link href="/search?onSale=true&sort=discount" className={a}>{t("open.deals")}</Link>
        </nav>
        <nav className={col} aria-label={t("open.footer.help")}>
          <p className="o-eyebrow !text-white/45 mb-1">{t("open.footer.help")}</p>
          <Link href="/orders" className={a}>{t("open.trackOrder")}</Link>
          <Link href="/account" className={a}>{t("nav.account")}</Link>
          <a href={`https://${rootDomain}`} className={a}>{t("open.sellWithUs")}</a>
        </nav>
      </div>
      <p className="o-container border-t border-white/10 pt-5 text-xs text-white/40">© {new Date().getFullYear()} {t("brand.name")}</p>
    </footer>
  );
}
