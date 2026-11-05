import Link from "next/link";
import type { ReactNode } from "react";
import { Icon, type IconName } from "../../icons";
import { BannerCarousel, type Slide } from "../hero";
import type { ShopProduct } from "../product-card";
import type { CategoryNode, ShopProfile } from "../types";
import { fileUrl, mediaUrl } from "@/lib/media";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import type { CatsKind, HeroKind } from "./registry";
import { Reveal } from "./reveal";

type T = (k: string, v?: Record<string, string | number>) => string;
export type HeroProps = { kind: HeroKind; p: ShopProfile | null | undefined; slides: Slide[]; featured: ShopProduct[]; cats: CategoryNode[]; offers: number; t: T; locale: Locale };

const img = (x?: ShopProduct) => (x && (x.imageUrl || x.imageMediaBase) ? mediaUrl({ url: x.imageUrl, mediaBase: x.imageMediaBase, mediaExt: x.imageMediaExt }, "medium") : null);
const price = (x: ShopProduct, locale: Locale) => money(x.offer?.salePriceMinor ?? x.minPriceMinor, x.currency, locale);

/** The main picture of the shop: the first banner, else the cover photo, else the first product photo. */
function mainImage(p: HeroProps["p"], slides: Slide[], featured: ShopProduct[]) {
  return slides[0]?.imageUrl ?? (p?.coverFileId ? fileUrl(p.coverFileId, "original") : null) ?? img(featured[0]);
}

