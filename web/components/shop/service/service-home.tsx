import Link from "next/link";
import { HoursList } from "@/components/shop/hours";
import { Icon, type IconName } from "@/components/icons";
import { ShopProductCard, type ShopProduct } from "@/components/shop/product-card";
import type { ShopBranch, ShopProfile } from "@/components/shop/types";
import { Reveal } from "@/components/shop/templates/reveal";
import type { ServiceLook } from "@/components/shop/templates/service-looks";
import { fileUrl } from "@/lib/media";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import { governorateName } from "@/lib/governorates";
import { whatsappLink } from "@/lib/whatsapp";

type T = (k: string, v?: Record<string, string | number>) => string;
export type SvcAddon = { id: string; name: string; priceMinor: number; durationMinutes: number };
export type Svc = { id: string; name: string; description?: string | null; shortDescription?: string | null; durationMinutes: number; priceMinor: number; priceBeforeMinor?: number | null; currency: string; categoryName?: string | null; imageFileId?: string | null; locationMode?: "SHOP" | "HOME" | "BOTH"; addons?: SvcAddon[] };
export type SvcConfig = { services: Svc[]; hours?: { weekday: number; opens: string; closes: string }[]; autoConfirm?: boolean };
type Props = { look: ServiceLook; p: ShopProfile | undefined; cfg: SvcConfig; products: ShopProduct[]; branches: ShopBranch[]; t: T; locale: Locale };

const img = (s?: Svc) => (s?.imageFileId ? fileUrl(s.imageFileId, "medium") : null);
const where = (s: Svc, t: T) => (s.locationMode === "HOME" ? t("svh.home") : s.locationMode === "BOTH" ? t("svh.both") : t("svh.shop"));

function Price({ s, locale }: { s: Svc; locale: Locale }) {
  return <span className="font-extrabold tnum" style={{ color: "var(--brand)" }}>{s.priceBeforeMinor ? <s className="me-1.5 text-xs font-medium text-[var(--s-mute)]">{money(s.priceBeforeMinor, s.currency, locale)}</s> : null}{money(s.priceMinor, s.currency, locale)}</span>;
}

/* eslint-disable @next/next/no-img-element */
function ServiceCard({ kind, s, t, locale }: { kind: ServiceLook["card"]; s: Svc; t: T; locale: Locale }) {
  const pic = img(s);
  const body = s.shortDescription || s.description;
  const book = <Link href={`/book?service=${s.id}`} className="s-btn !min-h-11 !px-4 !py-2 !text-sm">{t("svh.book")}</Link>;
  if (kind === "row") return (
    <li className="sv-row flex items-center gap-3 rounded-[var(--s-radius)] border border-[var(--s-line)] bg-[var(--s-card)] p-3 sm:gap-4 sm:p-4">
      {pic && <img src={pic} alt="" className="h-16 w-16 shrink-0 rounded-[calc(var(--s-radius)/1.5)] object-cover sm:h-20 sm:w-20" />}
      <div className="min-w-0 flex-1"><p className="truncate font-extrabold">{s.name}</p><p className="truncate text-xs text-[var(--s-mute)]">{s.durationMinutes} {t("bk.min")} · {where(s, t)}{body ? ` · ${body}` : ""}</p></div>
      <div className="flex shrink-0 flex-col items-end gap-1.5"><Price s={s} locale={locale} />{book}</div>
    </li>
  );
  if (kind === "photo") return (
    <li className="sv-photo relative isolate min-h-64 overflow-hidden rounded-[var(--s-radius)] bg-[var(--s-soft)]">
      {pic ? <img src={pic} alt="" className="absolute inset-0 -z-10 h-full w-full object-cover" /> : <div className="bg-brand-gradient absolute inset-0 -z-10" />}
      <div className="absolute inset-0 -z-10 bg-gradient-to-t from-black/75 via-black/20 to-transparent" />
      <div className="flex h-full min-h-64 flex-col justify-end gap-2 p-4 text-white"><p className="text-lg font-extrabold">{s.name}</p><p className="text-xs opacity-80">{s.durationMinutes} {t("bk.min")} · {where(s, t)}</p><div className="flex items-center justify-between gap-2"><span className="font-extrabold tnum">{money(s.priceMinor, s.currency, locale)}</span>{book}</div></div>
    </li>
  );
  if (kind === "tall") return (
    <li className="sv-tall group flex flex-col overflow-hidden rounded-[var(--s-radius)] border border-[var(--s-line)] bg-[var(--s-card)]">
      <div className="aspect-[4/5] overflow-hidden bg-[var(--s-soft)]">{pic ? <img src={pic} alt="" className="h-full w-full object-cover transition duration-700 group-hover:scale-105" /> : <div className="bg-brand-gradient h-full w-full opacity-70" />}</div>
      <div className="flex flex-1 flex-col gap-1.5 p-4"><p className="font-extrabold">{s.name}</p>{body && <p className="line-clamp-2 text-sm text-[var(--s-mute)]">{body}</p>}<p className="text-xs text-[var(--s-mute)]">{s.durationMinutes} {t("bk.min")}</p><div className="mt-auto flex items-center justify-between gap-2 pt-2"><Price s={s} locale={locale} />{book}</div></div>
    </li>
  );
  return (
    <li className="sv-tile flex flex-col gap-2 rounded-[var(--s-radius)] border border-[var(--s-line)] bg-[var(--s-card)] p-5">
      <span className="grid h-11 w-11 place-items-center rounded-xl bg-[var(--s-soft)]" style={{ color: "var(--brand)" }}><Icon name={(s.locationMode === "HOME" ? "pin" : "clock") as IconName} className="h-5 w-5" /></span>
      <p className="mt-1 font-extrabold">{s.name}</p>{body && <p className="line-clamp-2 text-sm text-[var(--s-mute)]">{body}</p>}
      <p className="text-xs text-[var(--s-mute)]">{s.durationMinutes} {t("bk.min")} · {where(s, t)}</p>
      <div className="mt-auto flex items-center justify-between gap-2 pt-2"><Price s={s} locale={locale} />{book}</div>
    </li>
  );
}

