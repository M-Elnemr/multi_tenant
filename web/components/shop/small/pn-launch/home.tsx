import { Reveal } from "@/components/shop/templates/reveal";
import { PnButton, PnCard, PnEmpty, PnPhoto, PnPrice, cardPic, firstParas, loadDetail, picOf } from "../pn-kit/primitives";
import type { PnHomeProps } from "../pn-registry";
import { Countdown } from "./countdown";

/** Launch / campaign: a loud poster with the product, the real offer (percent and end date, only when an offer exists), three reasons from the product's own text, and a sticky buy bar on phones. */
export async function LaunchHome({ p, products, t, locale }: PnHomeProps) {
  const name = p?.storeName ?? "";
  const hero = products[0];
  if (!hero) return <div data-small-home data-pn="launch"><PnEmpty name={name} t={t} /></div>;
  const d = await loadDetail(hero.slug, locale);
  const href = `/products/${hero.slug}`;
  const offerEnd = hero.offer?.endsAt ?? d?.offer?.endsAt;
  const pct = hero.offer?.percent ?? (hero.discountPct ? Math.round(Number(hero.discountPct)) : 0);
  const points = [...firstParas(d?.description, 4).slice(0, 3)];
  const rest = products.slice(1, 3);
  return (
    <div data-small-home data-pn="launch">
      <section className="pnl-poster">
        <div className="pn-wrap pnl-grid">
          <div className="pnl-copy">
            <p className="pnl-kicker">{hero.offer?.badge || t("sm.new")}</p>
            <h1>{hero.name}</h1>
            {d?.shortDescription && <p className="pnl-lede">{d.shortDescription}</p>}
            {offerEnd && <Countdown endsAt={offerEnd} labels={{ d: t("pn.launch.d"), h: t("pn.launch.h"), m: t("pn.launch.m"), s: t("pn.launch.s") }} />}
            <div className="pnl-buy"><PnPrice x={hero} locale={locale} className="pnl-price" /><PnButton href={href}>{t("pn.flag.buy")}</PnButton></div>
          </div>
          <div className="pnl-art"><PnPhoto src={picOf(d?.media[0]) ?? cardPic(hero)} alt={hero.name} priority />{pct > 0 && <span className="pnl-pct tnum">-{pct}%</span>}</div>
        </div>
      </section>
      {points.length > 0 && (
        <section className="pn-wrap pnl-points"><Reveal><ol>{points.map((s, i) => <li key={i}><span className="tnum">{i + 1}</span><p>{s}</p></li>)}</ol></Reveal></section>
      )}
      {rest.length > 0 && <section className="pn-wrap pnl-rest"><Reveal><h2>{t("sm.more")}</h2><div data-n={rest.length}>{rest.map((x) => <PnCard key={x.id} x={x} locale={locale} t={t} />)}</div></Reveal></section>}
      <div className="pnl-sticky"><PnPrice x={hero} locale={locale} /><PnButton href={href}>{t("pn.flag.buy")}</PnButton></div>
    </div>
  );
}