function Cta({ t, offers, light }: { t: T; offers: number; light?: boolean }) {
  return (
    <div className="mt-7 flex flex-wrap gap-3">
      <Link href="/products" className={`s-btn ${light ? "!bg-white !text-[#111]" : ""}`}>{t("shop.browse")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>
      {offers > 0 && <Link href="/products?onSale=true" className={`s-btn s-btn-ghost ${light ? "!border-white/40 !bg-white/10 !text-white backdrop-blur" : ""}`}><Icon name="tag" className="h-4 w-4" />{t("shop.offers")}</Link>}
    </div>
  );
}

/* eslint-disable @next/next/no-img-element */
export function Hero({ kind, p, slides, featured, cats, offers, t, locale }: HeroProps) {
  const name = p?.storeName ?? "";
  const blurb = p?.shortDescription || p?.about || "";
  const main = mainImage(p, slides, featured);

  if (kind === "carousel" && slides.length > 0) return <section className="s-container pt-5 sm:pt-8"><BannerCarousel slides={slides} cta={t("shop.browse")} /></section>;

  if (kind === "split" || kind === "carousel") {
    const chip = featured[0];
    return (
      <section className="s-container pt-6 sm:pt-12">
        <div className="s-hero-split grid items-center gap-8 lg:grid-cols-[1.05fr_1fr] lg:gap-14">
          <div className="s-reveal order-2 lg:order-1">
            <p className="s-eyebrow mb-4">{t("shop.welcomeTo")}</p>
            <h1 className="s-display max-w-[14ch] text-[2.6rem] font-extrabold leading-[1.02] sm:text-[4.2rem]">{name}</h1>
            {blurb && <p className="mt-5 max-w-lg text-lg leading-relaxed text-[var(--s-mute)]">{blurb}</p>}
            <Cta t={t} offers={offers} />
            {cats.length > 0 && <div className="mt-8 flex flex-wrap gap-2">{cats.slice(0, 4).map((c) => <Link key={c.id} href={`/c/${c.slug}`} className="s-chip">{c.name}</Link>)}</div>}
          </div>
          <div className="relative order-1 lg:order-2">
            <div className="s-hero relative aspect-[4/5] overflow-hidden rounded-[28px] bg-[var(--s-soft)] sm:aspect-[5/5]">
              {main ? <img src={main} alt="" className="s-kenburns h-full w-full object-cover" /> : <div className="bg-brand-gradient h-full w-full" />}
            </div>
            {chip && (
              <Link href={`/products/${chip.slug}`} className="s-float-card absolute -bottom-5 start-4 flex max-w-[16rem] items-center gap-3 rounded-[18px] bg-[var(--s-card)] p-2.5 pe-4 shadow-[var(--s-shadow-lg)] sm:start-[-2rem]">
                <span className="h-14 w-14 shrink-0 overflow-hidden rounded-[12px] bg-[var(--s-soft)]">{img(chip) && <img src={img(chip)!} alt="" className="h-full w-full object-cover" />}</span>
                <span className="min-w-0"><span className="block truncate text-sm font-bold">{chip.name}</span><span className="text-sm font-extrabold" style={{ color: "var(--brand)" }}>{price(chip, locale)}</span></span>
              </Link>
            )}
          </div>
        </div>
      </section>
    );
  }

  if (kind === "editorial") {
    return (
      <section className="s-hero-editorial">
        <div className="relative h-[78svh] min-h-[30rem] overflow-hidden bg-[var(--s-soft)]">
          {main ? <img src={main} alt="" className="s-kenburns absolute inset-0 h-full w-full object-cover" /> : <div className="bg-brand-gradient absolute inset-0" />}
          <div className="absolute inset-0 bg-gradient-to-t from-black/55 via-black/10 to-transparent" />
          <div className="s-container relative flex h-full flex-col justify-end pb-12 text-white sm:pb-20">
            <p className="mb-5 text-[11px] uppercase tracking-[0.4em] text-white/75">N° 01 — {t("shop.collection")}</p>
            <h1 className="s-display max-w-[13ch] text-[3rem] leading-[0.98] sm:text-[6rem]">{name}</h1>
            {blurb && <p className="mt-5 max-w-md text-base text-white/85 sm:text-lg">{blurb}</p>}
            <div className="mt-8"><Link href="/products" className="s-btn !bg-white !text-[#111]">{t("shop.discover")}</Link></div>
          </div>
        </div>
        {featured.length >= 2 && (
          <div className="s-container mt-16 grid items-end gap-6 sm:mt-24 md:grid-cols-[1fr_1.2fr_1fr]">
            <Reveal className="hidden md:block"><EditorialFigure x={featured[1]} locale={locale} /></Reveal>
            <Reveal className="text-center md:px-6">
              <p className="s-eyebrow mb-4">{t("shop.handpicked")}</p>
              <p className="s-display text-[1.7rem] leading-snug sm:text-[2.2rem]">{blurb ? `“${blurb.slice(0, 140)}${blurb.length > 140 ? "…" : ""}”` : t("shop.editorialLine")}</p>
              <Link href="/products" className="mt-6 inline-block text-sm font-semibold underline underline-offset-8">{t("shop.viewAll")}</Link>
            </Reveal>
            <Reveal><EditorialFigure x={featured[0]} locale={locale} /></Reveal>
          </div>
        )}
      </section>
    );
  }

  if (kind === "immersive") {
    return (
      <section className="s-hero-immersive relative -mt-[var(--s-header-h,0px)] h-[100svh] min-h-[34rem] overflow-hidden bg-[#1d1b18] text-white">
        {(slides.length ? slides.map((s) => s.imageUrl) : main ? [main] : []).slice(0, 3).map((src, i, arr) => (
          <img key={src} src={src} alt="" className="s-crossfade s-kenburns absolute inset-0 h-full w-full object-cover" style={{ animationDelay: `${i * 6}s`, ["--n" as string]: arr.length }} />
        ))}
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,rgb(0_0_0/.15),rgb(0_0_0/.55))]" />
        <div className="relative flex h-full flex-col items-center justify-center px-6 text-center">
          <p className="s-reveal mb-6 text-[11px] uppercase tracking-[0.5em] text-white/80">{t("shop.welcomeTo")}</p>
          <h1 className="s-reveal s-display max-w-[16ch] text-[3.2rem] leading-[1] sm:text-[6.5rem]" style={{ animationDelay: ".1s" }}>{name}</h1>
          {blurb && <p className="s-reveal mt-6 max-w-xl text-white/85 sm:text-lg" style={{ animationDelay: ".2s" }}>{blurb}</p>}
          <div className="s-reveal" style={{ animationDelay: ".3s" }}><Cta t={t} offers={offers} light /></div>
        </div>
        <a href="#s-after-hero" className="absolute bottom-6 start-1/2 flex -translate-x-1/2 flex-col items-center gap-2 text-[10px] uppercase tracking-[0.3em] text-white/70 rtl:translate-x-1/2"><span>{t("shop.scroll")}</span><span className="s-scroll-cue h-10 w-px bg-white/60" /></a>
        <span id="s-after-hero" className="absolute bottom-0" />
      </section>
    );
  }

  if (kind === "type") {
    const shots = featured.slice(0, 3);
    return (
      <section className="s-hero-type overflow-hidden pt-6 sm:pt-10">
        <div className="relative" dir="ltr">
          <div className="s-marquee s-marquee-ltr whitespace-nowrap py-2" aria-hidden>
            {Array.from({ length: 6 }).map((_, i) => <span key={i} className={`s-type-word mx-4 inline-block text-[4.5rem] font-black uppercase leading-none sm:text-[9rem] ${i % 2 ? "s-outline" : ""}`}>{name}</span>)}
          </div>
          <h1 className="sr-only">{name}</h1>
        </div>
        <div className="s-container mt-6 grid items-center gap-8 lg:grid-cols-[1fr_1.3fr]">
          <div>
            <span className="s-sticker-tag inline-block rotate-[-3deg] px-3 py-1.5 text-sm font-black uppercase">{t("shop.newDrop")}</span>
            {blurb && <p className="mt-5 max-w-md text-lg font-semibold">{blurb}</p>}
            <Cta t={t} offers={offers} />
          </div>
          <div className="relative h-[22rem] sm:h-[28rem]">
            {(shots.length ? shots : [undefined]).map((x, i) => {
              const src = x ? img(x) : main;
              const pos = ["start-0 top-6 w-[52%] -rotate-6 z-10", "end-0 top-0 w-[46%] rotate-[5deg] z-20", "start-[28%] bottom-0 w-[40%] rotate-[-2deg] z-30"][i];
              return (
                <Link key={i} href={x ? `/products/${x.slug}` : "/products"} className={`s-shot absolute aspect-[4/5] overflow-hidden border-[3px] border-[var(--s-ink)] bg-[var(--s-soft)] shadow-[8px_8px_0_var(--s-ink)] transition hover:z-40 hover:rotate-0 ${pos}`}>
                  {src && <img src={src} alt={x?.name ?? ""} className="h-full w-full object-cover" />}
                  {x && <span className="absolute bottom-2 start-2 bg-[var(--s-ink)] px-2 py-0.5 text-xs font-black text-white">{price(x, locale)}</span>}
                </Link>
              );
            })}
          </div>
        </div>
      </section>
    );
  }

  if (kind === "bento") {
    const [a, b] = featured;
    return (
      <section className="s-container pt-6 sm:pt-10">
        <div className="s-bento grid gap-3 sm:grid-cols-4 sm:grid-rows-[repeat(2,minmax(13rem,1fr))]">
          <div className="s-tile relative overflow-hidden rounded-[22px] sm:col-span-2 sm:row-span-2">
            {main && <img src={main} alt="" className="s-kenburns absolute inset-0 h-full w-full object-cover opacity-70" />}
            <div className="absolute inset-0 bg-gradient-to-t from-[var(--s-bg)] via-[color-mix(in_srgb,var(--s-bg)_40%,transparent)] to-transparent" />
            <div className="relative flex h-full min-h-[22rem] flex-col justify-end p-6 sm:p-9">
              <p className="s-mono mb-3 text-xs text-[var(--brand)]">{"// "}{t("shop.welcomeTo")}</p>
              <h1 className="s-display text-[2.6rem] font-bold leading-[1] sm:text-[3.6rem]">{name}</h1>
              {blurb && <p className="mt-3 max-w-md text-[var(--s-mute)]">{blurb}</p>}
              <Cta t={t} offers={offers} />
            </div>
          </div>
          {[a, b].map((x, i) => x ? (
            <Link key={x.id} href={`/products/${x.slug}`} className="s-tile group relative overflow-hidden rounded-[22px] p-5">
              <p className="s-mono text-[11px] text-[var(--s-mute)]">0{i + 1} / {t("shop.featured")}</p>
              <p className="mt-1 line-clamp-1 font-semibold">{x.name}</p>
              <p className="s-mono text-sm text-[var(--brand)]">{price(x, locale)}</p>
              {img(x) && <img src={img(x)!} alt="" className="absolute -bottom-4 end-[-8%] h-[70%] w-[70%] object-contain transition duration-700 group-hover:scale-110 group-hover:-rotate-3" />}
            </Link>
          ) : null)}
          <Link href="/products" className="s-tile group flex flex-col justify-between rounded-[22px] p-5 sm:col-span-2">
            <p className="s-mono text-[11px] text-[var(--s-mute)]">{t("shop.categories")}</p>
            <div className="flex flex-wrap gap-2">{cats.slice(0, 6).map((c) => <span key={c.id} className="rounded-full border border-[var(--s-line)] px-3 py-1 text-sm">{c.name}</span>)}</div>
            <p className="flex items-center gap-2 text-sm font-semibold text-[var(--brand)]">{t("shop.allProducts")}<Icon name="arrow" className="h-4 w-4 transition group-hover:translate-x-1 flip-rtl" /></p>
          </Link>
        </div>
      </section>
    );
  }

  if (kind === "menu") {
    return (
      <section className="s-container pt-5 sm:pt-8">
        <div className="s-hero-menu grid overflow-hidden rounded-[28px] bg-[var(--s-card)] shadow-[var(--s-shadow)] lg:grid-cols-[1.15fr_1fr]">
          <div className="relative min-h-[18rem] overflow-hidden bg-[var(--s-soft)] sm:min-h-[26rem]">
            {main ? <img src={main} alt="" className="s-kenburns absolute inset-0 h-full w-full object-cover" /> : <div className="bg-brand-gradient absolute inset-0" />}
            <span className={`absolute start-4 top-4 inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-xs font-bold backdrop-blur ${p?.isOpen === false ? "bg-black/60 text-white" : "bg-white/90 text-emerald-700"}`}><span className={`h-2 w-2 rounded-full ${p?.isOpen === false ? "bg-slate-300" : "bg-emerald-500 live-dot"}`} />{p?.isOpen === false ? t("shop.closedNow") : t("shop.openNow")}</span>
          </div>
          <div className="flex flex-col justify-center p-7 sm:p-12">
            <p className="s-eyebrow mb-3 flex items-center gap-2"><Icon name="utensils" className="h-4 w-4" />{t("shop.welcomeTo")}</p>
            <h1 className="s-display text-[2.4rem] font-extrabold leading-[1.05] sm:text-[3.4rem]">{name}</h1>
            {blurb && <p className="mt-4 text-[var(--s-mute)] sm:text-lg">{blurb}</p>}
            <div className="mt-7 flex flex-wrap gap-3"><Link href="/products" className="s-btn">{t("shop.orderNow")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>{offers > 0 && <Link href="/products?onSale=true" className="s-btn s-btn-ghost">{t("shop.offers")}</Link>}</div>
            {p?.minOrderMinor ? <p className="mt-5 text-sm text-[var(--s-mute)]">{t("shop.minOrder", { m: money(p.minOrderMinor, featured[0]?.currency ?? "EGP", locale) })}</p> : null}
          </div>
        </div>
      </section>
    );
  }

  if (kind === "playful") {
    const pops = featured.slice(0, 3);
    return (
      <section className="s-container pt-6 sm:pt-10">
        <div className="s-hero-play relative overflow-hidden rounded-[36px] px-6 py-12 sm:px-14 sm:py-20">
          <span className="s-blob s-blob-1" /><span className="s-blob s-blob-2" />
          <div className="relative grid items-center gap-10 lg:grid-cols-[1.1fr_1fr]">
            <div>
              <p className="s-eyebrow mb-4">{t("shop.welcomeTo")}</p>
              <h1 className="s-display text-[2.8rem] font-extrabold leading-[1.02] sm:text-[4.4rem]">
                <span className="relative inline-block">{name}<svg viewBox="0 0 300 20" preserveAspectRatio="none" className="absolute -bottom-3 start-0 h-4 w-full" aria-hidden><path d="M2 14 C 60 2, 120 22, 180 10 S 280 4, 298 12" fill="none" stroke="var(--brand)" strokeWidth="5" strokeLinecap="round" className="s-squiggle" /></svg></span>
              </h1>
              {blurb && <p className="mt-7 max-w-md text-lg text-[var(--s-mute)]">{blurb}</p>}
              <Cta t={t} offers={offers} />
            </div>
            <div className="relative mx-auto h-[20rem] w-full max-w-md sm:h-[24rem]">
              {(pops.length ? pops : [undefined]).map((x, i) => {
                const src = x ? img(x) : main;
                const pos = ["start-[8%] top-0 h-[62%]", "end-0 top-[22%] h-[52%]", "start-[30%] bottom-0 h-[44%]"][i];
                return (
                  <Link key={i} href={x ? `/products/${x.slug}` : "/products"} className={`s-pop absolute aspect-square overflow-hidden rounded-full border-[6px] border-white bg-[var(--s-soft)] shadow-[var(--s-shadow-lg)] ${pos}`} style={{ animationDelay: `${i * 0.8}s` }}>
                    {src && <img src={src} alt={x?.name ?? ""} className="h-full w-full object-cover" />}
                  </Link>
                );
              })}
              <span className="s-sticker-round absolute end-[6%] top-[2%] grid h-20 w-20 rotate-12 place-items-center rounded-full text-center text-xs font-extrabold leading-tight">{t("shop.newDrop")}</span>
            </div>
          </div>
        </div>
      </section>
    );
  }

  // arch (organic boutique): an arch-framed photo with a quiet story beside it
  return (
    <section className="s-container pt-8 sm:pt-14">
      <div className="s-hero-arch grid items-center gap-10 lg:grid-cols-[1fr_1fr] lg:gap-20">
        <div className="relative mx-auto w-full max-w-md">
          <div className="s-arch relative aspect-[3/4] overflow-hidden rounded-t-full bg-[var(--s-soft)]">
            {main ? <img src={main} alt="" className="s-kenburns h-full w-full object-cover" /> : <div className="bg-brand-gradient h-full w-full" />}
          </div>
          <Icon name="leaf" className="s-leaf absolute -bottom-6 -start-6 h-20 w-20" />
        </div>
        <div className="s-reveal">
          <p className="s-eyebrow mb-4">{t("shop.welcomeTo")}</p>
          <h1 className="s-display text-[2.6rem] leading-[1.05] sm:text-[4rem]">{name}</h1>
          {blurb && <p className="mt-6 max-w-md text-lg leading-relaxed text-[var(--s-mute)]">{blurb}</p>}
          <Cta t={t} offers={offers} />
        </div>
      </div>
    </section>
  );
}

