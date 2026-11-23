import Link from "next/link";
import type { Metadata } from "next";
import { Icon } from "@/components/icons";
import { getT } from "@/lib/i18n-server";
import { Faq, Head, LandingFooter } from "../common";
import { Reveal, SplitWords } from "../kit";
import { LandingNav } from "../nav";
import { serviceCopy } from "./copy";
import { ServicePricing } from "./pricing";
import { BookPhone, ChatDemo, DiaryMock, FeatureVisual, HeroDay } from "./visuals";

export async function serviceLandingMetadata(): Promise<Metadata> {
  const { locale } = await getT();
  const c = serviceCopy(locale);
  return {
    title: { absolute: c.meta.title },
    description: c.meta.description,
    alternates: { canonical: "/services" },
    openGraph: { title: c.meta.title, description: c.meta.description, url: "/services", type: "website", images: [{ url: "/brand/og.png", width: 1200, height: 630 }] },
    twitter: { card: "summary_large_image", title: c.meta.title, description: c.meta.description },
  };
}

/**
 * Service businesses landing page (elmanassa.shop/services): warm plum and pink on blush paper, built around the appointment (ticket cards, a day timeline).
 * Claims limited to real features (see copy.ts); prices come from lib/pricing.ts via ServicePricing; previews are illustrations, labelled as examples.
 */
