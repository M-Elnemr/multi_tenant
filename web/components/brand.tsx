import Link from "next/link";

/* eslint-disable @next/next/no-img-element */
export function LogoMark({ className = "h-9 w-9" }: { className?: string }) {
  return <img src="/brand/logo.png" alt="" className={`${className} shrink-0 object-contain`} />;
}

/** The Elmanassa platform logo with its name. */
export function Logo({ name, className = "h-9 w-9", href = "/", textClass = "text-lg font-bold" }: { name: string; className?: string; href?: string | null; textClass?: string }) {
  const body = (
    <>
      <LogoMark className={className} />
      <span className={`${textClass} text-gradient`}>{name}</span>
    </>
  );
  return href ? <Link href={href} className="inline-flex items-center gap-2.5">{body}</Link> : <span className="inline-flex items-center gap-2.5">{body}</span>;
}

/** Small "Powered by" mark shown on tenant sites. */
export function PoweredBy({ label, name }: { label: string; name: string }) {
  return (
    <a href="https://elmanassa.shop" target="_blank" rel="noopener" className="inline-flex items-center gap-1.5 text-xs text-slate-400 transition hover:text-slate-600">
      <LogoMark className="h-4 w-4" />
      <span>{label} <b className="font-semibold">{name}</b></span>
    </a>
  );
}
