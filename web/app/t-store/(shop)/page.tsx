import Link from "next/link";
import { HoursList } from "@/components/shop/hours";
import { ShopProductCard, type ShopProduct } from "@/components/shop/product-card";
import { OfferStrip, type OfferBanner } from "@/components/shop/offer-strip";
import { buildTree, type ShopBranch, type ShopCategory, type ShopProfile } from "@/components/shop/types";
import { backendJson } from "@/lib/backend";
import { governorateName } from "@/lib/governorates";
import { fileUrl } from "@/lib/media";
import { whatsappLink } from "@/lib/whatsapp";
import { getT } from "@/lib/i18n-server";
import { STORE_SECTIONS, arrange } from "@/lib/site-layout";
import { wantsEdit } from "@/lib/edit-mode";
import { EditBar, EditFrame, EditProvider } from "@/components/site-edit";
import { Icon } from "@/components/icons";
import { shopLook } from "@/lib/shop-look";
import type { CardKind, GridKind } from "@/components/shop/templates/registry";
import { Categories, Hero, TRUST_ICONS } from "@/components/shop/templates/sections";
import { Reveal } from "@/components/shop/templates/reveal";

type Home = { offerBanners?: OfferBanner[]; featured: ShopProduct[]; newest: ShopProduct[]; offers: ShopProduct[]; bestSellers: ShopProduct[]; banners: { id: string; imageFileId: string; title: string; subtitle: string; linkUrl: string }[] };

function Section({ title, eyebrow, href, more, children }: { title: string; eyebrow?: string; href?: string; more?: string; children: React.ReactNode }) {
  return (
    <section className="s-container s-sec mt-14 sm:mt-20">
      <Reveal className="s-sec-head mb-6 flex items-end justify-between gap-4">
        <div>{eyebrow && <p className="s-eyebrow mb-1">{eyebrow}</p>}<h2 className="s-section-title">{title}</h2></div>
        {href && more && <Link href={href} className="s-more group inline-flex shrink-0 items-center gap-1.5 text-sm font-extrabold" style={{ color: "var(--brand)" }}>{more}<Icon name="arrow" className="h-4 w-4 transition-transform group-hover:translate-x-1 flip-rtl rtl:group-hover:-translate-x-1" /></Link>}
      </Reveal>
      {children}
    </section>
  );
}

export const GRID_CLASS: Record<GridKind, string> = {
  g3: "grid grid-cols-2 gap-3 sm:gap-6 lg:grid-cols-3",
  g4: "grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-3 lg:grid-cols-4",
  g5: "grid grid-cols-2 gap-2.5 sm:gap-3 md:grid-cols-4 lg:grid-cols-5",
  rail: "s-rail flex gap-4 overflow-x-auto snap-x snap-mandatory pb-3 [&>*]:w-[46%] [&>*]:shrink-0 [&>*]:snap-start sm:[&>*]:w-[30%] lg:[&>*]:w-[23%]",
  list: "grid gap-3 md:grid-cols-2",
};
const PER_ROW: Record<GridKind, number> = { g3: 3, g4: 4, g5: 5, rail: 1, list: 2 };

function Grid({ items, locale, labels, card, grid }: { items: ShopProduct[]; locale: "ar" | "en"; labels: React.ComponentProps<typeof ShopProductCard>["labels"]; card: CardKind; grid: GridKind }) {
  // show complete rows only so a lone product never sits by itself on the last row
  const per = PER_ROW[grid], max = grid === "rail" ? 10 : per * 2;
  const capped = items.slice(0, max);
  const shown = capped.length >= per && grid !== "rail" ? capped.slice(0, capped.length - (capped.length % per)) : capped;
  return <div className={`s-grid s-grid-home stagger ${GRID_CLASS[grid]}`} data-grid={grid}>{shown.map((p, i) => <div key={p.id} style={{ "--i": i } as React.CSSProperties}><ShopProductCard p={p} locale={locale} labels={labels} variant={card} /></div>)}</div>;
}

