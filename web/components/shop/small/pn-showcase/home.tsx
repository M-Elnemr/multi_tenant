import { Reveal } from "@/components/shop/templates/reveal";
import { PnButton, PnEmpty, PnPhoto, PnPrice, PnStars, cardPic, firstParas, loadDetail, picOf } from "../pn-kit/primitives";
import type { PnHomeProps } from "../pn-registry";

/** Luxury showcase: a dark gallery. Each product (up to three) is its own full-height exhibit, numbered, alternating sides; the text under each is that product's own description. */
export async function ShowcaseHome({ p, products, t, locale }: PnHomeProps) {
  const name = p?.storeName ?? "";
  const shown = products.slice(0, 3);
  if (!shown.length) return <div data-small-home data-pn="showcase"><PnEmpty name={name} t={t} /></div>;
  const details = await Promise.all(shown.map((x) => loadDetail(x.slug, locale)));
  return (
    <div data-small-home data-pn="showcase">
      <section className="pn-wrap pns-intro"><p className="pn-eyebrow">{name}</p><h1>{shown.length === 1 ? shown[0].name : t("pn.show.collection")}</h1>{(p?.shortDescription || p?.about) && <p>{p.shortDescription || p.about}</p>}</section>
      {shown.map((x, i) => {
        const d = details[i];
        const para = firstParas(d?.description, 1)[0] ?? "";
        return (
          <section key={x.id} className="pn-wrap pns-exhibit" data-flip={i % 2 ? "" : undefined}>
            <Reveal className="pns-plate"><PnPhoto src={picOf(d?.media[0]) ?? cardPic(x)} alt={x.name} priority={i === 0} /></Reveal>
            <Reveal className="pns-caption">
              <span className="pns-no tnum">{String(i + 1).padStart(2, "0")}</span>
              <h2>{x.name}</h2>
              {para && <p>{para}</p>}
              {d && <PnStars value={d.reviews.average} count={d.reviews.count} label={t("shop.reviews")} />}
              <div className="pns-buy"><PnPrice x={x} locale={locale} className="pns-price" /><PnButton href={`/products/${x.slug}`} ghost>{t("sm.view")}</PnButton></div>
            </Reveal>
          </section>
        );
      })}
    </div>
  );
}
