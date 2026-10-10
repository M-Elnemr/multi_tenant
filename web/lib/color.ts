/** Brand colours come from clinics/shops and can be too light for white text. Darkens a hex colour until white text on it reaches `min` contrast (WCAG ratio). */
function lum(r: number, g: number, b: number) {
  const f = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

export function readableBrand(hex: string, min = 4.5): string {
  let h = hex.replace("#", "");
  if (h.length === 3 || h.length === 4) h = h.split("").map((c) => c + c).join("");
  if (h.length < 6) return hex;
  let r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
  if ([r, g, b].some(Number.isNaN)) return hex;
  for (let i = 0; i < 40 && 1.05 / (lum(r, g, b) + 0.05) < min; i++) { r = Math.round(r * 0.92); g = Math.round(g * 0.92); b = Math.round(b * 0.92); }
  return "#" + [r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("");
}
