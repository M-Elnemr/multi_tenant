import Link from "next/link";
import { LogoMark } from "@/components/brand";
import { Icon } from "@/components/icons";

/** Questions as native disclosure widgets (keyboard and screen-reader friendly, no script); the answer slides open in CSS. */
export function Faq({ items, className = "" }: { items: { q: string; a: string }[]; className?: string }) {
  return (
    <div className={`lp-faq divide-y ${className}`}>
      {items.map((f, i) => (
        <details key={i} className="group py-1" name="lp-faq">
          <summary className="flex cursor-pointer list-none items-center justify-between gap-6 py-5 text-start text-base font-semibold sm:text-lg">
            {f.q}
            <span className="lp-faq-icon grid h-8 w-8 shrink-0 place-items-center rounded-full border transition-transform duration-300 group-open:rotate-45"><Icon name="plus" className="h-4 w-4" /></span>
          </summary>
          <div className="lp-faq-body"><p className="pb-6 pe-12 leading-relaxed opacity-75">{f.a}</p></div>
        </details>
      ))}
    </div>
  );
}

/** Section heading: small eyebrow, big display title, optional lead. */
export function Head({ eyebrow, title, sub, center = false, className = "" }: { eyebrow?: string; title: string; sub?: string; center?: boolean; className?: string }) {
  return (
    <div className={`${center ? "mx-auto text-center" : ""} max-w-3xl ${className}`}>
      {eyebrow && <p className="lp-eyebrow mb-4">{eyebrow}</p>}
      <h2 className="lp-h2">{title}</h2>
      {sub && <p className={`lp-lead mt-5 ${center ? "mx-auto" : ""}`}>{sub}</p>}
    </div>
  );
}

export function LandingFooter({ brand, tag, cols, copyright }: { brand: string; tag: string; cols: { title: string; links: { href: string; label: string }[] }[]; copyright: string }) {
  return (
    <footer className="lp-footer">
      <div className="mx-auto grid max-w-7xl gap-10 px-4 py-14 sm:px-6 md:grid-cols-[1.4fr_repeat(2,1fr)]">
        <div>
          <Link href="/" className="inline-flex items-center gap-2.5"><LogoMark className="h-9 w-9" /><span className="font-display text-lg font-bold">{brand}</span></Link>
          <p className="mt-4 max-w-xs text-sm leading-relaxed opacity-65">{tag}</p>
        </div>
        {cols.map((c) => (
          <nav key={c.title} aria-label={c.title}>
            <p className="mb-4 text-xs font-bold uppercase tracking-wider opacity-50">{c.title}</p>
            <ul className="space-y-2.5 text-sm">{c.links.map((l) => <li key={l.href}><Link href={l.href} className="opacity-75 transition hover:opacity-100">{l.label}</Link></li>)}</ul>
          </nav>
        ))}
      </div>
      <div className="border-t border-current/10"><p className="mx-auto max-w-7xl px-4 py-6 text-xs opacity-50 sm:px-6">© {new Date().getFullYear()} {copyright}</p></div>
    </footer>
  );
}
