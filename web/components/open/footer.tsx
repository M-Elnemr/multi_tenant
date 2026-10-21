import Link from "next/link";
import type { Translate } from "@/lib/i18n";

export function OpenFooter({ t, rootDomain }: { t: Translate; rootDomain: string }) {
  const col = "grid content-start gap-2.5 text-sm text-white/70";
  return (
    <footer className="mt-14 bg-[var(--o-navy)] pb-4 text-white">
      <div className="border-b border-white/10">
        <div className="o-container grid grid-cols-2 gap-4 py-6 md:grid-cols-4">
          {[["💵", t("open.trust.cod"), t("open.trust.codSub")], ["🏪", t("open.trust.shops"), t("open.trust.shopsSub")], ["📦", t("open.trust.track"), t("open.trust.trackSub")], ["⭐", t("open.trust.reviews"), t("open.trust.reviewsSub")]].map(([e, a, b]) => (
            <div key={a} className="flex items-start gap-3"><span className="grid h-11 w-11 shrink-0 place-items-center rounded-2xl bg-white/10 text-xl">{e}</span><div><p className="text-sm font-extrabold">{a}</p><p className="text-xs text-white/60">{b}</p></div></div>
          ))}
        </div>
      </div>
      <div className="o-container grid gap-8 py-10 md:grid-cols-4">
        <div className="md:col-span-2">
          <p className="text-2xl font-black">{t("brand.name")} <span className="rounded-md bg-[var(--o-gold)] px-1.5 py-0.5 align-middle text-xs font-black uppercase text-[#3a2a00]">Open</span></p>
          <p className="mt-3 max-w-md text-sm leading-relaxed text-white/65">{t("open.footer.about")}</p>
        </div>
        <nav className={col} aria-label={t("open.footer.shop")}>
          <p className="text-sm font-extrabold text-white">{t("open.footer.shop")}</p>
          <Link href="/search" className="hover:text-white">{t("open.allProducts")}</Link>
          <Link href="/shops" className="hover:text-white">{t("open.allShops")}</Link>
          <Link href="/search?onSale=true&sort=discount" className="hover:text-white">{t("open.deals")}</Link>
        </nav>
        <nav className={col} aria-label={t("open.footer.help")}>
          <p className="text-sm font-extrabold text-white">{t("open.footer.help")}</p>
          <Link href="/orders" className="hover:text-white">{t("open.trackOrder")}</Link>
          <Link href="/account" className="hover:text-white">{t("nav.account")}</Link>
          <a href={`https://${rootDomain}`} className="hover:text-white">{t("open.sellWithUs")}</a>
        </nav>
      </div>
      <p className="o-container text-xs text-white/40">© {new Date().getFullYear()} {t("brand.name")}</p>
    </footer>
  );
}
