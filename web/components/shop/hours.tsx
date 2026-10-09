import { DAYS, hasHours, type WeekHours } from "@/lib/hours";

/** Weekly opening hours as a compact list (server-safe). `t` translates day names and "closed". */
export function HoursList({ hours, t, className = "" }: { hours?: WeekHours | null; t: (k: string) => string; className?: string }) {
  if (!hasHours(hours)) return null;
  return (
    <ul className={`space-y-1 text-sm ${className}`}>
      {DAYS.map((d) => {
        const h = hours?.[d];
        if (!h) return null;
        return <li key={d} className="flex justify-between gap-6"><span>{t(`day.${d}`)}</span><span dir="ltr" className={h.closed ? "opacity-60" : "font-semibold"}>{h.closed ? t("hours.closed") : `${h.open} – ${h.close}`}</span></li>;
      })}
    </ul>
  );
}
