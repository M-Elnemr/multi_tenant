import Link from "next/link";
import { Icon } from "@/components/icons";
import { Reveal } from "@/components/shop/templates/reveal";
import { PnEmpty, PnPhoto, PnPrice, cardPic } from "../pn-kit/primitives";
import type { PnHomeProps } from "../pn-registry";

/** Three-product boutique: the grid is composed for the number of products, so one, two or three always look finished (1: a wide feature, 2: a pair, 3: one large and two stacked). */
export async function TrioHome({ p, products, t, locale }: PnHomeProps) {
  const name = p?.storeName ?? "";
  const shown = products.slice(0, 3);
  if (!shown.length) return <div data-small-home data-pn="trio"><PnEmpty name={name} t={t} /></div>;
  return (
    <div data-small-home data-pn="trio">
      <section className="pn-wrap pnt-head"><p className="pn-eyebrow">{t("sm.more")}</p><h1>{name}</h1>{(p?.shortDescription || p?.about) && <p>{p.shortDescription || p.about}</p>}</section>
      <section className="pn-wrap pnt-board" data-n={shown.length}>
        {shown.map((x, i) => (
          <Reveal key={x.id} className={`pnt-tile pnt-t${i}`}>
            <Link href={`/products/${x.slug}`}>
              <PnPhoto src={cardPic(x)} alt={x.name} priority={i === 0} />
              {x.offer && <span className="pnt-flag">{x.offer.badge || t("shop.off")}</span>}
              <span className="pnt-meta"><h2>{x.name}</h2><PnPrice x={x} locale={locale} /></span>
            </Link>
          </Reveal>
        ))}
      </section>
      <section className="pn-wrap pnt-promise">{[t("trust.cod"), t("trust.delivery"), t("trust.support")].map((x) => <span key={x}><Icon name="check" className="h-4 w-4" />{x}</span>)}</section>
    </div>
  );
}
