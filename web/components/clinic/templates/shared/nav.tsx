"use client";

import Link from "next/link";
import { useState } from "react";
import { useOverlay } from "./ui";

/** Menu button + full-screen link list for small screens. Links come from the server (labels already translated). */
export function NavToggle({ links, label, closeLabel, children }: { links: { href: string; label: string }[]; label: string; closeLabel: string; children?: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);
  useOverlay(open, close);
  return (
    <>
      <button type="button" className="cn-burger" aria-expanded={open} aria-controls="cn-menu" aria-label={open ? closeLabel : label} onClick={() => setOpen(!open)}>
        <span /><span /><span />
      </button>
      {open && (
        <div id="cn-menu" className="cn-menu" role="dialog" aria-modal="true" aria-label={label}>
          <nav>{links.map((l) => <Link key={l.href} href={l.href} onClick={close}>{l.label}</Link>)}</nav>
          <div className="cn-menu-extra" onClick={close}>{children}</div>
        </div>
      )}
    </>
  );
}
