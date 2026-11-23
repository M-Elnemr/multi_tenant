import Link from "next/link";
import { Brand, Footer, LangSwitch, MobileMenu, PrimaryAction, navLinks } from "../shared/parts";
import { AuthLink } from "../shared/ui";
import { CnRoot } from "../shared/root";
import type { Ctx } from "../shared/kit";
import "./atelier.css";

/** Atelier: a transparent header laid over the first image, the brand left, one quiet action right. */
export function AtelierShell({ c, children }: { c: Ctx; children: React.ReactNode }) {
  return (
    <CnRoot c={c}>
      <header className="cn-header at-head">
        <div className="cn-wrap at-bar">
          <Brand c={c} />
          <nav className="cn-nav" aria-label={c.name}>{navLinks(c).map((l) => <Link key={l.href} href={l.href}>{l.label}</Link>)}</nav>
          <div className="cn-tools"><LangSwitch c={c} /><AuthLink className="cn-signin" portal={c.portal} /><PrimaryAction c={c} ghost className="cn-btn-sm cn-hide-sm" /></div>
          <MobileMenu c={c} />
        </div>
      </header>
      <main id="cn-main">{children}</main>
      <Footer c={c} />
    </CnRoot>
  );
}
