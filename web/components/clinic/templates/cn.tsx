import { Suspense } from "react";
import { redirect } from "next/navigation";
import { BookingFlow } from "./shared/booking";
import { cnCtx } from "./shared/ctx";
import type { CnId } from "./shared/ids";
import type { Ctx } from "./shared/kit";
import { PageHead, PrimaryAction, SecondaryAction } from "./shared/parts";
import { InstituteHome } from "./cn-institute/home";
import { InstituteShell } from "./cn-institute/shell";

type Shell = (p: { c: Ctx; children: React.ReactNode }) => React.ReactNode;
type Home = (p: { c: Ctx }) => React.ReactNode;

/** Each template's own shell (header, footer, chrome) and home composition. Inner pages and booking are shared and skinned by CSS. */
export const CN_SHELL: Record<CnId, Shell> = {
  "cn-institute": InstituteShell, "cn-boutique": InstituteShell, "cn-medtech": InstituteShell, "cn-family": InstituteShell, "cn-atelier": InstituteShell,
};
export const CN_HOME: Record<CnId, Home> = {
  "cn-institute": InstituteHome, "cn-boutique": InstituteHome, "cn-medtech": InstituteHome, "cn-family": InstituteHome, "cn-atelier": InstituteHome,
};

/** For the inner routes: the cn context, or (site on one of the original templates, which have no inner pages) back to the matching home section. */
export async function cnPage(anchor: string): Promise<Ctx> {
  const c = await cnCtx();
  if (!c) redirect(`/#${anchor}`);
  return c;
}

export function BookPage({ c }: { c: Ctx }) {
  const { t, p } = c;
  const head = <PageHead c={c} eyebrow={t("clinic.book")} title={t("cn.book.title")} lead={c.book ? t("cn.book.lead") : undefined} crumbs={[{ label: t("clinic.book") }]} />;
  if (!c.book || p.doctors.length === 0 || p.services.length === 0 || p.branches.length === 0) {
    return (
      <>
        {head}
        <section className="cn-section"><div className="cn-wrap"><div className="cn-card cn-disabled">
          <h2 className="cn-h3">{t("cn.book.offTitle")}</h2>
          <p className="cn-muted">{t("book.disabled")}</p>
          <div className="cn-cta-row"><PrimaryAction c={{ ...c, book: undefined }} /><SecondaryAction c={{ ...c, book: undefined }} /></div>
        </div></div></section>
      </>
    );
  }
  return (
    <>
      {head}
      <section className="cn-section"><div className="cn-wrap"><Suspense fallback={<p className="cn-muted">{t("cn.book.loadingTimes")}</p>}><BookingFlow p={p} dict={c.dict} locale={c.locale} phone={c.phone} /></Suspense></div></section>
    </>
  );
}
