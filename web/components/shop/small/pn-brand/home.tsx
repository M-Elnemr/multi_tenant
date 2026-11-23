import Link from "next/link";
import { PnEmpty, PnPhoto, PnPrice, cardPic } from "../pn-kit/primitives";
import type { PnHomeProps } from "../pn-registry";

/** Minimal brand store: words first. The shop's own statement in large type, then the products as a quiet typographic list with a small photo; no cards, no banners. */
export async function BrandHome({ p, products, t, locale }: PnHomeProps) {
  const name = p?.storeName ?? "";
  const shown = products.slice(0, 3);
  if (!shown.length) return <div data-small-home data-pn="brand"><PnEmpty name={name} t={t} /></div>;
  const statement = p?.shortDescription || p?.about || "";
  return (
    <div data-small-home data-pn="brand">
      <section className="pn-wrap pnb-statement"><h1>{name}</h1>{statement && <p>{statement}</p>}</section>
      <section className="pn-wrap pnb-list">
        <ol>{shown.map((x, i) => (
          <li key={x.id}><Link href={`/products/${x.slug}`}>
            <span className="pnb-no tnum">{String(i + 1).padStart(2, "0")}</span>
            <span className="pnb-name">{x.name}</span>
            <PnPrice x={x} locale={locale} />
            <span className="pnb-thumb"><PnPhoto src={cardPic(x, "medium")} alt="" /></span>
          </Link></li>
        ))}</ol>
      </section>
      {p?.about && p.about !== statement && <section className="pn-wrap pnb-about"><p>{p.about}</p></section>}
    </div>
  );
}