export default async function StoreHome({ searchParams }: { searchParams?: Promise<Record<string, string | string[] | undefined>> }) {
  const { t, locale } = await getT();
  const edit = await wantsEdit(searchParams);
  const [{ profile: prof, look }, cats, home, branches] = await Promise.all([
    shopLook(),
    backendJson<ShopCategory[]>(`/shop/categories?lang=${locale}`).catch(() => [] as ShopCategory[]),
    backendJson<Home>(`/shop/home?lang=${locale}`).catch(() => ({ featured: [], newest: [], offers: [], bestSellers: [], banners: [] }) as Home),
    backendJson<ShopBranch[]>("/shop/branches").catch(() => [] as ShopBranch[]),
  ]);
  const p = prof ?? undefined;
  const { card, grid } = look;
  const tops = buildTree(cats).filter((c) => c.productCount > 0).slice(0, 12);
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  const slides = home.banners.map((b) => ({ id: b.id, imageUrl: fileUrl(b.imageFileId, "original"), title: b.title, subtitle: b.subtitle, href: b.linkUrl || undefined }));
  const days = p?.returnWindowDays ?? 14;
  const trust = [
    { icon: TRUST_ICONS[0], title: t("trust.cod"), text: t("trust.codText") },
    { icon: TRUST_ICONS[1], title: t("trust.delivery"), text: t("trust.deliveryText") },
    { icon: TRUST_ICONS[2], title: t("trust.returns"), text: t("trust.returnsText", { n: days }) },
    { icon: TRUST_ICONS[3], title: t("trust.support"), text: t("trust.supportText") },
  ];
  const trustShown = p?.trustTiles?.length ? p.trustTiles.map((x, i) => ({ icon: TRUST_ICONS[i] ?? "sparkles", title: x.title, text: x.text })) : trust;
  const nodes: Record<(typeof STORE_SECTIONS)[number], React.ReactNode> = {
    banners: <>
      {(home.offerBanners?.length ?? 0) > 0 && <section className="s-container pt-5"><OfferStrip banners={home.offerBanners!} currency={[...home.featured, ...home.newest][0]?.currency ?? "EGP"} /></section>}
      <Hero kind={look.hero} p={p} slides={slides} featured={[...home.featured, ...home.bestSellers, ...home.newest].filter((x, i, a) => a.findIndex((y) => y.id === x.id) === i)} cats={tops} offers={home.offers.length} t={t} locale={locale} />
</>,
    trust: <>
      <section className="s-container mt-6">
        <div className="s-trust grid grid-cols-2 gap-3 lg:grid-cols-4">
          {trustShown.map((x) => (
            <div key={x.title} className="s-trust-item flex items-center gap-3 rounded-2xl border border-[var(--s-line)] bg-[var(--s-card)] p-3.5 sm:p-4">
              <span className="s-trust-ico grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-[var(--s-soft)]" style={{ color: "var(--brand)" }}><Icon name={x.icon} className="h-5 w-5" /></span>
              <div className="min-w-0"><p className="truncate text-sm font-extrabold">{x.title}</p><p className="line-clamp-2 text-xs text-[var(--s-mute)]">{x.text}</p></div>
            </div>
          ))}
        </div>
      </section>
</>,
    categories: <>
      <Categories kind={look.cats} cats={tops} t={t} head={(body) => <Section title={t("shop.categories")} eyebrow={t("shop.shopBy")}>{body}</Section>} />
</>,
    featured: <>
      {home.featured.length > 0 && <Section title={t("shop.featured")} eyebrow={t("shop.handpicked")} href="/products" more={t("shop.viewAll")}><Grid items={home.featured} locale={locale} labels={labels} card={card} grid={grid} /></Section>}
</>,
    offers: <>
      {home.offers.length > 0 && <Section title={t("shop.offers")} eyebrow={t("shop.limitedTime")} href="/products?onSale=true&sort=discount" more={t("shop.viewAll")}><Grid items={home.offers} locale={locale} labels={labels} card={card} grid={grid} /></Section>}
</>,
    bestSellers: <>
      {home.bestSellers.length > 0 && <Section title={t("shop.bestSellers")} eyebrow={t("shop.loved")} href="/products?sort=popular" more={t("shop.viewAll")}><Grid items={home.bestSellers} locale={locale} labels={labels} card={card} grid={grid} /></Section>}
</>,
    newest: <>
      <Section title={t("shop.newest")} eyebrow={t("shop.justIn")} href="/products?sort=newest" more={t("shop.viewAll")}>
        {home.newest.length === 0 ? <p className="rounded-3xl border border-dashed border-[var(--s-line)] p-12 text-center text-[var(--s-mute)]">{t("shop.empty")}</p> : <Grid items={home.newest} locale={locale} labels={labels} card={card} grid={grid} />}
      </Section>
</>,
    whatsapp: <>
      {p?.whatsapp && (
        <section className="s-container mt-16 sm:mt-24">
          <Reveal className="s-cta relative overflow-hidden rounded-[28px] bg-[#15120f] p-8 text-white sm:p-12">
            <div className="pointer-events-none absolute -end-10 -top-10 h-56 w-56 rounded-full opacity-30 blur-2xl" style={{ background: "var(--brand)" }} />
            <div className="relative flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-center">
              <div><h2 className="text-2xl font-extrabold sm:text-3xl">{t("shop.helpTitle")}</h2><p className="mt-2 max-w-md text-[#c9c1b6]">{t("shop.helpText")}</p></div>
              <a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" className="s-btn !bg-emerald-500 !shadow-none"><Icon name="chat" className="h-4 w-4" />{t("shop.chatWithUs")}</a>
            </div>
          </Reveal>
        </section>
      )}
</>,
    branches: <>
      {branches.length > 0 && (
        <Section title={t("shop.visitUs")} eyebrow={t("shop.branches")} href="/branches" more={t("shop.viewAll")}>
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {branches.slice(0, 3).map((b) => (
              <div key={b.id} className="s-card space-y-2 p-5">
                <h3 className="text-lg font-extrabold">{b.name}</h3>
                <p className="text-sm text-[var(--s-mute)]">{[b.addressLine1, b.area, governorateName(b.governorateCode, locale) || b.city].filter(Boolean).join("، ")}</p>
                {b.phone && <a href={`tel:${b.phone}`} dir="ltr" className="block text-sm font-bold" style={{ color: "var(--brand)" }}>{b.phone}</a>}
                {b.workingHours && <details className="text-sm"><summary className="cursor-pointer font-semibold">{t("identity.hours")}</summary><HoursList hours={b.workingHours} t={t} className="mt-2 text-[var(--s-mute)]" /></details>}
              </div>
            ))}
          </div>
        </Section>
      )}
</>,
  };
  const sections = arrange(STORE_SECTIONS, p?.siteLayout, edit);
  const order = arrange(STORE_SECTIONS, p?.siteLayout, true).map((x) => x.key as string);
  const hiddenKeys = p?.siteLayout?.hidden ?? [];
  return (
    <>
      {edit ? (
        <EditProvider>
          {sections.map(({ key, hidden }) => <EditFrame key={key} kind="store" section={key} hidden={hidden} order={order} hiddenKeys={hiddenKeys}>{nodes[key]}</EditFrame>)}
          <EditBar kind="store" order={order} hiddenKeys={hiddenKeys} />
        </EditProvider>
      ) : sections.map(({ key }) => <div key={key} className="contents">{nodes[key]}</div>)}
    </>
  );
}
