import Link from "next/link";
import { money } from "@/lib/format";
import type { LawyerAreas, LawyerProcess, LawyerReviews, LawyerServices } from "@/lib/lawyer-templates";
import type { LawyerSite } from "./types";

type T = (k: string, v?: Record<string, string | number>) => string;
const MODE_ICON = { OFFICE: "🏛", ONLINE: "💻", PHONE: "📞" } as const;
const mute = { color: "var(--lw-mute)" } as const;
const num = (n: number, locale: string) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { minimumIntegerDigits: 2, useGrouping: false });

/** Practice areas in four compositions: cards, numbered rows, compact tiles, or pills. */
export function Areas({ kind, areas, t, locale }: { kind: LawyerAreas; areas: LawyerSite["practiceAreas"]; t: T; locale: string }) {
  const icon = (a: LawyerSite["practiceAreas"][number]) => (a.icon && a.icon.length <= 3 ? a.icon : "⚖️");
  if (kind === "rows") return (
    <ol className="mt-10 divide-y border-y" style={{ borderColor: "var(--lw-line)" }}>
      {areas.map((a, i) => (
        <li key={i} className="lw-reveal flex items-start gap-4 py-6 sm:gap-8" style={{ animationDelay: `${i * 60}ms` }}>
          <span className="lw-display w-12 shrink-0 text-3xl font-bold tnum sm:w-20 sm:text-5xl" style={{ color: "var(--lw-accent)" }}>{num(i + 1, locale)}</span>
          <div className="min-w-0 flex-1"><h3 className="lw-display text-xl font-bold sm:text-2xl">{a.title}</h3><p className="mt-1 text-sm leading-relaxed sm:text-base" style={mute}>{a.description || t("lws.areaDefault")}</p></div>
          <span className="hidden text-2xl sm:block" aria-hidden style={{ color: "var(--lw-accent)" }}>{icon(a)}</span>
        </li>
      ))}
    </ol>
  );
  if (kind === "tiles") return (
    <div className="mt-10 grid gap-3 sm:grid-cols-2">
      {areas.map((a, i) => (
        <article key={i} className="lw-card lw-reveal flex items-start gap-4 p-5" style={{ animationDelay: `${i * 50}ms` }}>
          <span className="lw-icon !h-12 !w-12 shrink-0 !text-xl">{icon(a)}</span>
          <div className="min-w-0"><h3 className="lw-display text-lg font-bold">{a.title}</h3><p className="mt-1 text-sm leading-relaxed" style={mute}>{a.description || t("lws.areaDefault")}</p></div>
        </article>
      ))}
    </div>
  );
  if (kind === "chips") return (
    <ul className="mt-10 flex flex-wrap justify-center gap-3">
      {areas.map((a, i) => (
        <li key={i} className="lw-reveal max-w-sm rounded-[999px] border px-5 py-3 text-start" style={{ borderColor: "var(--lw-accent)", background: "color-mix(in srgb, var(--lw-accent) 8%, var(--lw-surface))", animationDelay: `${i * 40}ms` }}>
          <p className="lw-display flex items-center gap-2 font-bold"><span aria-hidden>{icon(a)}</span>{a.title}</p>
          {a.description && <p className="mt-0.5 line-clamp-2 text-xs leading-relaxed" style={mute}>{a.description}</p>}
        </li>
      ))}
    </ul>
  );
  return (
    <div className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
      {areas.map((a, i) => (
        <article key={i} className="lw-card lw-lift lw-reveal p-7" style={{ animationDelay: `${i * 70}ms` }}>
          <span className="lw-icon">{icon(a)}</span>
          <h3 className="lw-display mt-5 text-xl font-bold">{a.title}</h3>
          <p className="mt-2 text-sm leading-relaxed" style={mute}>{a.description || t("lws.areaDefault")}</p>
        </article>
      ))}
    </div>
  );
}

/** Services as cards or as one table-like list with the price and the booking button on each row. */
export function ServiceList({ kind, services, t, currency, locale, book }: { kind: LawyerServices; services: LawyerSite["services"]; t: T; currency: string; locale: "ar" | "en"; book: boolean }) {
  const price = (s: LawyerSite["services"][number]) => (s.priceMinor == null ? t(`lws.mode.${s.mode}`) : s.priceMinor === 0 ? t("lws.free") : money(s.priceMinor, currency, locale));
  if (kind === "table") return (
    <ul className="lw-card mt-10 divide-y overflow-hidden" style={{ ["--tw-divide-opacity" as string]: 1 }}>
      {services.map((s) => (
        <li key={s.id} className="flex flex-wrap items-center gap-x-6 gap-y-3 p-5 sm:p-6" style={{ borderColor: "var(--lw-line)" }}>
          <span className="text-2xl" aria-hidden>{MODE_ICON[s.mode]}</span>
          <div className="min-w-0 flex-1 basis-56"><h3 className="lw-display text-lg font-bold">{s.name}</h3>{s.description && <p className="mt-0.5 text-sm leading-relaxed" style={mute}>{s.description}</p>}</div>
          <span className="text-sm font-semibold tnum" style={mute}>{t("lws.minutes", { n: s.durationMinutes })}</span>
          <span className="lw-display text-xl font-bold tnum">{price(s)}</span>
          {book && <Link href={`/book?service=${s.id}`} className="lw-btn lw-btn-sm">{t("lws.book")}</Link>}
        </li>
      ))}
    </ul>
  );
  return (
    <div className="mt-10 grid gap-5 md:grid-cols-2 lg:grid-cols-3">
      {services.map((s) => (
        <article key={s.id} className="lw-card lw-lift flex flex-col p-7">
          <div className="flex items-center justify-between"><span className="text-3xl">{MODE_ICON[s.mode]}</span><span className="rounded-full px-3 py-1 text-xs font-bold" style={{ background: "color-mix(in srgb, var(--lw-accent) 14%, transparent)", color: "var(--lw-accent)" }}>{t("lws.minutes", { n: s.durationMinutes })}</span></div>
          <h3 className="lw-display mt-5 text-xl font-bold">{s.name}</h3>
          {s.description && <p className="mt-2 flex-1 text-sm leading-relaxed" style={mute}>{s.description}</p>}
          <div className="mt-6 flex items-center justify-between gap-2 border-t pt-5" style={{ borderColor: "var(--lw-line)" }}>
            <span className="lw-display text-2xl font-bold">{price(s)}</span>
            {book && <Link href={`/book?service=${s.id}`} className="lw-btn lw-btn-sm">{t("lws.book")}</Link>}
          </div>
        </article>
      ))}
    </div>
  );
}

