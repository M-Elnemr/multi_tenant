import Link from "next/link";
import { Brand, Footer, HeaderTools, MobileMenu, StatusPill, navLinks } from "../shared/parts";
import { CnRoot } from "../shared/root";
import type { Ctx } from "../shared/kit";
import "./medtech.css";

/** Medtech: a compact, precise header with a pill navigation and the live open/closed state always in view. */
export function MedtechShell({ c, children }: { c: Ctx; children: React.ReactNode }) {
  return (
    <CnRoot c={c}>
      <header className="cn-header">
        <div className="cn-wrap mt-row">
          <Brand c={c} />
          <nav className="cn-nav mt-pills" aria-label={c.name}>{navLinks(c).map((l) => <Link key={l.href} href={l.href}>{l.label}</Link>)}</nav>
          <span className="mt-status cn-hide-sm"><StatusPill c={c} /></span>
          <HeaderTools c={c} />
          <MobileMenu c={c} />
        </div>
      </header>
      <main id="cn-main">{children}</main>
      <Footer c={c} />
    </CnRoot>
  );
}
