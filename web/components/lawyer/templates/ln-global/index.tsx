import Link from "next/link";
import type { LnTemplate } from "../types";
import type { LnCtx } from "../shared/ctx";
import { LgBookClient, LgTrackClient } from "./booking-ui";
import { LgAbout, LgAreaDetail, LgAreas, LgContact, LgFaq, LgHome, LgReviews, LgServices, LgTeam } from "./pages";
import { LgShell } from "./shell";

function Head({ ctx, title, sub }: { ctx: LnCtx; title: string; sub?: string }) {
  return <section className="lg-ph"><div className="lg-wrap"><nav className="lg-crumb lg-up"><Link href="/">{ctx.t("lnx.home")}</Link><span aria-hidden="true">/</span><span>{title}</span></nav><h1>{title}</h1>{sub && <p>{sub}</p>}</div></section>;
}

export const lnGlobal: LnTemplate = {
  Shell: LgShell, Home: LgHome, About: LgAbout, Team: LgTeam, Areas: LgAreas, AreaDetail: LgAreaDetail, Services: LgServices, Contact: LgContact, Faq: LgFaq, Reviews: LgReviews,
  Book: ({ ctx }) => (
    <>
      <Head ctx={ctx} title={ctx.t("lws.bookTitle", { name: ctx.name })} sub={ctx.t("lws.bookSub")} />
      <div className="lg-wrap"><LgBookClient services={ctx.p.services} phone={ctx.p.phone} open={ctx.book} /></div>
    </>
  ),
  Track: ({ ctx }) => (<><Head ctx={ctx} title={ctx.t("lws.trackTitle")} /><LgTrackClient /></>),
};
