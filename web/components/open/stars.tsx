import { Icon } from "../icons";

/** Five outlined/filled stars drawn with the shared icon set (no text glyphs). `value` may be fractional; it is rounded to whole stars. */
export function Stars({ value, className = "h-4 w-4" }: { value: number; className?: string }) {
  const n = Math.round(value);
  return (
    <span className="o-stars inline-flex items-center gap-0.5" role="img" aria-label={`${value.toFixed(1)} / 5`}>
      {[1, 2, 3, 4, 5].map((i) => <Icon key={i} name="star" className={`${className} ${i <= n ? "fill-current" : "fill-none opacity-35"}`} />)}
    </span>
  );
}
