import Link from "next/link";
import type { Metadata } from "next";
import { Icon } from "@/components/icons";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { type Plan, PricingPlans } from "../pricing-plans";
import { Faq, Head, LandingFooter } from "../common";
import { Reveal, SplitWords } from "../kit";
import { LandingNav } from "../nav";
import { ChatDemo } from "./chat-demo";
import { shopCopy } from "./copy";
import { CatalogBuilder, ChannelsHub, DashPreview, HeroStage, InsightsPanel } from "./visuals";

/** SEO for both addresses of this page: "/" and "/shop" render the same thing, so both point search engines at "/". */
export async function shopLandingMetadata(): Promise<Metadata> {
  const { locale } = await getT();
  const c = shopCopy(locale);
  return {
    title: { absolute: c.meta.title },
    description: c.meta.description,
    alternates: { canonical: "/" },
    openGraph: { title: c.meta.title, description: c.meta.description, url: "/", type: "website", images: [{ url: "/brand/og.png", width: 1200, height: 630 }] },
    twitter: { card: "summary_large_image", title: c.meta.title, description: c.meta.description },
  };
}

/**
 * The shop-owner landing page (elmanassa.shop and elmanassa.shop/shop). AI-first: a dark, immersive story of the AI seller,
 * alternating with light "business" chapters. Only real capabilities (see copy.ts); prices come from the billing plans.
 */