export async function ServiceLanding() {
  const { t, locale } = await getT();
  const c = serviceCopy(locale);
  const register = "/register?type=SERVICE";
  const ld = [
    { "@context": "https://schema.org", "@type": "SoftwareApplication", name: `${t("brand.name")} · ${c.hero.eyebrow}`, applicationCategory: "BusinessApplication", description: c.meta.description, offers: { "@type": "Offer", price: "0", priceCurrency: "EGP" } },
    { "@context": "https://schema.org", "@type": "FAQPage", mainEntity: c.faq.items.map((f) => ({ "@type": "Question", name: f.q, acceptedAnswer: { "@type": "Answer", text: f.a } })) },
  ];
  return (
    <div className="lp lp-svc">
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      <LandingNav tone="service" brand={t("brand.name")} locale={locale} login={c.nav.login} cta={c.nav.cta} ctaHref={register}
        links={[{ href: "#trades", label: c.nav.trades }, { href: "#features", label: c.nav.features }, { href: "#how", label: c.nav.how }, { href: "#chat", label: c.nav.chat }, { href: "#pricing", label: c.nav.pricing }, { href: "#faq", label: c.nav.faq }]}
        other={{ href: "/", label: c.nav.others }} />

      <section className="lp-svc-hero relative isolate overflow-hidden pt-16">
        <div className="mx-auto grid max-w-7xl items-center gap-14 px-4 pb-24 pt-12 sm:px-6 lg:min-h-[calc(100svh-4rem)] lg:grid-cols-[1.05fr_0.95fr]">
          <div className="text-center lg:text-start">
            <Reveal y={10}><p className="lp-eyebrow mx-auto w-fit max-w-full rounded-full bg-white/70 px-4 py-1.5 text-[#e0457b] lg:mx-0"><Icon name="calendar" className="h-4 w-4 shrink-0" />{c.hero.eyebrow}</p></Reveal>
            <h1 className="lp-h1 mt-7">
              <SplitWords text={c.hero.title1} className="block" />
              <SplitWords text={c.hero.title2} className="block" delay={0.15} />
              <SplitWords text={c.hero.title3} className="block text-[#e0457b]" delay={0.3} />
            </h1>
            <Reveal delay={0.45}><p className="lp-lead mx-auto mt-6 lg:mx-0">{c.hero.sub}</p></Reveal>
            <Reveal delay={0.6}>
              <div className="mt-8 flex flex-wrap justify-center gap-3 lg:justify-start">
                <Link href={register} className="lp-cta lp-cta-lg">{c.hero.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link>
                <a href="#chat" className="lp-cta-ghost lp-cta-lg">{c.hero.cta2}</a>
              </div>
              <ul className="mt-6 flex flex-wrap justify-center gap-x-5 gap-y-2 text-sm text-[#1b1233]/65 lg:justify-start">{c.hero.trust.map((x) => <li key={x} className="flex items-center gap-1.5"><Icon name="check" className="h-4 w-4 text-[#e0457b]" />{x}</li>)}</ul>
            </Reveal>
          </div>
          <HeroDay h={c.hero} />
        </div>
      </section>

      <section id="trades" className="lp-section">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.trades.eyebrow} title={c.trades.title} sub={c.trades.sub} center /></Reveal>
          <ul className="mt-14 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {c.trades.items.map((x, i) => (
              <Reveal as="li" key={x.name} delay={(i % 4) * 0.07}>
                <div className="lp-svc-ticket group h-full rounded-3xl border border-[#1b1233]/10 bg-white p-6 transition hover:-translate-y-1 hover:shadow-[0_30px_60px_-30px_rgb(224_69_123/.35)]">
                  <span className="grid h-11 w-11 place-items-center rounded-2xl bg-[#fde6ec] text-[#e0457b] transition group-hover:bg-[#e0457b] group-hover:text-white"><Icon name={x.icon as never} className="h-5 w-5" /></span>
                  <p className="mt-4 text-lg font-bold text-[#1b1233]">{x.name}</p>
                  <p className="mt-1.5 text-sm leading-relaxed text-[#1b1233]/65">{x.line}</p>
                </div>
              </Reveal>
            ))}
          </ul>
        </div>
      </section>

      <section className="lp-section lp-svc-tint">
        <div className="mx-auto max-w-6xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.position.eyebrow} title={c.position.title} sub={c.position.sub} center /></Reveal>
          <div className="mt-14 grid items-stretch gap-5 lg:grid-cols-[1.35fr_1fr]">
            <Reveal>
              <div className="h-full rounded-[1.75rem] bg-[#1b1233] p-7 text-white sm:p-9">
                <p className="flex items-center gap-2 text-sm font-bold text-[#ff9bbb]"><Icon name="calendar" className="h-4 w-4" />{c.position.mainTitle}</p>
                <ul className="mt-5 space-y-3.5">{c.position.mainItems.map((x) => <li key={x} className="flex items-start gap-3 text-[15px] leading-relaxed text-white/90 sm:text-base"><Icon name="check" className="mt-1 h-5 w-5 shrink-0 text-[#ff7aa3]" />{x}</li>)}</ul>
              </div>
            </Reveal>
            <Reveal delay={0.1}>
              <div className="relative h-full rounded-[1.75rem] border-2 border-dashed border-[#e0457b]/40 bg-white/70 p-7 sm:p-9">
                <span className="absolute -top-3 start-6 rounded-full bg-[#e0457b] px-3 py-1 text-xs font-bold text-white">{c.position.sideTag}</span>
                <p className="flex items-center gap-2 text-sm font-bold text-[#c93a6c]"><Icon name="bag" className="h-4 w-4" />{c.position.sideTitle}</p>
                <p className="mt-4 leading-relaxed text-[#1b1233]/75">{c.position.sideBody}</p>
              </div>
            </Reveal>
          </div>
        </div>
      </section>

      <section className="lp-section">
        <div className="mx-auto max-w-6xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.benefits.eyebrow} title={c.benefits.title} center /></Reveal>
          <div className="mt-14 grid gap-x-10 gap-y-9 sm:grid-cols-2">
            {c.benefits.items.map((b, i) => (
              <Reveal key={b.title} delay={(i % 2) * 0.08}>
                <div className="flex gap-5">
                  <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-[#e0457b] text-white"><Icon name={b.icon as never} className="h-6 w-6" /></span>
                  <div><p className="text-xl font-bold text-[#1b1233]">{b.title}</p><p className="mt-2 leading-relaxed text-[#1b1233]/65">{b.body}</p></div>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      <section id="features" className="lp-section lp-svc-tint">
        <div className="mx-auto max-w-7xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.features.eyebrow} title={c.features.title} center /></Reveal>
          <div className="mt-14 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {c.features.items.map((f, i) => (
              <Reveal key={f.key} delay={(i % 3) * 0.08}>
                <div className="h-full rounded-3xl border border-[#1b1233]/10 bg-white p-7 transition hover:-translate-y-1 hover:shadow-[0_30px_60px_-30px_rgb(224_69_123/.35)]">
                  <span className="grid h-12 w-12 place-items-center rounded-2xl bg-[#fde6ec] text-[#e0457b]"><Icon name={f.icon as never} className="h-6 w-6" /></span>
                  <p className="mt-5 text-xl font-bold text-[#1b1233]">{f.title}</p>
                  <p className="mt-2 leading-relaxed text-[#1b1233]/65">{f.body}</p>
                  <FeatureVisual k={f.key} f={c.features} locale={locale} />
                </div>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      <section className="lp-section">
        <div className="mx-auto grid max-w-6xl items-center gap-14 px-4 sm:px-6 lg:grid-cols-2">
          <Reveal>
            <Head eyebrow={c.book.eyebrow} title={c.book.title} sub={c.book.sub} />
            <ul className="mt-7 space-y-3">{c.book.points.map((x) => <li key={x} className="flex items-center gap-3 font-semibold text-[#1b1233]"><span className="grid h-7 w-7 place-items-center rounded-full bg-[#fde6ec] text-[#e0457b]"><Icon name="check" className="h-4 w-4" /></span>{x}</li>)}</ul>
          </Reveal>
          <Reveal delay={0.1}><BookPhone b={c.book} locale={locale} /></Reveal>
        </div>
      </section>

      <section id="how" className="lp-section lp-svc-tint">
        <div className="mx-auto max-w-6xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.how.eyebrow} title={c.how.title} center /></Reveal>
          <ol className="mt-14 grid gap-5 md:grid-cols-4 md:gap-6">
            {c.how.steps.map((s, i) => (
              <Reveal as="li" key={s.title} delay={i * 0.1} className="lp-svc-ticket rounded-3xl border border-[#1b1233]/10 bg-white p-6">
                <span className="grid h-10 w-10 place-items-center rounded-full bg-[#e0457b] font-display text-lg font-extrabold text-white tnum">{(i + 1).toLocaleString(locale === "ar" ? "ar-EG" : "en-US")}</span>
                <p className="mt-4 text-lg font-bold text-[#1b1233]">{s.title}</p>
                <p className="mt-2 leading-relaxed text-[#1b1233]/65">{s.body}</p>
              </Reveal>
            ))}
          </ol>
        </div>
      </section>

      <section id="chat" className="lp-section">
        <div className="mx-auto max-w-6xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.chat.eyebrow} title={c.chat.title} sub={c.chat.sub} center /></Reveal>
          <Reveal delay={0.1} className="mt-12"><ChatDemo c={c.chat} /></Reveal>
        </div>
      </section>

      <section id="calendar" className="lp-section lp-svc-tint">
        <div className="mx-auto grid max-w-7xl items-center gap-14 px-4 sm:px-6 lg:grid-cols-[0.9fr_1.1fr]">
          <Reveal><Head eyebrow={c.calendar.eyebrow} title={c.calendar.title} sub={c.calendar.sub} /></Reveal>
          <Reveal delay={0.1}><div className="mx-auto max-w-lg"><DiaryMock d={c.calendar} /></div></Reveal>
        </div>
      </section>

      <section id="pricing" className="lp-section">
        <div className="mx-auto max-w-5xl px-4 sm:px-6">
          <Reveal><Head eyebrow={c.pricing.eyebrow} title={c.pricing.title} sub={c.pricing.sub} center /></Reveal>
          <ServicePricing p={c.pricing} locale={locale} register={register} />
        </div>
      </section>

      <section id="faq" className="pb-24">
        <div className="mx-auto max-w-3xl px-4 sm:px-6">
          <Reveal><h2 className="lp-h2 mb-8 text-center">{c.faq.title}</h2></Reveal>
          <Faq items={c.faq.items} className="divide-[#1b1233]/12 border-y border-[#1b1233]/12" />
        </div>
      </section>

      <section className="lp-svc-final relative isolate overflow-hidden py-24 text-center text-white">
        <div className="relative mx-auto max-w-3xl px-4">
          <Reveal><h2 className="lp-h1 text-[clamp(2.2rem,5vw,4rem)]">{c.final.title}</h2></Reveal>
          <Reveal delay={0.1}><p className="lp-lead mx-auto mt-5">{c.final.sub}</p></Reveal>
          <Reveal delay={0.2}><div className="mt-9 flex flex-wrap justify-center gap-3"><Link href={register} className="lp-cta lp-cta-lg">{c.final.cta}<Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link><a href="#pricing" className="lp-cta-ghost lp-cta-lg">{c.final.cta2}</a></div></Reveal>
        </div>
      </section>

      <div className="bg-[#1b1233] text-white">
        <LandingFooter brand={t("brand.name")} tag={c.footer.tag} copyright={t("brand.name")}
          cols={[
            { title: c.footer.product, links: [{ href: "#features", label: c.nav.features }, { href: "#how", label: c.nav.how }, { href: "#pricing", label: c.nav.pricing }, { href: register, label: c.nav.cta }] },
            { title: c.footer.company, links: [{ href: "/", label: c.footer.shops }, { href: "/tabib", label: c.footer.clinics }, { href: "/mohami", label: c.footer.lawyers }, { href: "/privacy", label: c.footer.priv }] },
          ]} />
      </div>
      <div className="lp-sticky-cta sm:hidden"><Link href={register} className="lp-cta w-full justify-center py-3.5">{c.hero.cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
    </div>
  );
}