function EditorialFigure({ x, locale }: { x: ShopProduct; locale: Locale }) {
  return (
    <Link href={`/products/${x.slug}`} className="group block">
      <div className="aspect-[3/4] overflow-hidden bg-[var(--s-soft)]">{img(x) && <img src={img(x)!} alt={x.name} className="h-full w-full object-cover transition duration-[1.2s] group-hover:scale-105" />}</div>
      <p className="mt-3 text-sm font-semibold">{x.name}</p>
      <p className="text-sm text-[var(--s-mute)]">{price(x, locale)}</p>
    </Link>
  );
}

/* ───────────── Category navigation ───────────── */

export function Categories({ kind, cats, t, head }: { kind: CatsKind; cats: CategoryNode[]; t: T; head: (body: ReactNode) => ReactNode }) {
  if (!cats.length) return null;
  if (kind === "menu") {
    // food: a sticky menu bar of sections
    return (
      <div className="s-menu-bar sticky top-[var(--s-header-h,4.5rem)] z-30 mt-8 border-y border-[var(--s-line)] bg-[var(--s-bg)]/95 backdrop-blur">
        <div className="s-container s-scroll-x !gap-1 py-2">
          {cats.map((c) => <Link key={c.id} href={`/c/${c.slug}`} className="s-menu-tab whitespace-nowrap rounded-full px-4 py-2 text-sm font-bold transition hover:bg-[var(--s-soft)]">{c.icon && <span className="me-1.5" aria-hidden>{c.icon}</span>}{c.name}</Link>)}
        </div>
      </div>
    );
  }
  if (kind === "index") {
    return head(
      <ol className="s-cat-index grid border-t border-[var(--s-line)] md:grid-cols-2 md:gap-x-12">
        {cats.slice(0, 8).map((c, i) => (
          <li key={c.id}>
            <Link href={`/c/${c.slug}`} className="group flex items-baseline gap-5 border-b border-[var(--s-line)] py-5 transition">
              <span className="s-mono w-8 shrink-0 text-xs text-[var(--s-mute)]">{String(i + 1).padStart(2, "0")}</span>
              <span className="s-display flex-1 text-2xl transition group-hover:translate-x-2 rtl:group-hover:-translate-x-2 sm:text-3xl">{c.name}</span>
              <span className="text-xs text-[var(--s-mute)]">{t("shop.itemsCount", { n: c.productCount })}</span>
              <Icon name="arrow" className="h-4 w-4 opacity-0 transition group-hover:opacity-100 flip-rtl" />
            </Link>
          </li>
        ))}
      </ol>,
    );
  }
  if (kind === "chips") {
    return head(
      <div className="s-scroll-x !gap-2">
        {cats.map((c) => <Link key={c.id} href={`/c/${c.slug}`} className="s-chip !px-4 !py-2.5">{c.icon && <span aria-hidden>{c.icon}</span>}{c.name}<span className="text-[var(--s-mute)]">{c.productCount}</span></Link>)}
      </div>,
    );
  }
  if (kind === "circles") {
    return head(
      <div className="s-scroll-x s-cats !gap-5">
        {cats.map((c) => (
          <Link key={c.id} href={`/c/${c.slug}`} className="group w-20 text-center sm:w-24">
            <span className="s-circle mx-auto grid aspect-square w-full place-items-center rounded-full border border-[var(--s-line)] bg-[var(--s-soft)] text-3xl transition duration-300 group-hover:scale-105 group-hover:border-[var(--brand)] sm:text-4xl" aria-hidden>{c.icon || c.name.charAt(0)}</span>
            <span className="mt-2 block truncate text-xs font-bold sm:text-sm">{c.name}</span>
          </Link>
        ))}
      </div>,
    );
  }
  if (kind === "blocks") {
    return head(
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {cats.slice(0, 8).map((c) => (
          <Link key={c.id} href={`/c/${c.slug}`} className="s-block group relative flex aspect-[5/3] flex-col justify-between overflow-hidden border-[3px] border-[var(--s-ink)] p-3 transition hover:-translate-y-1 sm:p-4">
            <span className="text-xs font-black">{t("shop.itemsCount", { n: c.productCount })}</span>
            <span className="text-lg font-black uppercase leading-none sm:text-2xl">{c.name}</span>
            <Icon name="arrow" className="absolute end-3 top-3 h-5 w-5 transition group-hover:rotate-[-45deg] flip-rtl" />
          </Link>
        ))}
      </div>,
    );
  }
  // tiles
  return head(
    <div className="s-scroll-x s-cats">
      {cats.map((c) => (
        <Link key={c.id} href={`/c/${c.slug}`} className="s-lift group w-36 sm:w-44">
          <div className="relative grid aspect-square place-items-center overflow-hidden rounded-[26px] border border-[var(--s-line)] bg-[var(--s-soft)] transition duration-500 group-hover:bg-[var(--s-card)]">
            <span className="text-6xl transition duration-500 group-hover:scale-125 sm:text-7xl" aria-hidden>{c.icon || c.name.charAt(0)}</span>
            <span className="absolute inset-x-0 bottom-0 h-1.5 origin-center scale-x-0 transition duration-500 group-hover:scale-x-100" style={{ background: "var(--brand)" }} />
          </div>
          <p className="mt-2.5 text-center text-sm font-extrabold">{c.name}</p>
          <p className="text-center text-xs text-[var(--s-mute)]">{t("shop.itemsCount", { n: c.productCount })}</p>
        </Link>
      ))}
    </div>,
  );
}

export const TRUST_ICONS: IconName[] = ["wallet", "truck", "return", "chat", "star", "sparkles"];
