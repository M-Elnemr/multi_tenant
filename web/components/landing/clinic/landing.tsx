import Link from "next/link";
import type { Metadata } from "next";
import { Icon } from "@/components/icons";
import { templateFontVars } from "@/components/shop/templates/fonts";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { type Plan, PricingPlans } from "../pricing-plans";
import { Faq, Head, LandingFooter } from "../common";
import { Counter, Reveal, SplitWords } from "../kit";
import { LandingNav } from "../nav";
import { clinicCopy } from "./copy";
import { ClinicInsights, DayBoard, HeroDay, PatientFile, WebsiteShowcase } from "./visuals";

export async function clinicLandingMetadata(): Promise<Metadata> {
  const { locale } = await getT();
  const c = clinicCopy(locale);
  return {
    title: { absolute: c.meta.title },
    description: c.meta.description,
    alternates: { canonical: "/tabib" },
    openGraph: { title: c.meta.title, description: c.meta.description, url: "/tabib", type: "website", images: [{ url: "/brand/og.png", width: 1200, height: 630 }] },
    twitter: { card: "summary_large_image", title: c.meta.title, description: c.meta.description },
  };
}

/**
 * Doctors & clinics landing page (elmanassa.shop/tabib). Its own identity: warm paper, deep teal and sage, an editorial serif,
 * slow and quiet motion. Healthcare only; claims limited to real features (see copy.ts). Prices are the CLINIC billing plans.
 */
