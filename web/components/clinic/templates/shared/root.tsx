import { templateFontVars } from "@/components/shop/templates/fonts";
import { Chrome } from "./chrome";
import { Dock } from "./parts";
import type { Ctx } from "./kit";
import "./base.css";

/** The wrapper every cn-* shell renders: template class + font variables, the preview chrome and the phone action bar. */
export function CnRoot({ c, children }: { c: Ctx; children: React.ReactNode }) {
  return (
    <div className={`cn ${c.id} ${templateFontVars}`} data-cn={c.id}>
      <Chrome ctx={c} />
      {children}
      <Dock c={c} />
    </div>
  );
}
