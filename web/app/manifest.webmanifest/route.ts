import { currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";

/** "Add to home screen" identity per address: a shop installs as itself (its name, logo and colour); only the platform site is Elmanassa. */
export async function GET() {
  const info = await resolveHost(await currentHost());
  let manifest: Record<string, unknown>;
  if (info.kind === "TENANT") {
    const logo = (info.branding as { logo_file_id?: string }).logo_file_id;
    const color = /^#[0-9a-fA-F]{6}$/.test(info.branding.primary_color ?? "") ? info.branding.primary_color! : "#1f6a99";
    manifest = {
      name: info.name, short_name: info.name.slice(0, 12), start_url: "/", display: "standalone", background_color: "#faf8f5", theme_color: color,
      icons: logo
        ? [{ src: `/api/bff/files/${logo}/content?variant=medium`, sizes: "512x512", type: "image/png", purpose: "any" }]
        : [{ src: "/api/monogram", sizes: "any", type: "image/svg+xml", purpose: "any" }],
    };
  } else {
    manifest = {
      name: "Elmanassa | المنصة", short_name: "Elmanassa", start_url: "/", display: "standalone", background_color: "#ffffff", theme_color: "#1f6a99",
      icons: [
        { src: "/brand/icon-192.png", sizes: "192x192", type: "image/png" },
        { src: "/brand/icon-512.png", sizes: "512x512", type: "image/png" },
        { src: "/brand/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
      ],
    };
  }
  return new Response(JSON.stringify(manifest), { headers: { "Content-Type": "application/manifest+json", "Cache-Control": "public, max-age=600" } });
}
