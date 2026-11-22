import { PreviewBar } from "@/components/shop/templates/preview-bar";
import { PreviewLinks } from "@/components/shop/templates/preview-links";
import { VisitTracker } from "@/components/visit-tracker";
import { PoweredBy } from "@/components/brand";
import type { Ctx } from "./kit";

/** The non-visual parts every template shell needs: the "you are previewing" bar, in-frame link rewriting and the visit counter. */
export function Chrome({ ctx }: { ctx: Ctx }) {
  const { t } = ctx;
  return (
    <>
      {ctx.previewing && !ctx.inFrame && <PreviewBar label={t("tpl.previewing", { name: t(`ctpl.${ctx.id}`) })} exit={t("tpl.exitPreview")} />}
      {ctx.inFrame && <PreviewLinks tpl={ctx.id} />}
      {!ctx.inFrame && <VisitTracker />}
    </>
  );
}

/** The "powered by" credit, hidden for white-label tenants (decided by the caller). */
export function Credit({ ctx }: { ctx: Ctx }) {
  if (ctx.whiteLabel) return null;
  return <PoweredBy label={ctx.t("brand.powered")} name={ctx.t("brand.name")} />;
}
