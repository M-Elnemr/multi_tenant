import Link from "next/link";
import { backendJson } from "@/lib/backend";
import { money } from "@/lib/format";
import { getT } from "@/lib/i18n-server";
import { whatsappLink } from "@/lib/whatsapp";
import { currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";
import type { LawyerSite } from "./types";

export const dynamic = "force-dynamic";

const MODE_ICON = { OFFICE: "🏛", ONLINE: "💻", PHONE: "📞" } as const;

/** A scales-of-justice line drawing used as a watermark in the hero. */
function Scales({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 200 200" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden>
      <path d="M100 22v150M62 172h76M100 22a7 7 0 1 0 0 .1M38 52h124M38 52 18 108a22 14 0 0 0 40 0L38 52Zm124 0-20 56a22 14 0 0 0 40 0l-20-56M100 40l-62 12m62-12 62 12" />
    </svg>
  );
}

export default async function LawyerHome() {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const currency = info.kind === "TENANT" ? info.currency : "EGP";
  const p = await backendJson<LawyerSite>("/lawyer/public/profile").catch(() => null);
  if (!p) return <div className="lw-container py-24 text-center">{t("unknown.title")}</div>;
  const name = `${p.title ? p.title + " " : ""}${p.displayName}`;
  const stats = p.achievements.length ? p.achievements : p.yearsExperience ? [{ value: `${p.yearsExperience}+`, label: t("lws.years") }] : [];
  const intl = locale === "ar" ? "ar-EG" : "en-GB";
  const days = [6, 7, 1, 2, 3, 4, 5].map((w) => ({ w, name: new Intl.DateTimeFormat(intl, { weekday: "long" }).format(new Date(2024, 0, w === 7 ? 7 : w)), rows: p.hours.filter((h) => h.weekday === w) }));
  const fmtTime = (s: string) => new Intl.DateTimeFormat(intl, { hour: "numeric", minute: "2-digit" }).format(new Date(`2024-01-01T${s}`));
  const book = p.bookingEnabled !== false;
  const process = [1, 2, 3].map((i) => ({ n: i, title: t(`lws.step${i}`), text: t(`lws.step${i}d`) }));

  return (
    <>
      {/* hero */}
      <section className="lw-hero lw-hero-grain">
        <Scales className="pointer-events-none absolute -bottom-10 h-[420px] w-[420px] opacity-[.06] ltr:-right-16 rtl:-left-16" />
        <div className="lw-container relative grid items-center gap-12 py-16 md:py-24 lg:grid-cols-[1.25fr_1fr]">
          <div className="lw-reveal">
            {p.barLevel && <p className="lw-eyebrow">{p.barLevel}</p>}
            <h1 className="lw-display mt-4 text-[clamp(2.4rem,6vw,4.4rem)] font-bold leading-[1.08]">{p.title && <span className="block text-[.55em] font-semibold opacity-80">{p.title}</span>}{p.displayName}</h1>
            {p.headline && <p className="mt-5 max-w-xl text-lg leading-relaxed opacity-85">{p.headline}</p>}
            <div className="mt-8 flex flex-wrap gap-3">
              {book && <Link href="/book" className="lw-btn">{t("lws.bookCta")} →</Link>}
              {p.whatsapp && <a href={whatsappLink(p.whatsapp, t("lws.waHello"))} target="_blank" rel="noopener noreferrer" className="lw-btn lw-btn-ghost">{t("lws.chat")}</a>}
              {!book && p.phone && <a href={`tel:${p.phone}`} className="lw-btn">{t("lws.call")}</a>}
            </div>
            {stats.length > 0 && (
              <dl className="mt-12 grid max-w-xl grid-cols-3 gap-6 border-t pt-8" style={{ borderColor: "color-mix(in srgb, var(--lw-hero-ink) 18%, transparent)" }}>
                {stats.slice(0, 3).map((s, i) => <div key={i} className="lw-reveal" style={{ animationDelay: `${150 + i * 120}ms` }}><dt className="lw-stat">{s.value}</dt><dd className="mt-2 text-sm opacity-75">{s.label}</dd></div>)}
              </dl>
            )}
          </div>
          <div className="lw-reveal mx-auto w-full max-w-sm" style={{ animationDelay: "200ms" }}>
            <div className="lw-frame">
              {p.photoUrl
                /* eslint-disable-next-line @next/next/no-img-element */
                ? <img src={p.photoUrl} alt={name} className="relative aspect-[4/5] w-full rounded-[calc(var(--lw-radius)+8px)] object-cover shadow-2xl" />
                : <div className="relative grid aspect-[4/5] w-full place-items-center rounded-[calc(var(--lw-radius)+8px)] shadow-2xl" style={{ background: "linear-gradient(160deg, color-mix(in srgb, var(--lw-accent) 30%, var(--lw-hero-2)), var(--lw-hero))" }}><Scales className="h-40 w-40" /></div>}
              {p.yearsExperience > 0 && (
                <div className="absolute -bottom-6 start-6 rounded-2xl px-5 py-3 shadow-xl" style={{ background: "var(--lw-surface)", color: "var(--lw-ink)" }}>
                  <p className="lw-stat text-3xl">{p.yearsExperience}+</p><p className="text-xs font-bold" style={{ color: "var(--lw-mute)" }}>{t("lws.years")}</p>
                </div>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* practice areas */}
      {p.practiceAreas.length > 0 && (
        <section id="areas" className="lw-section scroll-mt-20">
          <div className="lw-container">
            <div className="mx-auto max-w-2xl text-center"><p className="lw-eyebrow justify-center">{t("lws.areasEyebrow")}</p><h2 className="lw-title mt-3">{t("lws.areasTitle")}</h2></div>
            <div className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
              {p.practiceAreas.map((a, i) => (
                <article key={i} className="lw-card lw-lift lw-reveal p-7" style={{ animationDelay: `${i * 70}ms` }}>
                  <span className="lw-icon">{a.icon && a.icon.length <= 3 ? a.icon : "⚖️"}</span>
                  <h3 className="lw-display mt-5 text-xl font-bold">{a.title}</h3>
                  <p className="mt-2 text-sm leading-relaxed" style={{ color: "var(--lw-mute)" }}>{a.description || t("lws.areaDefault")}</p>
                </article>
              ))}
            </div>
          </div>
        </section>
      )}

      {/* about */}
      <section id="about" className="lw-section lw-dark scroll-mt-20">
        <div className="lw-container grid gap-12 lg:grid-cols-[1fr_1.2fr]">
          <div>
            <p className="lw-eyebrow">{t("lws.aboutEyebrow")}</p>
            <h2 className="lw-title mt-3">{name}</h2>
            {p.barLevel && <p className="mt-4 inline-flex rounded-full px-4 py-1.5 text-sm font-bold" style={{ background: "color-mix(in srgb, var(--lw-accent) 20%, transparent)", color: "var(--lw-accent)" }}>⚖ {p.barLevel}</p>}
            {p.barNumber && <p className="mt-3 text-sm opacity-70">{t("lws.barNo")}: <span dir="ltr">{p.barNumber}</span></p>}
            {p.languages.length > 0 && <p className="mt-2 text-sm opacity-70">{t("lws.languages")}: {p.languages.join("، ")}</p>}
          </div>
          <div>
            <p className="whitespace-pre-line text-lg leading-loose opacity-90">{p.bio || t("lws.bioDefault")}</p>
            {p.education.length > 0 && (
              <ol className="mt-10 space-y-5 border-s-2 ps-6" style={{ borderColor: "color-mix(in srgb, var(--lw-accent) 50%, transparent)" }}>
                {p.education.map((e, i) => (
                  <li key={i} className="relative"><span className="absolute -start-[33px] top-1.5 h-4 w-4 rounded-full ring-4" style={{ background: "var(--lw-accent)", ["--tw-ring-color" as string]: "var(--lw-hero)" }} />
                    <p className="font-bold">{e.title}</p><p className="text-sm opacity-70">{e.place}{e.year ? ` · ${e.year}` : ""}</p></li>
                ))}
              </ol>
            )}
          </div>
        </div>
      </section>

      {/* services */}
      {p.services.length > 0 && (
        <section id="services" className="lw-section scroll-mt-20">
          <div className="lw-container">
            <div className="flex flex-wrap items-end justify-between gap-4"><div><p className="lw-eyebrow">{t("lws.servicesEyebrow")}</p><h2 className="lw-title mt-3">{t("lws.servicesTitle")}</h2></div>
              {book && <Link href="/book" className="lw-btn lw-btn-sm">{t("lws.bookCta")}</Link>}</div>
            <div className="mt-10 grid gap-5 md:grid-cols-2 lg:grid-cols-3">
              {p.services.map((s) => (
                <article key={s.id} className="lw-card lw-lift flex flex-col p-7">
                  <div className="flex items-center justify-between"><span className="text-3xl">{MODE_ICON[s.mode]}</span><span className="rounded-full px-3 py-1 text-xs font-bold" style={{ background: "color-mix(in srgb, var(--lw-accent) 14%, transparent)", color: "var(--lw-accent)" }}>{t("lws.minutes", { n: s.durationMinutes })}</span></div>
                  <h3 className="lw-display mt-5 text-xl font-bold">{s.name}</h3>
                  {s.description && <p className="mt-2 flex-1 text-sm leading-relaxed" style={{ color: "var(--lw-mute)" }}>{s.description}</p>}
                  <div className="mt-6 flex items-center justify-between gap-2 border-t pt-5" style={{ borderColor: "var(--lw-line)" }}>
                    <span className="lw-display text-2xl font-bold">{s.priceMinor == null ? <span className="text-base" style={{ color: "var(--lw-mute)" }}>{t(`lws.mode.${s.mode}`)}</span> : s.priceMinor === 0 ? t("lws.free") : money(s.priceMinor, currency, locale)}</span>
                    {book && <Link href={`/book?service=${s.id}`} className="lw-btn lw-btn-sm">{t("lws.book")}</Link>}
                  </div>
                </article>
              ))}
            </div>
          </div>
        </section>
      )}

      {/* how it works */}
      <section className="lw-section pt-0">
        <div className="lw-container">
          <div className="lw-card grid gap-8 overflow-hidden p-8 md:grid-cols-3 md:p-12">
            {process.map((s) => (
              <div key={s.n} className="relative">
                <span className="lw-display text-6xl font-bold opacity-15" style={{ color: "var(--lw-accent)" }}>0{s.n}</span>
                <h3 className="lw-display -mt-6 text-xl font-bold">{s.title}</h3>
                <p className="mt-2 text-sm leading-relaxed" style={{ color: "var(--lw-mute)" }}>{s.text}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* testimonials */}
      {p.testimonials.length > 0 && (
        <section id="reviews" className="lw-section scroll-mt-20 pt-0">
          <div className="lw-container">
            <div className="mx-auto max-w-2xl text-center"><p className="lw-eyebrow justify-center">{t("lws.reviewsEyebrow")}</p><h2 className="lw-title mt-3">{t("lws.reviewsTitle")}</h2></div>
            <div className="mt-12 grid gap-5 md:grid-cols-2 lg:grid-cols-3">
              {p.testimonials.map((r, i) => (
                <figure key={i} className="lw-card p-7">
                  <span className="lw-quote">“</span>
                  <blockquote className="mt-2 leading-relaxed">{r.text}</blockquote>
                  <figcaption className="mt-5 flex items-center justify-between"><b>{r.name}</b><span className="text-amber-500">{"★".repeat(Math.max(1, Math.min(5, r.rating || 5)))}</span></figcaption>
                </figure>
              ))}
            </div>
          </div>
        </section>
      )}

      {/* FAQ */}
      {p.faq.length > 0 && (
        <section className="lw-section pt-0">
          <div className="lw-container grid gap-10 lg:grid-cols-[1fr_1.6fr]">
            <div><p className="lw-eyebrow">{t("lws.faqEyebrow")}</p><h2 className="lw-title mt-3">{t("lws.faqTitle")}</h2></div>
            <div className="space-y-3">
              {p.faq.map((f, i) => (
                <details key={i} className="lw-faq lw-card p-5" open={i === 0}>
                  <summary className="flex items-center justify-between gap-4 font-bold"><span>{f.q}</span><span className="lw-faq-plus grid h-8 w-8 shrink-0 place-items-center rounded-full text-xl transition" style={{ background: "color-mix(in srgb, var(--lw-accent) 14%, transparent)", color: "var(--lw-accent)" }}>+</span></summary>
                  <p className="mt-3 leading-relaxed" style={{ color: "var(--lw-mute)" }}>{f.a}</p>
                </details>
              ))}
            </div>
          </div>
        </section>
      )}

      {/* contact */}
      <section id="contact" className="lw-section scroll-mt-20 pt-0">
        <div className="lw-container">
          <div className="lw-hero overflow-hidden rounded-[calc(var(--lw-radius)+10px)]">
            <div className="grid gap-10 p-8 md:grid-cols-2 md:p-12">
              <div>
                <p className="lw-eyebrow">{t("lws.contactEyebrow")}</p>
                <h2 className="lw-title mt-3">{t("lws.contactTitle")}</h2>
                <p className="mt-3 opacity-80">{t("lws.contactText")}</p>
                <div className="mt-6 flex flex-wrap gap-3">{book && <Link href="/book" className="lw-btn">{t("lws.bookCta")}</Link>}{p.phone && <a href={`tel:${p.phone}`} className="lw-btn lw-btn-ghost" dir="ltr">{p.phone}</a>}</div>
                {(p.officeAddress || p.officeCity) && <p className="mt-6 opacity-85">📍 {[p.officeAddress, p.officeCity].filter(Boolean).join("، ")}</p>}
                {p.mapUrl && <a href={p.mapUrl} target="_blank" rel="noopener noreferrer" className="mt-2 inline-block text-sm font-bold underline underline-offset-4" style={{ color: "var(--lw-accent)" }}>{t("lws.directions")} ↗</a>}
              </div>
              <div className="rounded-2xl p-6" style={{ background: "color-mix(in srgb, var(--lw-hero-ink) 7%, transparent)" }}>
                <p className="mb-4 font-bold">🕘 {t("lws.hours")}</p>
                <ul className="space-y-2 text-sm">{days.map((d) => (
                  <li key={d.w} className="flex justify-between gap-3 border-b pb-2 last:border-0" style={{ borderColor: "color-mix(in srgb, var(--lw-hero-ink) 12%, transparent)" }}>
                    <span className="font-semibold">{d.name}</span><span className="opacity-85" dir="ltr">{d.rows.length ? d.rows.map((r) => `${fmtTime(r.start)} – ${fmtTime(r.end)}`).join(" · ") : t("lws.closed")}</span>
                  </li>
                ))}</ul>
              </div>
            </div>
          </div>
        </div>
      </section>
    </>
  );
}
