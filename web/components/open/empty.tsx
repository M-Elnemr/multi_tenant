import Link from "next/link";
import { Icon } from "../icons";

/** One calm empty / error state for the whole Open Store: an outlined icon on a paper disc, a serif title, one line of help, one action. */
export function OEmpty({ icon, title, body, href, cta, tone = "default" }: { icon: string; title: string; body?: string; href?: string; cta?: string; tone?: "default" | "error" }) {
  return (
    <div className="o-card mx-auto max-w-xl px-6 py-14 text-center" role={tone === "error" ? "alert" : undefined}>
      <span className="mx-auto grid h-16 w-16 place-items-center rounded-full border border-[var(--o-line)] bg-[var(--o-soft)] text-[var(--o-accent)]"><Icon name={icon} className="h-7 w-7" /></span>
      <h2 className="o-h1 mt-5 !text-2xl">{title}</h2>
      {body && <p className="o-mute mx-auto mt-2 max-w-sm text-sm leading-relaxed">{body}</p>}
      {href && cta && <Link href={href} className="o-btn mt-6">{cta}</Link>}
    </div>
  );
}
