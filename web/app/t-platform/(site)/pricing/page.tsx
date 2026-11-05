import type { Metadata } from "next";
import { getT } from "@/lib/i18n-server";
import { clinicCopy } from "@/components/landing/clinic/copy";
import { ClinicHostingPricing, LawyerHostingPricing } from "@/components/landing/hosting-pricing";
import { Icon } from "@/components/icons";
import { lawyerCopy } from "@/components/landing/lawyer/copy";
import { ShopPricing } from "@/components/landing/shop/pricing";
import { shopCopy } from "@/components/landing/shop/copy";
import "../../(landing)/landing.css";

export async function generateMetadata(): Promise<Metadata> {
  const { t } = await getT();
  return { title: t("pricing.title"), description: t("pricing.subtitle"), alternates: { canonical: "/pricing" } };
}

/** All prices in one page: shops (free + points), clinics and lawyers (first month free, then 250 EGP a month), each in its own look. */
export default async function Pricing() {
  const { t, locale } = await getT();
  const sections = [
    { id: "shops", icon: "bag" as const, title: t("type.store"), body: <ShopPricing p={shopCopy(locale).pricing} locale={locale} register="/register?type=STORE" /> },
    { id: "clinics", icon: "stethoscope" as const, title: t("type.clinic"), body: <ClinicHostingPricing p={clinicCopy(locale).pricing} register="/register?type=CLINIC" /> },
    { id: "lawyers", icon: "scale" as const, title: t("type.lawyer"), body: <LawyerHostingPricing p={lawyerCopy(locale).pricing} register="/register?type=LAWYER" /> },
  ];
  return (
    <div className="lp mx-auto max-w-5xl px-4 py-14">
      <h1 className="text-gradient animate-fade-up text-center text-3xl font-extrabold sm:text-4xl">{t("pricing.title")}</h1>
      <p className="mx-auto mt-3 max-w-2xl text-center text-slate-600">{t("pricing.subtitle")}</p>
      <nav className="mt-8 flex flex-wrap justify-center gap-2">
        {sections.map((s) => <a key={s.id} href={`#${s.id}`} className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-700 hover:border-slate-300"><Icon name={s.icon} className="h-4 w-4" />{s.title}</a>)}
      </nav>
      {sections.map((s) => (
        <section key={s.id} id={s.id} className="mt-16 scroll-mt-24">
          <h2 className="flex items-center gap-2 text-xl font-bold"><span className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-soft text-brand"><Icon name={s.icon} className="h-5 w-5" /></span>{s.title}</h2>
          {s.body}
        </section>
      ))}
    </div>
  );
}
