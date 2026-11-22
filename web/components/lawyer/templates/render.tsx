import { notFound, redirect } from "next/navigation";
import { buildLnCtx } from "./shared/ctx";
import { LN_TEMPLATES } from "./registry";

export type LnPageName = "about" | "team" | "areas" | "services" | "contact" | "faq" | "reviews" | "book" | "track";

/** Where a site on an old-style template goes instead of a separate page, so the new routes never break existing sites. */
const LEGACY: Record<LnPageName, string> = { about: "/#about", team: "/#about", areas: "/#areas", services: "/#services", contact: "/#contact", faq: "/", reviews: "/#reviews", book: "/book", track: "/booking" };

/**
 * Renders one page of an ln-* template. Returns null when the site is not on an ln-* template: `book` and `track` then fall back to the original
 * pages (the route file does that); every other route sends the visitor to the matching section of the original home page.
 */
export async function renderLnPage(name: LnPageName, arg?: { areaKey?: string }) {
  const ctx = await buildLnCtx();
  if (!ctx || !ctx.isLn) {
    if (name === "book" || name === "track") return null;
    redirect(LEGACY[name]);
  }
  const T = LN_TEMPLATES[ctx.id];
  if (arg?.areaKey !== undefined) {
    const index = ctx.p.practiceAreas.findIndex((a) => a.key === arg.areaKey);
    if (index < 0) notFound();
    return <T.AreaDetail ctx={ctx} area={ctx.p.practiceAreas[index]} index={index} />;
  }
  switch (name) {
    case "about": return <T.About ctx={ctx} />;
    case "team": return <T.Team ctx={ctx} />;
    case "areas": return <T.Areas ctx={ctx} />;
    case "services": return <T.Services ctx={ctx} />;
    case "contact": return <T.Contact ctx={ctx} />;
    case "faq": return <T.Faq ctx={ctx} />;
    case "reviews": return <T.Reviews ctx={ctx} />;
    case "book": return <T.Book ctx={ctx} />;
    case "track": return <T.Track ctx={ctx} />;
  }
}
