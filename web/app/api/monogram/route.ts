import { currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";

/** A tab icon for shops that have not uploaded a logo yet: the first letter of the shop's name on the shop's own colour. */
export async function GET() {
  const info = await resolveHost(await currentHost());
  const name = info.kind === "TENANT" ? info.name : "E";
  const color = info.kind === "TENANT" && /^#[0-9a-fA-F]{6}$/.test(info.branding.primary_color ?? "") ? info.branding.primary_color! : "#1f6a99";
  const letter = (Array.from(name.trim())[0] ?? "E").toUpperCase().replace(/[<>&"']/g, "");
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="16" fill="${color}"/><text x="32" y="44" font-family="Arial, sans-serif" font-size="36" font-weight="700" text-anchor="middle" fill="#fff">${letter}</text></svg>`;
  return new Response(svg, { headers: { "Content-Type": "image/svg+xml", "Cache-Control": "public, max-age=3600" } });
}
