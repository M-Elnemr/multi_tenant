import Link from "next/link";
import { Brand, Footer, HeaderTools, MobileMenu, navLinks } from "../shared/parts";
import { CnRoot } from "../shared/root";
import type { Ctx } from "../shared/kit";
import "./family.css";

/** Family: a floating, rounded header bar with big friendly targets. */
export function FamilyShell({ c, children }: { c: Ctx; children: React.ReactNode }) {
  return (
    <CnRoot c={c}>
      <header className="cn-header fm-head">
        <div className="cn-wrap fm-bar">
          <Brand c={c} />
          <nav className="cn-nav" aria-label={c.name}>{navLinks(c).map((l) => <Link key={l.href} href={l.href}>{l.label}</Link>)}</nav>
          <HeaderTools c={c} />
          <MobileMenu c={c} />
        </div>
      </header>
      <main id="cn-main">{children}</main>
      <Footer c={c} />
    </CnRoot>
  );
}
