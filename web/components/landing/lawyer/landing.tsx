import Link from "next/link";
import type { Metadata } from "next";
import { Icon } from "@/components/icons";
import { templateFontVars } from "@/components/shop/templates/fonts";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { type Plan, PricingPlans } from "../pricing-plans";
import { Faq, Head, LandingFooter } from "../common";
import { Reveal, SplitWords } from "../kit";
import { LandingNav } from "../nav";
import { lawyerCopy } from "./copy";
import { Agenda, CaseTimeline, HeroCase, OfficeSite } from "./visuals";

export async function lawyerLandingMetadata(): Promise<Metadata> {
  const { locale } = await getT();
  const c = lawyerCopy(locale);
  return {
    title: { absolute: c.meta.title },
    description: c.meta.description,
    alternates: { canonical: "/mohami" },
    openGraph: { title: c.meta.title, description: c.meta.description, url: "/mohami", type: "website", images: [{ url: "/brand/og.png", width: 1200, height: 630 }] },
    twitter: { card: "summary_large_image", title: c.meta.title, description: c.meta.description },
  };
}

/**
 * Lawyers & law firms landing page (elmanassa.shop/mohami). Its own identity: deep navy, brass and ivory, a classical serif (Amiri / Cormorant),
 * slow and stately motion. No AI (user decision for lawyers). Claims limited to real features (see copy.ts); prices are the LAWYER billing plans.
 */