function Hero({ look, p, cfg, t, locale }: Pick<Props, "look" | "p" | "cfg" | "t" | "locale">) {
  const name = p?.storeName ?? "";
  const blurb = p?.shortDescription || p?.about || t("svh.heroDefault");
  const cover = p?.coverFileId ? fileUrl(p.coverFileId, "original") : img(cfg.services.find((s) => s.imageFileId));
  const top = cfg.services.slice(0, 5);
  const cta = (
    <div className="mt-7 flex flex-wrap gap-3">
      <Link href="/book" className="s-btn"><Icon name="calendar" className="h-4 w-4" />{t("svh.bookNow")}</Link>
      <a href="#services" className="s-btn s-btn-ghost">{t("svh.seeServices")}</a>
    </div>
  );
  if (look.hero === "list") return (
    <section className="s-container pt-6 sm:pt-12"><div className="grid items-center gap-8 lg:grid-cols-[1.1fr_0.9fr]">
      <div className="s-reveal"><p className="s-eyebrow mb-4">{t("svh.welcome")}</p><h1 className="s-display text-[2.6rem] font-extrabold leading-[1.02] sm:text-[4.2rem]">{name}</h1><p className="mt-5 max-w-lg text-lg text-[var(--s-mute)]">{blurb}</p>{cta}</div>
      <div className="sv-menu rounded-[var(--s-radius)] border border-[var(--s-line)] bg-[var(--s-card)] p-5 sm:p-6"><p className="mb-3 text-sm font-extrabold uppercase tracking-wider text-[var(--s-mute)]">{t("svh.priceList")}</p>
        <ul className="divide-y divide-[var(--s-line)]">{top.map((s) => <li key={s.id} className="flex items-center justify-between gap-3 py-3"><span className="min-w-0 truncate font-bold">{s.name}<span className="ms-2 text-xs font-medium text-[var(--s-mute)]">{s.durationMinutes} {t("bk.min")}</span></span><Price s={s} locale={locale} /></li>)}</ul></div>
    </div></section>
  );
  if (look.hero === "overlay") return (
    <section className="s-container pt-5 sm:pt-8"><div className="sv-overlay relative isolate overflow-hidden rounded-[var(--s-radius)] bg-[var(--s-ink)]">
      {cover ? <img src={cover} alt="" className="absolute inset-0 -z-10 h-full w-full object-cover opacity-60" /> : <div className="bg-brand-gradient absolute inset-0 -z-10 opacity-70" />}
      <div className="absolute inset-0 -z-10 bg-gradient-to-t from-black/80 via-black/30 to-black/10" />
      <div className="flex min-h-[26rem] flex-col justify-end p-6 text-white sm:min-h-[34rem] sm:p-12"><p className="s-eyebrow mb-3 !text-white/80">{t("svh.welcome")}</p><h1 className="s-display max-w-[16ch] text-[2.4rem] font-extrabold leading-[1.04] sm:text-[4rem]">{name}</h1><p className="mt-4 max-w-xl text-white/80">{blurb}</p>{cta}</div>
    </div></section>
  );
  if (look.hero === "type") return (
    <section className="s-container pt-8 sm:pt-16"><div className="text-center"><p className="s-eyebrow mb-4">{t("svh.welcome")}</p><h1 className="s-display mx-auto max-w-[14ch] text-[3rem] font-extrabold leading-[1] sm:text-[6rem]">{name}</h1><p className="mx-auto mt-6 max-w-xl text-lg text-[var(--s-mute)]">{blurb}</p><div className="flex justify-center">{cta}</div>
      <ul className="s-scroll-x mt-9 flex gap-2 overflow-x-auto pb-1 sm:flex-wrap sm:justify-center">{top.map((s) => <li key={s.id} className="s-chip shrink-0">{s.name}</li>)}</ul></div></section>
  );
  if (look.hero === "plans") return (
    <section className="s-container pt-8 sm:pt-14"><div className="text-center"><p className="s-eyebrow mb-3">{t("svh.welcome")}</p><h1 className="s-display text-[2.6rem] font-extrabold leading-[1.05] sm:text-[4.4rem]">{name}</h1><p className="mx-auto mt-4 max-w-xl text-[var(--s-mute)]">{blurb}</p></div>
      <ul className="mt-9 grid gap-4 md:grid-cols-3">{cfg.services.slice(0, 3).map((s, i) => <li key={s.id} className={`sv-plan flex flex-col gap-3 rounded-[var(--s-radius)] border bg-[var(--s-card)] p-6 text-center ${i === 1 ? "border-[var(--brand)] shadow-[var(--s-shadow-lg)]" : "border-[var(--s-line)]"}`}><p className="text-lg font-extrabold">{s.name}</p><p className="text-3xl"><Price s={s} locale={locale} /></p><p className="text-sm text-[var(--s-mute)]">{s.durationMinutes} {t("bk.min")}</p><Link href={`/book?service=${s.id}`} className="s-btn mt-auto justify-center">{t("svh.book")}</Link></li>)}</ul></section>
  );
  if (look.hero === "tiles") return (
    <section className="s-container pt-6 sm:pt-10"><div className="grid gap-4 lg:grid-cols-[1.2fr_1fr]">
      <div className="sv-bento rounded-[var(--s-radius)] bg-[var(--s-soft)] p-7 sm:p-10"><p className="s-eyebrow mb-3">{t("svh.welcome")}</p><h1 className="s-display text-[2.4rem] font-extrabold leading-[1.04] sm:text-[3.8rem]">{name}</h1><p className="mt-4 max-w-md text-[var(--s-mute)]">{blurb}</p>{cta}</div>
      <ul className="grid grid-cols-2 gap-4">{top.slice(0, 4).map((s) => <li key={s.id}><Link href={`/book?service=${s.id}`} className="sv-bento-tile flex h-full min-h-32 flex-col justify-between rounded-[var(--s-radius)] border border-[var(--s-line)] bg-[var(--s-card)] p-4"><span className="font-extrabold">{s.name}</span><span className="text-sm"><Price s={s} locale={locale} /></span></Link></li>)}</ul>
    </div></section>
  );
  if (look.hero === "slots") return (
    <section className="s-container pt-8 sm:pt-16"><div className="mx-auto max-w-3xl text-center"><p className="s-eyebrow mb-3">{t("svh.welcome")}</p><h1 className="s-display text-[2.6rem] font-extrabold leading-[1.04] sm:text-[4.6rem]">{name}</h1><p className="mx-auto mt-4 max-w-xl text-lg text-[var(--s-mute)]">{blurb}</p>
      <Link href="/book" className="sv-bigcta mt-8 flex min-h-16 items-center justify-between gap-4 rounded-[var(--s-radius)] bg-[var(--s-ink)] px-6 text-start text-white"><span><span className="block text-lg font-extrabold">{t("svh.bookNow")}</span><span className="block text-sm opacity-70">{t("svh.onlyFree")}</span></span><Icon name="arrow" className="h-6 w-6 flip-rtl" /></Link></div></section>
  );
  return (
    <section className="s-container pt-6 sm:pt-12"><div className="grid items-center gap-8 lg:grid-cols-[1.05fr_1fr] lg:gap-14">
      <div className="s-reveal order-2 lg:order-1"><p className="s-eyebrow mb-4">{t("svh.welcome")}</p><h1 className="s-display max-w-[14ch] text-[2.6rem] font-extrabold leading-[1.02] sm:text-[4.2rem]">{name}</h1><p className="mt-5 max-w-lg text-lg text-[var(--s-mute)]">{blurb}</p>{cta}</div>
      <div className="order-1 lg:order-2"><div className="sv-arch relative aspect-[4/5] overflow-hidden rounded-[var(--s-radius)] bg-[var(--s-soft)]">{cover ? <img src={cover} alt="" className="s-kenburns h-full w-full object-cover" /> : <div className="bg-brand-gradient h-full w-full" />}</div></div>
    </div></section>
  );
}

