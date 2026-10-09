import Link from "next/link";
import { BannerCarousel } from "@/components/shop/hero";
import { HoursList } from "@/components/shop/hours";
import { ShopProductCard, type ShopProduct } from "@/components/shop/product-card";
import { buildTree, type ShopBranch, type ShopCategory, type ShopProfile } from "@/components/shop/types";
import { backendJson } from "@/lib/backend";
import { governorateName } from "@/lib/governorates";
import { fileUrl } from "@/lib/media";
import { whatsappLink } from "@/lib/whatsapp";
import { getT } from "@/lib/i18n-server";

type Home = { featured: ShopProduct[]; newest: ShopProduct[]; offers: ShopProduct[]; bestSellers: ShopProduct[]; banners: { id: string; imageFileId: string; title: string; subtitle: string; linkUrl: string }[] };

function Section({ title, eyebrow, href, more, children }: { title: string; eyebrow?: string; href?: string; more?: string; children: React.ReactNode }) {
  return (
    <section className="s-container mt-14 sm:mt-20">
      <div className="mb-6 flex items-end justify-between gap-4">
        <div>{eyebrow && <p className="s-eyebrow mb-1">{eyebrow}</p>}<h2 className="s-section-title">{title}</h2></div>
        {href && more && <Link href={href} className="shrink-0 text-sm font-extrabold" style={{ color: "var(--brand)" }}>{more} →</Link>}
      </div>
      {children}
    </section>
  );
}

function Grid({ items, locale, labels }: { items: ShopProduct[]; locale: "ar" | "en"; labels: React.ComponentProps<typeof ShopProductCard>["labels"] }) {
  // show complete rows only (4 across on desktop, 2 on phones) so a lone product never sits by itself on the last row
  const shown = items.length >= 4 ? items.slice(0, Math.min(items.length, 8) - (Math.min(items.length, 8) % 4)) : items;
  return <div className="stagger grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-3 lg:grid-cols-4">{shown.map((p, i) => <div key={p.id} style={{ "--i": i } as React.CSSProperties}><ShopProductCard p={p} locale={locale} labels={labels} /></div>)}</div>;
}