export async function LawyerLanding() {
  const { t, locale } = await getT();
  const c = lawyerCopy(locale);
  const plans = await backendJson<Plan[]>("/billing/plans").catch(() => [] as Plan[]);
  const register = "/register?type=LAWYER";
  const fmt = (n: number) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const ld = [
    { "@context": "https://schema.org", "@type": "SoftwareApplication", name: `${t("brand.name")} · ${c.hero.eyebrow}`, applicationCategory: "BusinessApplication", applicationSubCategory: "Legal practice management", operatingSystem: "Web, Android, iOS", description: c.meta.description, offers: { "@type": "Offer", price: "0", priceCurrency: "EGP" } },
    { "@context": "https://schema.org", "@type": "FAQPage", mainEntity: c.faq.items.map((f) => ({ "@type": "Question", name: f.q, acceptedAnswer: { "@type": "Answer", text: f.a } })) },
  ];
  return (
    <div className={`lp lp-law ${templateFontVars}`}>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      <LandingNav tone="lawyer" brand={t("brand.name")} locale={locale} login={c.nav.login} cta={c.nav.cta} ctaHref={register}
        links={[{ href: "#features", label: c.nav.features }, { href: "#cases", label: c.nav.cases }, { href: "#website", label: c.nav.website }, { href: "#pricing", label: c.nav.pricing }, { href: "#faq", label: c.nav.faq }]}
        other={{ href: "/", label: c.nav.others }} />

      {/* ---------------- hero (navy) ---------------- */}
      <section className="lp-law-navy relative isolate overflow-hidden pt-16">
        <div className="lp-law-lines" aria-hidden />
        <div className="mx-auto grid max-w-7xl items-center gap-16 px-4 pb-28 pt-12 sm:px-6 lg:min-h-[calc(100svh-4rem)] lg:grid-cols-[1.05fr_1fr]">
          <div className="text-center lg:text-start">
            <Reveal y={10}><p className="lp-eyebrow mx-auto w-fit border-y border-[#b08d57]/40 py-1.5 text-[#d9b97f] lg:mx-0"><Icon name="scale" className="h-4 w-4" />{c.hero.eyebrow}</p></Reveal>
            <h1 className="lp-h1 lp-classic mt-8">
              <SplitWords text={c.hero.title1} className="block" />
              <SplitWords text={c.hero.title2} className="block" delay={0.18} />
              <SplitWords text={c.hero.title3} className="block text-[#d9b97f]" delay={0.36} />
            </h1>
            <Reveal delay={0.5}><p className="lp-lead mx-auto mt-7 lg:mx-0">{c.hero.sub}</p></Reveal>
            <Reveal delay={0.65}>
              <div className="mt-9 flex flex-wrap justify-center gap-3 lg:justify-start">
                <Link href={register} className="lp-cta lp-cta-lg">{c.hero.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link>
                <a href="#features" className="lp-cta-ghost lp-cta-lg">{c.hero.cta2}</a>
              </div>
              <ul className="mt-7 flex flex-wrap justify-center gap-x-5 gap-y-2 text-sm text-[#f5efe0]/60 lg:justify-start">
                {c.hero.trust.map((x) => <li key={x} className="flex items-center gap-1.5"><Icon name="check" className="h-4 w-4 text-[#d9b97f]" />{x}</li>)}
              </ul>
            </Reveal>
          </div>
          <HeroCase h={c.hero} />
        </div>
      </section>

      {/* ---------------- features (ivory) ---------------- */}
      <section id="features" className="lp-section">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.features.eyebrow} title={c.features.title} center /></Reveal>
          <div className="mt-16 grid border-t border-[#14213d]/15 sm:grid-cols-2 lg:grid-cols-3">
            {c.features.items.map((f, i) => (
              <Reveal key={f.title} delay={(i % 3) * 0.1} className="lp-law-cell">
                <span className="lp-classic text-sm font-semibold text-[#b08d57] tnum">{fmt(i + 1).padStart(2, locale === "ar" ? "٠" : "0")}</span>
                <Icon name={f.icon} className="mt-5 h-6 w-6 text-[#14213d]" />
                <p className="lp-classic mt-4 text-2xl font-semibold text-[#14213d]">{f.title}</p>
                <p className="mt-2 leading-relaxed text-[#14213d]/65">{f.body}</p>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- cases ---------------- */}
      <section id="cases" className="lp-section bg-[#efe8d8]">
        <div className="mx-auto grid max-w-7xl items-center gap-16 px-4 sm:px-6 lg:grid-cols-[0.85fr_1.15fr]">
          <Reveal><Head eyebrow={c.cases.eyebrow} title={c.cases.title} sub={c.cases.sub} /></Reveal>
          <Reveal delay={0.1}><CaseTimeline c={c.cases} locale={locale} /></Reveal>
        </div>
      </section>

      {/* ---------------- agenda (navy) ---------------- */}
      <section className="lp-section lp-law-navy">
        <div className="mx-auto grid max-w-7xl items-center gap-16 px-4 sm:px-6 lg:grid-cols-[1.1fr_0.9fr]">
          <Reveal className="lg:order-2"><Head eyebrow={c.agenda.eyebrow} title={c.agenda.title} sub={c.agenda.sub} /></Reveal>
          <Reveal delay={0.1} className="lg:order-1"><div className="mx-auto max-w-lg"><Agenda a={c.agenda} /></div></Reveal>
        </div>
      </section>

      {/* ---------------- website ---------------- */}
      <section id="website" className="lp-section">
        <div className="mx-auto grid max-w-7xl items-center gap-16 px-4 sm:px-6 lg:grid-cols-[0.8fr_1.2fr]">
          <Reveal><Head eyebrow={c.website.eyebrow} title={c.website.title} sub={c.website.sub} /></Reveal>
          <Reveal delay={0.1} className="pt-12"><OfficeSite w={c.website} /></Reveal>
        </div>
      </section>

      {/* ---------------- clients ---------------- */}
      <section className="lp-section bg-[#efe8d8]">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.clients.eyebrow} title={c.clients.title} center /></Reveal>
          <div className="mt-14 grid gap-4 md:grid-cols-3">
            {c.clients.items.map((x, i) => (
              <Reveal key={x.title} delay={i * 0.1}>
                <div className="lp-ivory-card h-full p-7">
                  <span className="grid h-12 w-12 place-items-center rounded-full border border-[#b08d57]/50 text-[#8a6a3a]"><Icon name={x.icon} className="h-5 w-5" /></span>
                  <p className="lp-classic mt-5 text-2xl font-semibold text-[#14213d]">{x.title}</p>
                  <p className="mt-2 leading-relaxed text-[#14213d]/65">{x.body}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- how ---------------- */}
      <section className="lp-section lp-law-navy">
        <div className="mx-auto max-w-6xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.how.eyebrow} title={c.how.title} center /></Reveal>
          <ol className="mt-16 grid gap-10 md:grid-cols-4 md:gap-6">
            {c.how.steps.map((s, i) => (
              <Reveal as="li" key={s.title} delay={i * 0.12} className="border-t border-[#d9b97f]/30 pt-6">
                <span className="lp-classic text-5xl font-semibold text-[#d9b97f]">{fmt(i + 1)}</span>
                <p className="mt-4 text-lg font-bold text-[#f5efe0]">{s.title}</p>
                <p className="mt-2 leading-relaxed text-[#f5efe0]/65">{s.body}</p>
              </Reveal>
            ))}
          </ol>
        </div>
      </section>

      {/* ---------------- pricing + faq ---------------- */}
      <section id="pricing" className="lp-section">
        <div className="mx-auto max-w-5xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.pricing.eyebrow} title={c.pricing.title} sub={c.pricing.sub} center /></Reveal>
          {plans.length > 0 ? <PricingPlans plans={plans} types={["LAWYER"]} /> : <p className="mt-10 text-center"><Link href="/pricing" className="font-semibold underline">{c.final.cta2}</Link></p>}
        </div>
      </section>
      <section id="faq" className="pb-28">
        <div className="mx-auto max-w-3xl px-4 sm:px-6">
          <Reveal><h2 className="lp-h2 lp-classic mb-8 text-center">{c.faq.title}</h2></Reveal>
          <Faq items={c.faq.items} className="divide-[#14213d]/12 border-y border-[#14213d]/12" />
        </div>
      </section>

      {/* ---------------- final ---------------- */}
      <section className="lp-law-navy relative isolate overflow-hidden py-28 text-center">
        <div className="lp-law-lines" aria-hidden />
        <div className="relative mx-auto max-w-3xl px-4">
          <Reveal><Icon name="scale" className="mx-auto h-10 w-10 text-[#d9b97f]" /></Reveal>
          <Reveal delay={0.05}><h2 className="lp-h1 lp-classic mt-6 text-[clamp(2.2rem,5vw,4rem)]">{c.final.title}</h2></Reveal>
          <Reveal delay={0.1}><p className="lp-lead mx-auto mt-5">{c.final.sub}</p></Reveal>
          <Reveal delay={0.2}>
            <div className="mt-10 flex flex-wrap justify-center gap-3">
              <Link href={register} className="lp-cta lp-cta-lg">{c.final.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link>
              <a href="#pricing" className="lp-cta-ghost lp-cta-lg">{c.final.cta2}</a>
            </div>
          </Reveal>
        </div>
      </section>

      <div className="lp-law-navy border-t border-[#d9b97f]/15">
        <LandingFooter brand={t("brand.name")} tag={c.footer.tag} copyright={t("brand.name")}
          cols={[
            { title: c.footer.product, links: [{ href: "#features", label: c.nav.features }, { href: "#cases", label: c.nav.cases }, { href: "#pricing", label: c.nav.pricing }, { href: register, label: c.nav.cta }] },
            { title: c.footer.company, links: [{ href: "/", label: c.footer.shops }, { href: "/tabib", label: c.footer.clinics }, { href: "/login", label: c.nav.login }, { href: "/privacy", label: c.footer.privacy }] },
          ]} />
      </div>
      <div className="lp-sticky-cta lp-sticky-law sm:hidden"><Link href={register} className="lp-cta w-full justify-center py-3.5">{c.hero.cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
    </div>
  );
}
