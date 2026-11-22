import Link from "next/link";
import { Icon } from "@/components/icons";
import { Brand, Footer, HeaderTools, MobileMenu, StatusPill, navLinks } from "../shared/parts";
import { CnRoot } from "../shared/root";
import type { Ctx } from "../shared/kit";
import "./institute.css";

/** Institute: a slim information bar above a formal header; the footer closes on a navy block. */
export function InstituteShell({ c, children }: { c: Ctx; children: React.ReactNode }) {
  return (
    <CnRoot c={c}>
      <div className="cn-util">
        <div className="cn-wrap">
          <StatusPill c={c} />
          <span className="cn-util-end">
            {c.p.addressText && <span className="cn-util-addr"><Icon name="pin" className="cn-ic" />{c.p.addressText}</span>}
            {c.phone && <a href={`tel:${c.phone}`} dir="ltr"><Icon name="phone" className="cn-ic" />{c.phone}</a>}
          </span>
        </div>
      </div>
      <header className="cn-header">
        <div className="cn-wrap cn-header-row">
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
