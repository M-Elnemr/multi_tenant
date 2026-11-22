import Link from "next/link";
import { Brand, Footer, HeaderTools, MobileMenu, navLinks } from "../shared/parts";
import { CnRoot } from "../shared/root";
import type { Ctx } from "../shared/kit";
import "./boutique.css";

/** Boutique: the name sits centred like a masthead with a quiet navigation line under it. */
export function BoutiqueShell({ c, children }: { c: Ctx; children: React.ReactNode }) {
  return (
    <CnRoot c={c}>
      <header className="cn-header">
        <div className="cn-wrap bq-mast">
          <div className="bq-side"><MobileMenu c={c} /><nav className="cn-nav" aria-label={c.name}>{navLinks(c).slice(0, 3).map((l) => <Link key={l.href} href={l.href}>{l.label}</Link>)}</nav></div>
          <Brand c={c} />
          <div className="bq-side bq-side-end"><nav className="cn-nav" aria-label={c.name}>{navLinks(c).slice(3).map((l) => <Link key={l.href} href={l.href}>{l.label}</Link>)}</nav><HeaderTools c={c} /></div>
        </div>
      </header>
      <main id="cn-main">{children}</main>
      <Footer c={c} />
    </CnRoot>
  );
}
