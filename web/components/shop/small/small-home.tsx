import Link from "next/link";
import { Icon } from "@/components/icons";
import { ShopProductCard, type ShopProduct } from "@/components/shop/product-card";
import type { ShopBranch, ShopProfile } from "@/components/shop/types";
import { Reveal } from "@/components/shop/templates/reveal";
import type { SmallLook } from "@/components/shop/templates/small-looks";
import { mediaUrl } from "@/lib/media";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import { governorateName } from "@/lib/governorates";
import { whatsappLink } from "@/lib/whatsapp";

type T = (k: string, v?: Record<string, string | number>) => string;
type Props = { look: SmallLook; p: ShopProfile | undefined; products: ShopProduct[]; branches: ShopBranch[]; t: T; locale: Locale };

const pic = (x?: ShopProduct) => (x && (x.imageUrl || x.imageMediaBase) ? mediaUrl({ url: x.imageUrl, mediaBase: x.imageMediaBase, mediaExt: x.imageMediaExt }, "original") : null);
const price = (x: ShopProduct, locale: Locale) => money(x.offer?.salePriceMinor ?? x.minPriceMinor, x.currency, locale);

/* eslint-disable @next/next/no-img-element */
function Buy({ x, t }: { x: ShopProduct; t: T }) { return <Link href={`/products/${x.slug}`} className="s-btn">{t("sm.view")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>; }
function Price({ x, locale }: { x: ShopProduct; locale: Locale }) { return <span className="font-extrabold tnum" style={{ color: "var(--brand)" }}>{x.offer ? <s className="me-2 text-sm font-medium text-[var(--s-mute)]">{money(x.minPriceMinor, x.currency, locale)}</s> : null}{price(x, locale)}</span>; }

function Showcase({ look, shown, t, locale, name, blurb }: { look: SmallLook; shown: ShopProduct[]; t: T; locale: Locale; name: string; blurb: string }) {
  const main = shown[0];
  if (!main) return <section className="s-container pt-12 text-center"><h1 className="s-display text-4xl font-extrabold">{name}</h1><p className="mt-4 text-[var(--s-mute)]">{t("shop.empty")}</p></section>;
  if (look.stage === "trio") return (
    <section className="s-container pt-8 sm:pt-14"><div className="mb-8 text-center"><h1 className="s-display text-[2.4rem] font-extrabold sm:text-6xl">{name}</h1>{blurb && <p className="mx-auto mt-3 max-w-xl text-[var(--s-mute)]">{blurb}</p>}</div>
      <ul className={`grid gap-4 ${shown.length === 1 ? "mx-auto max-w-md" : shown.length === 2 ? "sm:grid-cols-2" : "sm:grid-cols-3"}`}>{shown.map((x) => (
        <li key={x.id}><Link href={`/products/${x.slug}`} className="sm-capsule group block overflow-hidden rounded-[var(--s-radius)] border border-[var(--s-line)] bg-[var(--s-card)]"><div className="aspect-[4/5] overflow-hidden bg-[var(--s-soft)]">{pic(x) && <img src={pic(x)!} alt="" className="h-full w-full object-cover transition duration-700 group-hover:scale-105" />}</div><div className="p-4"><p className="font-extrabold">{x.name}</p><p className="mt-1"><Price x={x} locale={locale} /></p></div></Link></li>))}</ul></section>
  );
  if (look.stage === "list") return (
    <section className="s-container pt-8 sm:pt-14"><h1 className="s-display text-[2.4rem] font-extrabold sm:text-6xl">{name}</h1>{blurb && <p className="mt-3 max-w-xl text-[var(--s-mute)]">{blurb}</p>}
      <ol className="mt-8 divide-y divide-[var(--s-line)] border-y border-[var(--s-line)]">{shown.map((x, i) => (
        <li key={x.id}><Link href={`/products/${x.slug}`} className="sm-row flex items-center gap-4 py-5 sm:gap-8"><span className="s-display w-10 text-3xl font-extrabold text-[var(--s-mute)] tnum">{String(i + 1).padStart(2, "0")}</span><span className="h-24 w-24 shrink-0 overflow-hidden rounded-[var(--s-radius)] bg-[var(--s-soft)] sm:h-32 sm:w-32">{pic(x) && <img src={pic(x)!} alt="" className="h-full w-full object-cover" />}</span><span className="min-w-0 flex-1"><span className="block text-lg font-extrabold sm:text-2xl">{x.name}</span><span className="mt-1 block"><Price x={x} locale={locale} /></span></span><Icon name="arrow" className="h-5 w-5 flip-rtl" /></Link></li>))}</ol></section>
  );
  if (look.stage === "story") return (
    <section className="s-container pt-6 sm:pt-12"><div className="grid items-center gap-8 lg:grid-cols-[0.9fr_1.1fr]">
      <div><p className="s-eyebrow mb-4">{t("sm.new")}</p><h1 className="s-display text-[2.6rem] font-extrabold leading-[1.02] sm:text-[4.4rem]">{main.name}</h1>{blurb && <p className="mt-5 max-w-lg text-lg text-[var(--s-mute)]">{blurb}</p>}<p className="mt-6 text-3xl"><Price x={main} locale={locale} /></p><div className="mt-6 flex flex-wrap gap-3"><Buy x={main} t={t} /></div></div>
      <div className="sm-frame relative aspect-[4/5] overflow-hidden rounded-[var(--s-radius)] bg-[var(--s-soft)]">{pic(main) ? <img src={pic(main)!} alt="" className="s-kenburns h-full w-full object-cover" /> : <div className="bg-brand-gradient h-full w-full" />}</div>
    </div></section>
  );
  if (look.stage === "split") return (
    <section className="s-container pt-6 sm:pt-12"><div className="grid items-center gap-8 lg:grid-cols-[1.1fr_0.9fr] lg:gap-14">
      <div className="sm-frame relative order-1 aspect-square overflow-hidden rounded-[var(--s-radius)] bg-[var(--s-soft)] sm:aspect-[5/4]">{pic(main) ? <img src={pic(main)!} alt="" className="s-kenburns h-full w-full object-cover" /> : <div className="bg-brand-gradient h-full w-full" />}</div>
      <div className="order-2"><p className="s-eyebrow mb-4">{name}</p><h1 className="s-display text-[2.4rem] font-extrabold leading-[1.04] sm:text-[3.8rem]">{main.name}</h1>{blurb && <p className="mt-4 max-w-md text-[var(--s-mute)]">{blurb}</p>}<p className="mt-5 text-3xl"><Price x={main} locale={locale} /></p><div className="mt-6"><Buy x={main} t={t} /></div></div>
    </div></section>
  );
  return (   /* stage: one product, centred, large */
    <section className="s-container pt-8 text-center sm:pt-14"><p className="s-eyebrow mb-3">{name}</p><h1 className="s-display mx-auto max-w-[16ch] text-[2.6rem] font-extrabold leading-[1.02] sm:text-[5rem]">{main.name}</h1>
      <div className="sm-stage relative mx-auto mt-8 aspect-square max-w-md overflow-hidden rounded-[var(--s-radius)] bg-[var(--s-soft)] sm:max-w-lg">{pic(main) ? <img src={pic(main)!} alt="" className="h-full w-full object-cover" /> : <div className="bg-brand-gradient h-full w-full" />}</div>
      <p className="mt-6 text-3xl"><Price x={main} locale={locale} /></p>{blurb && <p className="mx-auto mt-3 max-w-lg text-[var(--s-mute)]">{blurb}</p>}<div className="mt-6 flex justify-center"><Buy x={main} t={t} /></div></section>
  );
}

/** The home page of a shop with very few products: the product is the page. More than three products (the shop grew after choosing this design): the first three are the showcase and every product follows in a grid. */
export function SmallCatalogHome({ look, p, products, branches, t, locale }: Props) {
  const name = p?.storeName ?? "";
  const blurb = p?.shortDescription || p?.about || "";
  const shown = products.slice(0, 3);
  const rest = products.slice(look.stage === "story" || look.stage === "split" || look.stage === "stage" ? 1 : 3);
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  return (
    <div data-small-home>
      <Showcase look={look} shown={shown} t={t} locale={locale} name={name} blurb={blurb} />
      {rest.length > 0 && (
        <section className="s-container s-sec mt-14 sm:mt-20"><Reveal className="mb-6"><h2 className="s-section-title">{products.length > 3 ? t("shop.allProducts") : t("sm.more")}</h2></Reveal>
          <div className="grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-3">{rest.map((x) => <ShopProductCard key={x.id} p={x} locale={locale} labels={labels} variant="minimal" />)}</div>
          {products.length > 3 && <p className="mt-4"><Link href="/products" className="font-extrabold" style={{ color: "var(--brand)" }}>{t("shop.viewAll")}</Link></p>}</section>
      )}
      <section className="s-container mt-14 grid gap-3 sm:grid-cols-3">{[["check", t("trust.cod"), t("trust.codText")], ["navigate", t("trust.delivery"), t("trust.deliveryText")], ["chat", t("trust.support"), t("trust.supportText")]].map(([ic, h, b]) => (
        <div key={h} className="flex items-center gap-3 rounded-2xl border border-[var(--s-line)] bg-[var(--s-card)] p-4"><span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-[var(--s-soft)]" style={{ color: "var(--brand)" }}><Icon name={ic as never} className="h-5 w-5" /></span><div className="min-w-0"><p className="text-sm font-extrabold">{h}</p><p className="line-clamp-2 text-xs text-[var(--s-mute)]">{b}</p></div></div>))}</section>
      {branches.length > 0 && <section className="s-container mt-14"><h2 className="s-section-title mb-4">{t("shop.visitUs")}</h2><div className="grid gap-4 md:grid-cols-2">{branches.slice(0, 2).map((b) => <div key={b.id} className="s-card space-y-1 p-5"><h3 className="font-extrabold">{b.name}</h3><p className="text-sm text-[var(--s-mute)]">{[b.addressLine1, b.area, governorateName(b.governorateCode, locale) || b.city].filter(Boolean).join("، ")}</p>{b.phone && <a href={`tel:${b.phone}`} dir="ltr" className="inline-flex min-h-11 items-center text-sm font-bold" style={{ color: "var(--brand)" }}>{b.phone}</a>}</div>)}</div></section>}
      {p?.whatsapp && <section className="s-container mt-14"><a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" className="s-btn !bg-emerald-500 !shadow-none"><Icon name="chat" className="h-4 w-4" />{t("shop.chatWithUs")}</a></section>}
    </div>
  );
}