export async function ClinicLanding() {
  const { t, locale } = await getT();
  const c = clinicCopy(locale);
  const plans = await backendJson<Plan[]>("/billing/plans").catch(() => [] as Plan[]);
  const register = "/register?type=CLINIC";
  const fmt = (n: number) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const ld = [
    { "@context": "https://schema.org", "@type": "SoftwareApplication", name: `${t("brand.name")} · ${c.hero.eyebrow}`, applicationCategory: "BusinessApplication", applicationSubCategory: "Medical practice management", operatingSystem: "Web, Android, iOS", description: c.meta.description, offers: { "@type": "Offer", price: "0", priceCurrency: "EGP" } },
    { "@context": "https://schema.org", "@type": "FAQPage", mainEntity: c.faq.items.map((f) => ({ "@type": "Question", name: f.q, acceptedAnswer: { "@type": "Answer", text: f.a } })) },
  ];
  return (
    <div className={`lp lp-clinic ${templateFontVars}`}>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      <LandingNav tone="clinic" brand={t("brand.name")} locale={locale} login={c.nav.login} cta={c.nav.cta} ctaHref={register}
        links={[{ href: "#features", label: c.nav.features }, { href: "#website", label: c.nav.website }, { href: "#how", label: c.nav.how }, { href: "#pricing", label: c.nav.pricing }, { href: "#faq", label: c.nav.faq }]}
        other={{ href: "/", label: c.nav.shops }} />

      {/* ---------------- hero ---------------- */}
      <section className="relative isolate overflow-hidden pt-16">
        <div className="lp-clinic-wash" aria-hidden />
        <div className="mx-auto grid max-w-7xl items-center gap-16 px-4 pb-24 pt-12 sm:px-6 lg:min-h-[calc(100svh-4rem)] lg:grid-cols-[1fr_1fr] lg:pb-28">
          <div className="text-center lg:text-start">
            <Reveal y={10}><p className="lp-eyebrow lp-clinic-eyebrow mx-auto w-fit lg:mx-0"><Icon name="stethoscope" className="h-4 w-4" />{c.hero.eyebrow}</p></Reveal>
            <h1 className="lp-h1 lp-serif mt-6">
              <SplitWords text={c.hero.title1} className="block" />
              <SplitWords text={c.hero.title2} className="block italic text-[#0f5e57]" delay={0.15} />
            </h1>
            <Reveal delay={0.4}><p className="lp-lead mx-auto mt-7 lg:mx-0">{c.hero.sub}</p></Reveal>
            <Reveal delay={0.55}>
              <div className="mt-9 flex flex-wrap justify-center gap-3 lg:justify-start">
                <Link href={register} className="lp-cta lp-cta-lg">{c.hero.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link>
                <a href="#how" className="lp-cta-ghost lp-cta-lg">{c.hero.cta2}</a>
              </div>
              <ul className="mt-7 flex flex-wrap justify-center gap-x-5 gap-y-2 text-sm text-[#10302d]/60 lg:justify-start">
                {c.hero.trust.map((x) => <li key={x} className="flex items-center gap-1.5"><Icon name="check" className="h-4 w-4 text-[#3f7d5a]" />{x}</li>)}
              </ul>
            </Reveal>
          </div>
          <HeroDay h={c.hero} slots={c.booking.slots} locale={locale} />
        </div>
        <div className="mx-auto max-w-5xl px-4 pb-20 sm:px-6">
          <div className="grid gap-px overflow-hidden rounded-3xl border border-[#10302d]/10 bg-[#10302d]/10 sm:grid-cols-3">
            {c.stats.map((s, i) => (
              <Reveal key={s.label} delay={i * 0.08} className="bg-[#fbf9f4] p-7 text-center">
                <p dir="ltr" className="lp-serif text-5xl font-semibold text-[#0f3d3a] tnum"><Counter value={s.value} locale={locale} suffix={"suffix" in s ? s.suffix : ""} /></p>
                <p className="mt-2 text-sm text-[#10302d]/60">{s.label}</p>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- clinic management ---------------- */}
      <section id="features" className="lp-section bg-[#fbf9f4]">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.features.eyebrow} title={c.features.title} sub={c.features.sub} center /></Reveal>
          <div className="mt-16 grid gap-px overflow-hidden rounded-[2rem] border border-[#10302d]/[.08] bg-[#10302d]/[.08] sm:grid-cols-2 lg:grid-cols-3">
            {c.features.items.map((f, i) => (
              <Reveal key={f.title} delay={(i % 3) * 0.08} className="lp-clinic-cell">
                <span className="grid h-12 w-12 place-items-center rounded-2xl bg-[#e7f0ea] text-[#0f5e57]"><Icon name={f.icon} className="h-5 w-5" /></span>
                <p className="lp-serif mt-6 text-2xl font-semibold text-[#10302d]">{f.title}</p>
                <p className="mt-2 leading-relaxed text-[#10302d]/65">{f.body}</p>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- appointments ---------------- */}
      <section className="lp-section">
        <div className="mx-auto grid max-w-7xl items-center gap-16 px-4 sm:px-6 lg:grid-cols-[0.9fr_1.1fr]">
          <Reveal><Head eyebrow={c.booking.eyebrow} title={c.booking.title} sub={c.booking.sub} /></Reveal>
          <Reveal delay={0.1}><DayBoard b={c.booking} /></Reveal>
        </div>
      </section>

      {/* ---------------- patients (deep teal chapter) ---------------- */}
      <section className="lp-section lp-clinic-deep relative overflow-hidden">
        <div className="mx-auto grid max-w-7xl items-center gap-16 px-4 sm:px-6 lg:grid-cols-[1.05fr_0.95fr]">
          <Reveal className="lg:order-2"><Head eyebrow={c.patients.eyebrow} title={c.patients.title} sub={c.patients.sub} /></Reveal>
          <Reveal delay={0.1} className="lg:order-1"><div className="mx-auto max-w-md"><PatientFile p={c.patients.file} /></div></Reveal>
        </div>
      </section>

      {/* ---------------- website ---------------- */}
      <section id="website" className="lp-section bg-[#fbf9f4]">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.website.eyebrow} title={c.website.title} sub={c.website.sub} center /></Reveal>
          <div className="mt-14 grid items-center gap-12 lg:grid-cols-[1.4fr_0.6fr]">
            <Reveal><WebsiteShowcase w={c.website} /></Reveal>
            <div>
              <ul className="space-y-4">
                {c.website.points.map((p, i) => (
                  <Reveal as="li" key={p} delay={i * 0.08} className="flex items-center gap-3 text-[#10302d]">
                    <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-[#0f3d3a] text-[#f3efe6]"><Icon name="check" className="h-4 w-4" /></span><span className="font-semibold">{p}</span>
                  </Reveal>
                ))}
              </ul>
              <Reveal delay={0.3}><p className="mt-8 rounded-2xl border border-[#10302d]/10 bg-white p-5 text-sm leading-relaxed text-[#10302d]/70"><Icon name="mobile" className="mb-2 h-5 w-5 text-[#0f5e57]" />{c.website.directory}</p></Reveal>
            </div>
          </div>
        </div>
      </section>

      {/* ---------------- services + insights ---------------- */}
      <section className="lp-section">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.services.eyebrow} title={c.services.title} /></Reveal>
          <div className="mt-12 grid gap-4 md:grid-cols-3">
            {c.services.items.map((s, i) => (
              <Reveal key={s.title} delay={i * 0.08}>
                <div className="lp-paper-card h-full p-7">
                  <Icon name={s.icon} className="h-6 w-6 text-[#0f5e57]" />
                  <p className="lp-serif mt-5 text-2xl font-semibold text-[#10302d]">{s.title}</p>
                  <p className="mt-2 leading-relaxed text-[#10302d]/65">{s.body}</p>
                </div>
              </Reveal>
            ))}
          </div>
          <div className="mt-28 grid items-center gap-14 lg:grid-cols-[0.9fr_1.1fr]">
            <Reveal><Head eyebrow={c.insights.eyebrow} title={c.insights.title} sub={c.insights.sub} /></Reveal>
            <Reveal delay={0.1}><ClinicInsights s={c.insights} locale={locale} /></Reveal>
          </div>
        </div>
      </section>

      {/* ---------------- how it works ---------------- */}
      <section id="how" className="lp-section lp-clinic-deep">
        <div className="mx-auto max-w-6xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.how.eyebrow} title={c.how.title} center /></Reveal>
          <ol className="mt-16 grid gap-10 md:grid-cols-4 md:gap-6">
            {c.how.steps.map((s, i) => (
              <Reveal as="li" key={s.title} delay={i * 0.12} className="relative border-t border-[#f3efe6]/20 pt-6">
                <span className="lp-serif text-5xl font-semibold italic text-[#cfe3d6]">{fmt(i + 1)}</span>
                <p className="mt-4 text-lg font-bold text-[#f3efe6]">{s.title}</p>
                <p className="mt-2 leading-relaxed text-[#f3efe6]/65">{s.body}</p>
              </Reveal>
            ))}
          </ol>
        </div>
      </section>

      {/* ---------------- pricing + faq ---------------- */}
      <section id="pricing" className="lp-section bg-[#fbf9f4]">
        <div className="mx-auto max-w-5xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.pricing.eyebrow} title={c.pricing.title} sub={c.pricing.sub} center /></Reveal>
          {plans.length > 0 ? <PricingPlans plans={plans} types={["CLINIC"]} /> : <p className="mt-10 text-center"><Link href="/pricing" className="font-semibold underline">{c.final.cta2}</Link></p>}
        </div>
      </section>
      <section id="faq" className="bg-[#fbf9f4] pb-28">
        <div className="mx-auto max-w-3xl px-4 sm:px-6">
          <Reveal><h2 className="lp-h2 lp-serif mb-8 text-center">{c.faq.title}</h2></Reveal>
          <Faq items={c.faq.items} className="divide-[#10302d]/10 border-y border-[#10302d]/10" />
        </div>
      </section>

      {/* ---------------- final ---------------- */}
      <section className="relative isolate overflow-hidden py-28 text-center">
        <div className="lp-clinic-wash" aria-hidden />
        <div className="relative mx-auto max-w-3xl px-4">
          <Reveal><h2 className="lp-h1 lp-serif text-[clamp(2.2rem,5vw,4rem)]">{c.final.title}</h2></Reveal>
          <Reveal delay={0.1}><p className="lp-lead mx-auto mt-5">{c.final.sub}</p></Reveal>
          <Reveal delay={0.2}>
            <div className="mt-10 flex flex-wrap justify-center gap-3">
              <Link href={register} className="lp-cta lp-cta-lg">{c.final.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link>
              <a href="#pricing" className="lp-cta-ghost lp-cta-lg">{c.final.cta2}</a>
            </div>
          </Reveal>
        </div>
      </section>

      <div className="lp-clinic-deep">
        <LandingFooter brand={t("brand.name")} tag={c.footer.tag} copyright={t("brand.name")}
          cols={[
            { title: c.footer.product, links: [{ href: "#features", label: c.nav.features }, { href: "#website", label: c.nav.website }, { href: "#pricing", label: c.nav.pricing }, { href: register, label: c.nav.cta }] },
            { title: c.footer.company, links: [{ href: "/", label: c.footer.shops }, { href: "/login", label: c.nav.login }, { href: "/privacy", label: c.footer.privacy }, { href: "/delete-account", label: c.footer.deleteAccount }] },
          ]} />
      </div>
      <div className="lp-sticky-cta lp-sticky-clinic sm:hidden"><Link href={register} className="lp-cta w-full justify-center py-3.5">{c.hero.cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
    </div>
  );
}
