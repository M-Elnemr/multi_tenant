import Link from "next/link";
import { whatsappLink } from "@/lib/whatsapp";
import type { LawyerHero } from "@/lib/lawyer-templates";
import type { LawyerSite } from "./types";

type T = (k: string, v?: Record<string, string | number>) => string;
type Props = { kind: Exclude<LawyerHero, "split">; p: LawyerSite; name: string; stats: { value: string; label: string }[]; book: boolean; t: T };

function Ctas({ p, book, t }: { p: LawyerSite; book: boolean; t: T }) {
  return (
    <div className="mt-8 flex flex-wrap gap-3">
      {book && <Link href="/book" className="lw-btn">{t("lws.bookCta")} →</Link>}
      {p.whatsapp && <a href={whatsappLink(p.whatsapp, t("lws.waHello"))} target="_blank" rel="noopener noreferrer" className="lw-btn lw-btn-ghost">{t("lws.chat")}</a>}
      {!book && p.phone && <a href={`tel:${p.phone}`} className="lw-btn">{t("lws.call")}</a>}
    </div>
  );
}

const Stats = ({ stats }: { stats: Props["stats"] }) => stats.length === 0 ? null : (
  <dl className="grid grid-cols-3 gap-6">{stats.slice(0, 3).map((s, i) => <div key={i}><dt className="lw-stat">{s.value}</dt><dd className="mt-2 text-sm opacity-75">{s.label}</dd></div>)}</dl>
);

/* eslint-disable @next/next/no-img-element */
/** The three hero compositions other than the original split: they share the data of the office and differ in how the page opens. */
export function LawyerHeroVariant({ kind, p, name, stats, book, t }: Props) {
  if (kind === "center") return (
    <section className="lw-hero lw-hero-grain"><div className="lw-container relative py-20 text-center md:py-28">
      <span className="lw-emblem mx-auto grid h-24 w-24 place-items-center rounded-full text-4xl" aria-hidden>⚖</span>
      {p.barLevel && <p className="lw-eyebrow mx-auto mt-6 w-fit">{p.barLevel}</p>}
      <h1 className="lw-display mx-auto mt-4 max-w-3xl text-[clamp(2.4rem,6vw,4.6rem)] font-bold leading-[1.08]">{p.title && <span className="block text-[.55em] font-semibold opacity-80">{p.title}</span>}{p.displayName}</h1>
      {p.headline && <p className="mx-auto mt-5 max-w-xl text-lg leading-relaxed opacity-85">{p.headline}</p>}
      <div className="flex justify-center"><Ctas p={p} book={book} t={t} /></div>
      {stats.length > 0 && <div className="mx-auto mt-12 max-w-xl border-t pt-8" style={{ borderColor: "color-mix(in srgb, var(--lw-hero-ink) 18%, transparent)" }}><Stats stats={stats} /></div>}
    </div></section>
  );
  if (kind === "editorial") return (
    <section className="lw-hero lw-hero-grain"><div className="lw-container relative grid gap-10 py-16 md:py-24 lg:grid-cols-[1.4fr_auto_1fr]">
      <div><p className="lw-eyebrow">{p.barLevel || t("lws.years")}</p>
        <h1 className="lw-display mt-5 text-[clamp(2.8rem,8vw,6rem)] font-bold leading-[1.02]">{p.title && <span className="block text-[.4em] font-semibold opacity-75">{p.title}</span>}{p.displayName}</h1>
        {p.headline && <p className="mt-6 max-w-xl text-lg leading-relaxed opacity-85">{p.headline}</p>}<Ctas p={p} book={book} t={t} /></div>
      <div className="hidden w-px self-stretch lg:block" style={{ background: "color-mix(in srgb, var(--lw-accent) 60%, transparent)" }} aria-hidden />
      <div className="flex flex-col justify-center gap-8">
        {p.photoUrl && <img src={p.photoUrl} alt={name} className="h-32 w-32 rounded-full object-cover ring-2" style={{ "--tw-ring-color": "var(--lw-accent)" } as React.CSSProperties} />}
        {p.yearsExperience > 0 && <div><p className="lw-stat text-5xl">{p.yearsExperience}+</p><p className="mt-1 text-sm font-bold opacity-80">{t("lws.years")}</p></div>}
        <Stats stats={stats} />
      </div>
    </div></section>
  );
  return (   /* band */
    <section className="lw-hero lw-hero-grain"><div className="lw-container relative py-14 md:py-20">
      <div className="max-w-3xl"><p className="lw-eyebrow">{p.barLevel || ""}</p>
        <h1 className="lw-display mt-4 text-[clamp(2.4rem,6vw,4.6rem)] font-bold leading-[1.06]">{p.title && <span className="block text-[.5em] font-semibold opacity-80">{p.title}</span>}{p.displayName}</h1>
        {p.headline && <p className="mt-5 max-w-xl text-lg leading-relaxed opacity-85">{p.headline}</p>}<Ctas p={p} book={book} t={t} /></div>
      <div className="lw-band mt-10 grid items-stretch overflow-hidden rounded-[var(--lw-radius)] md:grid-cols-[1.4fr_1fr]">
        <div className="relative min-h-56 md:min-h-72">{p.photoUrl ? <img src={p.coverUrl || p.photoUrl} alt={name} className="absolute inset-0 h-full w-full object-cover" /> : <div className="absolute inset-0 grid place-items-center text-7xl opacity-30" style={{ background: "linear-gradient(160deg, color-mix(in srgb, var(--lw-accent) 30%, var(--lw-hero-2)), var(--lw-hero))" }}>⚖</div>}</div>
        <div className="flex flex-col justify-center gap-6 bg-[color-mix(in_srgb,var(--lw-hero-ink)_8%,transparent)] p-6 md:p-8"><Stats stats={stats} />{p.yearsExperience > 0 && <p className="text-sm font-bold opacity-80">{p.yearsExperience}+ {t("lws.years")}</p>}</div>
      </div>
    </div></section>
  );
}