export async function ShopLanding() {
  const { t, locale } = await getT();
  const c = shopCopy(locale);
  const plans = await backendJson<Plan[]>("/billing/plans").catch(() => [] as Plan[]);
  const shopName = locale === "ar" ? "بوتيك نور" : "Nour Boutique";
  const register = "/register?type=STORE";
  const jsonLd = {
    "@context": "https://schema.org", "@type": "SoftwareApplication", name: t("brand.name"), applicationCategory: "BusinessApplication", operatingSystem: "Web, Android, iOS",
    description: c.meta.description, offers: { "@type": "Offer", price: "0", priceCurrency: "EGP", description: c.hero.trust[0] },
  };
  const faqLd = { "@context": "https://schema.org", "@type": "FAQPage", mainEntity: c.faq.items.map((f) => ({ "@type": "Question", name: f.q, acceptedAnswer: { "@type": "Answer", text: f.a } })) };

  return (
    <div className="lp lp-shop">
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify([jsonLd, faqLd]).replace(/</g, "\\u003c") }} />
      <LandingNav tone="shop" brand={t("brand.name")} locale={locale} login={c.nav.login} cta={c.nav.cta} ctaHref={register}
        links={[{ href: "#demo", label: c.nav.demo }, { href: "#features", label: c.nav.features }, { href: "#how", label: c.nav.how }, { href: "#pricing", label: c.nav.pricing }, { href: "#faq", label: c.nav.faq }]}
        other={{ href: "/tabib", label: c.nav.clinics }} />

      {/* ---------------- hero ---------------- */}
      <section className="lp-dark relative isolate overflow-hidden pt-16">
        <div className="lp-aurora" aria-hidden />
        <div className="lp-grid-bg" aria-hidden />
        <div className="mx-auto grid max-w-7xl items-center gap-6 px-4 pb-16 pt-10 sm:px-6 lg:min-h-[calc(100svh-4rem)] lg:grid-cols-[1.05fr_1fr] lg:gap-10 lg:pb-24">
          <div className="text-center lg:text-start">
            <Reveal y={12}><p className="lp-chip mx-auto lg:mx-0"><span className="lp-orb h-2 w-2 rounded-full" />{c.hero.eyebrow}</p></Reveal>
            <h1 className="lp-h1 mt-6">
              <SplitWords text={c.hero.title1} className="block" />
              <SplitWords text={c.hero.title2} className="lp-gradient-text block" delay={0.15} />
              <SplitWords text={c.hero.title3} className="block text-white/55" delay={0.3} />
            </h1>
            <Reveal delay={0.5}><p className="lp-lead mx-auto mt-6 lg:mx-0">{c.hero.sub}</p></Reveal>
            <Reveal delay={0.65}>
              <div className="mt-9 flex flex-wrap justify-center gap-3 lg:justify-start">
                <Link href={register} className="lp-cta lp-cta-lg">{c.hero.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link>
                <a href="#demo" className="lp-cta-ghost lp-cta-lg"><Icon name="play" className="h-4 w-4" />{c.hero.cta2}</a>
              </div>
              <ul className="mt-7 flex flex-wrap justify-center gap-x-5 gap-y-2 text-sm text-white/55 lg:justify-start">
                {c.hero.trust.map((x) => <li key={x} className="flex items-center gap-1.5"><Icon name="check" className="h-4 w-4 text-[#a3e635]" />{x}</li>)}
              </ul>
            </Reveal>
          </div>
          <HeroStage h={c.hero} d={c.demo} />
        </div>
        <div className="lp-marquee border-y border-white/[.06] py-4 text-white/40" aria-hidden>
          <div className="lp-marquee-track" dir="ltr">
            {[0, 1].map((k) => (
              <span key={k} className="flex shrink-0 items-center gap-10 pe-10">
                {[...c.channels.list.map((x) => x.name), ...c.sell.items.map((x) => x.title)].map((w, i) => <span key={i} className="flex items-center gap-10 whitespace-nowrap font-display text-lg font-semibold">{w}<Icon name="sparkles" className="h-4 w-4 text-white/20" /></span>)}
              </span>
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- live demo ---------------- */}
      <section id="demo" className="lp-dark lp-section relative overflow-hidden">
        <div className="lp-glow lp-glow-violet" aria-hidden />
        <div className="relative mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.demo.eyebrow} title={c.demo.title} sub={c.demo.sub} center /></Reveal>
          <div className="mt-14"><ChatDemo d={c.demo} shopName={shopName} /></div>
        </div>
      </section>

      {/* ---------------- AI selling capabilities ---------------- */}
      <section id="features" className="lp-dark lp-section relative">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.sell.eyebrow} title={c.sell.title} sub={c.sell.sub} /></Reveal>
          <div className="mt-14 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {c.sell.items.map((it, i) => (
              <Reveal key={it.title} delay={(i % 3) * 0.08} className={i === 0 || i === 5 ? "lg:col-span-1" : ""}>
                <div className="lp-feature h-full">
                  <span className="lp-feature-icon"><Icon name={it.icon} className="h-5 w-5" /></span>
                  <p className="mt-6 font-display text-xl font-bold text-white">{it.title}</p>
                  <p className="mt-2 leading-relaxed text-white/60">{it.body}</p>
                </div>
              </Reveal>
            ))}
          </div>
          <Reveal><p className="mt-8 flex items-center justify-center gap-2 text-center text-white/60"><Icon name="chat" className="h-4 w-4 text-[#22d3ee]" />{c.sell.asks}</p></Reveal>
        </div>
      </section>

      {/* ---------------- business management (light chapter) ---------------- */}
      <section className="lp-light lp-section lp-curve-top">
        <div className="mx-auto grid max-w-7xl items-center gap-14 px-4 sm:px-6 lg:grid-cols-[0.9fr_1.1fr]">
          <div>
            <Reveal><Head eyebrow={c.manage.eyebrow} title={c.manage.title} sub={c.manage.sub} /></Reveal>
            <ul className="mt-10 space-y-6">
              {c.manage.points.map((p, i) => (
                <Reveal key={p.title} as="li" delay={i * 0.08} className="flex gap-4">
                  <span className="mt-1 grid h-8 w-8 shrink-0 place-items-center rounded-full bg-[#0d1117] text-xs font-bold text-white tnum">{(i + 1).toLocaleString(locale === "ar" ? "ar-EG" : "en-US")}</span>
                  <div><p className="font-display text-lg font-bold">{p.title}</p><p className="mt-1 leading-relaxed text-[#4b5563]">{p.body}</p></div>
                </Reveal>
              ))}
            </ul>
          </div>
          <Reveal delay={0.1}><DashPreview m={c.manage.dash} locale={locale} /></Reveal>
        </div>
      </section>

      {/* ---------------- product understanding ---------------- */}
      <section className="lp-dark lp-section relative overflow-hidden">
        <div className="lp-glow lp-glow-cyan" aria-hidden />
        <div className="relative mx-auto grid max-w-7xl items-center gap-14 px-4 sm:px-6 lg:grid-cols-2">
          <Reveal className="lg:order-2"><Head eyebrow={c.catalog.eyebrow} title={c.catalog.title} sub={c.catalog.sub} /></Reveal>
          <Reveal delay={0.1} className="lg:order-1"><CatalogBuilder c={c.catalog} /></Reveal>
        </div>
      </section>

      {/* ---------------- channels ---------------- */}
      <section className="lp-dark lp-section relative">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.channels.eyebrow} title={c.channels.title} sub={c.channels.sub} center /></Reveal>
          <div className="mx-auto mt-14 max-w-4xl"><ChannelsHub list={c.channels.list} /></div>
          <p className="mx-auto mt-6 max-w-2xl text-center text-sm text-white/45"><Icon name="lock" className="me-1.5 inline h-4 w-4 align-[-3px]" />{c.channels.note}</p>
        </div>
      </section>

      {/* ---------------- analytics + marketing (light) ---------------- */}
      <section className="lp-light lp-section lp-curve-top">
        <div className="mx-auto grid max-w-7xl items-center gap-14 px-4 sm:px-6 lg:grid-cols-[1.1fr_0.9fr]">
          <Reveal><InsightsPanel s={c.insights} locale={locale} /></Reveal>
          <Reveal delay={0.1}><Head eyebrow={c.insights.eyebrow} title={c.insights.title} sub={c.insights.sub} /></Reveal>
        </div>
        <div className="mx-auto mt-28 max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.marketing.eyebrow} title={c.marketing.title} center /></Reveal>
          <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {c.marketing.items.map((m, i) => (
              <Reveal key={m.title} delay={i * 0.07}>
                <div className="lp-card-light h-full">
                  <span className="grid h-11 w-11 place-items-center rounded-xl bg-[#7c5cff]/10 text-[#7c5cff]"><Icon name={m.icon} className="h-5 w-5" /></span>
                  <p className="mt-5 font-display text-lg font-bold">{m.title}</p>
                  <p className="mt-1.5 text-sm leading-relaxed text-[#4b5563]">{m.body}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      {/* ---------------- how it works ---------------- */}
      <section id="how" className="lp-dark lp-section relative overflow-hidden">
        <div className="lp-glow lp-glow-lime" aria-hidden />
        <div className="relative mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.how.eyebrow} title={c.how.title} center /></Reveal>
          <ol className="lp-steps relative mt-16 grid gap-8 md:grid-cols-4 md:gap-5">
            {c.how.steps.map((s, i) => (
              <Reveal key={s.title} as="li" delay={i * 0.12} className="relative">
                <span className="lp-step-num">{(i + 1).toLocaleString(locale === "ar" ? "ar-EG" : "en-US")}</span>
                <p className="mt-6 font-display text-xl font-bold text-white">{s.title}</p>
                <p className="mt-2 leading-relaxed text-white/60">{s.body}</p>
              </Reveal>
            ))}
          </ol>
          <Reveal><div className="mt-14 flex justify-center"><Link href={register} className="lp-cta lp-cta-lg">{c.hero.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link></div></Reveal>
        </div>
      </section>

      {/* ---------------- pricing + faq (light) ---------------- */}
      <section id="pricing" className="lp-light lp-section lp-curve-top">
        <div className="mx-auto max-w-5xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.pricing.eyebrow} title={c.pricing.title} sub={c.pricing.sub} center /></Reveal>
          {plans.length > 0 ? <PricingPlans plans={plans} types={["STORE"]} /> : <p className="mt-10 text-center"><Link href="/pricing" className="font-semibold underline">{c.final.cta2}</Link></p>}
        </div>
      </section>
      <section id="faq" className="lp-light pb-28">
        <div className="mx-auto max-w-3xl px-4 sm:px-6">
          <Reveal><h2 className="lp-h2 mb-8 text-center">{c.faq.title}</h2></Reveal>
          <Faq items={c.faq.items} className="divide-black/10 border-y border-black/10" />
        </div>
      </section>

      {/* ---------------- final CTA ---------------- */}
      <section className="lp-dark relative isolate overflow-hidden py-28 text-center">
        <div className="lp-aurora lp-aurora-low" aria-hidden />
        <div className="relative mx-auto max-w-3xl px-4">
          <Reveal><h2 className="lp-h1 text-[clamp(2.2rem,5vw,4rem)]">{c.final.title}</h2></Reveal>
          <Reveal delay={0.1}><p className="lp-lead mx-auto mt-5">{c.final.sub}</p></Reveal>
          <Reveal delay={0.2}>
            <div className="mt-10 flex flex-wrap justify-center gap-3">
              <Link href={register} className="lp-cta lp-cta-lg">{c.final.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link>
              <a href="#pricing" className="lp-cta-ghost lp-cta-lg">{c.final.cta2}</a>
            </div>
          </Reveal>
        </div>
      </section>

      <div className="lp-dark">
        <LandingFooter brand={t("brand.name")} tag={c.footer.tag} copyright={t("brand.name")}
          cols={[
            { title: c.footer.product, links: [{ href: "#demo", label: c.nav.demo }, { href: "#features", label: c.nav.features }, { href: "/pricing", label: c.nav.pricing }, { href: register, label: c.nav.cta }] },
            { title: c.footer.company, links: [{ href: "/tabib", label: c.footer.clinics }, { href: "/login", label: c.nav.login }, { href: "/privacy", label: c.footer.privacy }, { href: "/delete-account", label: c.footer.deleteAccount }] },
          ]} />
      </div>
      {/* phones: one thumb-reach call to action */}
      <div className="lp-sticky-cta sm:hidden"><Link href={register} className="lp-cta w-full justify-center py-3.5">{c.hero.cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
    </div>
  );
}