export default async function StoreHome() {
  const { t, locale } = await getT();
  const [profileRes, cats, home, branches] = await Promise.all([
    backendJson<{ profile: ShopProfile }>("/shop/profile").catch(() => null),
    backendJson<ShopCategory[]>("/shop/categories").catch(() => [] as ShopCategory[]),
    backendJson<Home>("/shop/home").catch(() => ({ featured: [], newest: [], offers: [], bestSellers: [], banners: [] }) as Home),
    backendJson<ShopBranch[]>("/shop/branches").catch(() => [] as ShopBranch[]),
  ]);
  const p = profileRes?.profile;
  const tops = buildTree(cats).filter((c) => c.productCount > 0).slice(0, 12);
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  const slides = home.banners.map((b) => ({ id: b.id, imageUrl: fileUrl(b.imageFileId, "original"), title: b.title, subtitle: b.subtitle, href: b.linkUrl || undefined }));
  const days = p?.returnWindowDays ?? 14;
  const trust = [
    { icon: "💵", title: t("trust.cod"), text: t("trust.codText") },
    { icon: "🚚", title: t("trust.delivery"), text: t("trust.deliveryText") },
    { icon: "↩️", title: t("trust.returns"), text: t("trust.returnsText", { n: days }) },
    { icon: "💬", title: t("trust.support"), text: t("trust.supportText") },
  ];
  return (
    <>
      <section className="s-container pt-5 sm:pt-8">
        {slides.length > 0 ? <BannerCarousel slides={slides} cta={t("shop.browse")} /> : (
          <div className="s-hero-shine relative overflow-hidden rounded-[28px] shadow-[var(--s-shadow-lg)]">
            {p?.coverFileId ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={fileUrl(p.coverFileId, "original")} alt="" className="absolute inset-0 h-full w-full object-cover" />
            ) : <div className="bg-brand-gradient absolute inset-0" />}
            <div className="absolute inset-0 s-overlay-side" />
            <div className="relative flex min-h-[22rem] flex-col justify-end gap-4 p-7 text-white sm:min-h-[27rem] sm:justify-center sm:p-14">
              <p className="s-eyebrow !text-white/80">{t("shop.welcomeTo")}</p>
              <h1 className="max-w-2xl text-4xl font-extrabold leading-[1.1] drop-shadow sm:text-6xl">{p?.storeName}</h1>
              {(p?.shortDescription || p?.about) && <p className="max-w-xl text-base opacity-95 sm:text-xl">{p.shortDescription || p.about}</p>}
              <div className="mt-2 flex flex-wrap gap-3">
                <Link href="/products" className="s-btn !bg-white !text-[var(--s-ink)]">{t("shop.browse")}</Link>
                {home.offers.length > 0 && <Link href="/products?onSale=true" className="s-btn s-btn-ghost !bg-white/15 !text-white !border-white/40 backdrop-blur">🔥 {t("shop.offers")}</Link>}
              </div>
            </div>
          </div>
        )}
      </section>

      <section className="s-container mt-6">
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          {trust.map((x) => (
            <div key={x.title} className="flex items-center gap-3 rounded-2xl border border-[var(--s-line)] bg-white p-3.5 sm:p-4">
              <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-[var(--s-soft)] text-xl">{x.icon}</span>
              <div className="min-w-0"><p className="truncate text-sm font-extrabold">{x.title}</p><p className="line-clamp-2 text-xs text-[var(--s-mute)]">{x.text}</p></div>
            </div>
          ))}
        </div>
      </section>

      {tops.length > 0 && (
        <Section title={t("shop.categories")} eyebrow={t("shop.shopBy")}>
          <div className="s-scroll-x">
            {tops.map((c) => (
              <Link key={c.id} href={`/c/${c.slug}`} className="s-lift group w-36 sm:w-44">
                <div className="relative aspect-square overflow-hidden rounded-[26px] border border-[var(--s-line)] bg-[var(--s-soft)]">
                  {c.imageFileId ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={fileUrl(c.imageFileId, "medium")} alt="" className="h-full w-full object-cover transition duration-700 group-hover:scale-110" loading="lazy" />
                  ) : <div className="grid h-full place-items-center text-5xl font-extrabold opacity-20" style={{ color: "var(--brand)" }}>{c.name.charAt(0)}</div>}
                  <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/60 to-transparent p-3 pt-8" />
                </div>
                <p className="mt-2.5 text-center text-sm font-extrabold">{c.name}</p>
                <p className="text-center text-xs text-[var(--s-mute)]">{t("shop.itemsCount", { n: c.productCount })}</p>
              </Link>
            ))}
          </div>
        </Section>
      )}

      {home.featured.length > 0 && <Section title={t("shop.featured")} eyebrow={t("shop.handpicked")} href="/products" more={t("shop.viewAll")}><Grid items={home.featured} locale={locale} labels={labels} /></Section>}
      {home.offers.length > 0 && <Section title={t("shop.offers")} eyebrow={t("shop.limitedTime")} href="/products?onSale=true&sort=discount" more={t("shop.viewAll")}><Grid items={home.offers} locale={locale} labels={labels} /></Section>}
      {home.bestSellers.length > 0 && <Section title={t("shop.bestSellers")} eyebrow={t("shop.loved")} href="/products?sort=popular" more={t("shop.viewAll")}><Grid items={home.bestSellers} locale={locale} labels={labels} /></Section>}
      <Section title={t("shop.newest")} eyebrow={t("shop.justIn")} href="/products?sort=newest" more={t("shop.viewAll")}>
        {home.newest.length === 0 ? <p className="rounded-3xl border border-dashed border-[var(--s-line)] p-12 text-center text-[var(--s-mute)]">{t("shop.empty")}</p> : <Grid items={home.newest} locale={locale} labels={labels} />}
      </Section>

      {p?.whatsapp && (
        <section className="s-container mt-16 sm:mt-24">
          <div className="relative overflow-hidden rounded-[28px] bg-[#15120f] p-8 text-white sm:p-12">
            <div className="pointer-events-none absolute -end-10 -top-10 h-56 w-56 rounded-full opacity-30 blur-2xl" style={{ background: "var(--brand)" }} />
            <div className="relative flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-center">
              <div><h2 className="text-2xl font-extrabold sm:text-3xl">{t("shop.helpTitle")}</h2><p className="mt-2 max-w-md text-[#c9c1b6]">{t("shop.helpText")}</p></div>
              <a href={whatsappLink(p.whatsapp, `${p.storeName} 👋`)} target="_blank" rel="noopener noreferrer" className="s-btn !bg-emerald-500 !shadow-none">💬 {t("shop.chatWithUs")}</a>
            </div>
          </div>
        </section>
      )}

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
    </>
  );
}