function Section({ id, title, eyebrow, children }: { id?: string; title: string; eyebrow?: string; children: React.ReactNode }) {
  return (
    <section id={id} className="s-container s-sec mt-14 sm:mt-20"><Reveal className="mb-6">{eyebrow && <p className="s-eyebrow mb-1">{eyebrow}</p>}<h2 className="s-section-title">{title}</h2></Reveal>{children}</section>
  );
}

/** The home page of a SERVICE business: services and booking first, how it works, hours and place, offers; products only as a secondary shelf. */
export function ServiceHome({ look, p, cfg, products, branches, t, locale }: Props) {
  const services = cfg.services;
  const groups = Array.from(new Set(services.map((s) => s.categoryName || ""))).map((c) => ({ name: c, items: services.filter((s) => (s.categoryName || "") === c) }));
  const offers = services.filter((s) => s.priceBeforeMinor && s.priceBeforeMinor > s.priceMinor);
  const listClass = look.card === "row" ? "grid gap-3 md:grid-cols-2" : look.card === "photo" ? "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3" : "grid gap-4 sm:grid-cols-2 lg:grid-cols-3";
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  const steps: [IconName, string, string][] = [["sparkles", t("svh.s1"), t("svh.s1b")], ["calendar", t("svh.s2"), t("svh.s2b")], ["check", t("svh.s3"), cfg.autoConfirm ? t("svh.s3auto") : t("svh.s3b")]];
  return (
    <div data-service-home>
      <Hero look={look} p={p} cfg={cfg} t={t} locale={locale} />
      <Section id="services" title={t("svh.services")} eyebrow={t("svh.whatWeDo")}>
        {services.length === 0 ? <p className="rounded-3xl border border-dashed border-[var(--s-line)] p-12 text-center text-[var(--s-mute)]">{t("svh.noServices")}</p> : groups.map((g) => (
          <div key={g.name} className="mb-8">{g.name && <h3 className="mb-3 text-sm font-extrabold uppercase tracking-wider text-[var(--s-mute)]">{g.name}</h3>}<ul className={`stagger ${listClass}`}>{g.items.map((s) => <ServiceCard key={s.id} kind={look.card} s={s} t={t} locale={locale} />)}</ul></div>
        ))}
      </Section>
      {offers.length > 0 && <Section title={t("svh.offers")} eyebrow={t("shop.limitedTime")}><ul className={listClass}>{offers.slice(0, 3).map((s) => <ServiceCard key={s.id} kind={look.card} s={s} t={t} locale={locale} />)}</ul></Section>}
      <Section title={t("svh.how")}><ol className="grid gap-4 md:grid-cols-3">{steps.map(([ic, h, b], i) => <li key={h} className="flex gap-4 rounded-[var(--s-radius)] border border-[var(--s-line)] bg-[var(--s-card)] p-5"><span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-[var(--s-soft)]" style={{ color: "var(--brand)" }}><Icon name={ic} className="h-5 w-5" /></span><div><p className="font-extrabold">{i + 1}. {h}</p><p className="mt-1 text-sm text-[var(--s-mute)]">{b}</p></div></li>)}</ol></Section>
      {products.length > 0 && (
        <Section title={t("svh.products")} eyebrow={t("svh.productsHint")}>
          <div className="grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-4">{products.slice(0, 4).map((x) => <ShopProductCard key={x.id} p={x} locale={locale} labels={labels} variant="minimal" />)}</div>
          <p className="mt-4"><Link href="/products" className="font-extrabold" style={{ color: "var(--brand)" }}>{t("shop.viewAll")}</Link></p>
        </Section>
      )}
      {branches.length > 0 && (
        <Section title={t("svh.visit")} eyebrow={t("shop.branches")}>
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">{branches.slice(0, 3).map((b) => (
            <div key={b.id} className="s-card space-y-2 p-5"><h3 className="text-lg font-extrabold">{b.name}</h3><p className="text-sm text-[var(--s-mute)]">{[b.addressLine1, b.area, governorateName(b.governorateCode, locale) || b.city].filter(Boolean).join("، ")}</p>
              {b.phone && <a href={`tel:${b.phone}`} dir="ltr" className="inline-flex min-h-11 items-center text-sm font-bold" style={{ color: "var(--brand)" }}>{b.phone}</a>}
              {b.workingHours && <details className="text-sm"><summary className="min-h-11 cursor-pointer font-semibold">{t("identity.hours")}</summary><HoursList hours={b.workingHours} t={t} className="mt-2 text-[var(--s-mute)]" /></details>}</div>
          ))}</div>
        </Section>
      )}
      {p?.whatsapp && <section className="s-container mt-14"><a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" className="s-btn !bg-emerald-500 !shadow-none"><Icon name="chat" className="h-4 w-4" />{t("shop.chatWithUs")}</a></section>}
    </div>
  );
}