/** How it works: three cards in a panel, a vertical timeline, or three open columns. */
export function Process({ kind, steps, locale }: { kind: LawyerProcess; steps: { n: number; title: string; text: string }[]; locale: string }) {
  if (kind === "timeline") return (
    <ol className="relative mx-auto max-w-2xl space-y-10 ps-10 before:absolute before:inset-y-2 before:start-3 before:w-px before:bg-[var(--lw-accent)] before:opacity-50 before:content-['']">
      {steps.map((s) => (
        <li key={s.n} className="relative">
          <span className="absolute -start-10 top-0 grid h-7 w-7 place-items-center rounded-full text-xs font-bold tnum" style={{ background: "var(--lw-accent)", color: "var(--lw-accent-ink)" }}>{num(s.n, locale).replace(/^[0٠]/, "")}</span>
          <h3 className="lw-display text-xl font-bold">{s.title}</h3><p className="mt-1 text-sm leading-relaxed" style={mute}>{s.text}</p>
        </li>
      ))}
    </ol>
  );
  if (kind === "inline") return (
    <div className="grid gap-8 md:grid-cols-3 md:gap-0 md:divide-x md:divide-x-reverse" style={{ borderColor: "var(--lw-line)" }}>
      {steps.map((s) => <div key={s.n} className="md:px-8"><span className="lw-display text-4xl font-bold tnum" style={{ color: "var(--lw-accent)" }}>{num(s.n, locale)}</span><h3 className="lw-display mt-2 text-xl font-bold">{s.title}</h3><p className="mt-2 text-sm leading-relaxed" style={mute}>{s.text}</p></div>)}
    </div>
  );
  return (
    <div className="lw-card grid gap-8 overflow-hidden p-8 md:grid-cols-3 md:p-12">
      {steps.map((s) => (
        <div key={s.n} className="relative"><span className="lw-display text-6xl font-bold tnum opacity-15" style={{ color: "var(--lw-accent)" }}>{num(s.n, locale)}</span>
          <h3 className="lw-display -mt-6 text-xl font-bold">{s.title}</h3><p className="mt-2 text-sm leading-relaxed" style={mute}>{s.text}</p></div>
      ))}
    </div>
  );
}

const stars = (r: number) => "★".repeat(Math.max(1, Math.min(5, r || 5)));

/** Client reviews: a grid, one large quote with the others small, or a masonry wall. */
export function Reviews({ kind, items }: { kind: LawyerReviews; items: LawyerSite["testimonials"] }) {
  if (kind === "single") {
    const [first, ...rest] = items;
    return (
      <div className="mx-auto mt-10 max-w-3xl text-center">
        <span className="lw-quote">“</span>
        <blockquote className="lw-display text-2xl font-semibold leading-relaxed sm:text-3xl">{first.text}</blockquote>
        <p className="mt-5"><b>{first.name}</b> <span className="text-amber-500">{stars(first.rating)}</span></p>
        {rest.length > 0 && <ul className="mt-10 grid gap-4 text-start sm:grid-cols-2">{rest.slice(0, 4).map((r, i) => <li key={i} className="lw-card p-5 text-sm leading-relaxed">{r.text}<p className="mt-3 font-bold">{r.name}</p></li>)}</ul>}
      </div>
    );
  }
  if (kind === "wall") return (
    <div className="mt-10 columns-1 gap-5 sm:columns-2 lg:columns-3 [&>*]:mb-5 [&>*]:break-inside-avoid">
      {items.map((r, i) => <figure key={i} className="lw-card p-6"><p className="leading-relaxed">{r.text}</p><figcaption className="mt-4 flex items-center justify-between text-sm"><b>{r.name}</b><span className="text-amber-500">{stars(r.rating)}</span></figcaption></figure>)}
    </div>
  );
  return (
    <div className="mt-12 grid gap-5 md:grid-cols-2 lg:grid-cols-3">
      {items.map((r, i) => (
        <figure key={i} className="lw-card p-7"><span className="lw-quote">“</span><blockquote className="mt-2 leading-relaxed">{r.text}</blockquote>
          <figcaption className="mt-5 flex items-center justify-between"><b>{r.name}</b><span className="text-amber-500">{stars(r.rating)}</span></figcaption></figure>
      ))}
    </div>
  );
}
