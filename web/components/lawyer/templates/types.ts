import type { ReactNode } from "react";
import type { LnArea, LnCtx } from "./shared/ctx";

/** What each of the five lawyer templates provides. Every one composes its own pages; only data, booking logic and tokens are shared. */
export type LnPageProps = { ctx: LnCtx };
export type LnTemplate = {
  Shell: (p: { ctx: LnCtx; children: ReactNode }) => ReactNode;
  Home: (p: LnPageProps) => ReactNode;
  About: (p: LnPageProps) => ReactNode;
  Team: (p: LnPageProps) => ReactNode;
  Areas: (p: LnPageProps) => ReactNode;
  AreaDetail: (p: LnPageProps & { area: LnArea; index: number }) => ReactNode;
  Services: (p: LnPageProps) => ReactNode;
  Contact: (p: LnPageProps) => ReactNode;
  Faq: (p: LnPageProps) => ReactNode;
  Reviews: (p: LnPageProps) => ReactNode;
  Book: (p: LnPageProps) => ReactNode;
  Track: (p: LnPageProps) => ReactNode;
};
