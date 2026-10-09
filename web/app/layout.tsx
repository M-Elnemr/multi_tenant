import type { Metadata, Viewport } from "next";
import { Cairo } from "next/font/google";
import "./globals.css";
import { I18nProvider } from "@/components/i18n-provider";
import { headers } from "next/headers";
import { currentHost } from "@/lib/backend";
import { dir } from "@/lib/i18n";
import { getLocale } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

const cairo = Cairo({ subsets: ["arabic", "latin"], variable: "--font-cairo", display: "swap" });

export const viewport: Viewport = { themeColor: "#1f6a99", width: "device-width", initialScale: 1 };

export async function generateMetadata(): Promise<Metadata> {
  const host = await currentHost();
  const info = await resolveHost(host);
  // Absolute base for og:image etc.: the public host the visitor used (not the container's localhost:3000).
  const proto = (await headers()).get("x-forwarded-proto")?.split(",")[0].trim() || (/^(localhost|127\.)/.test(host) ? "http" : "https");
  let metadataBase: URL | undefined;
  try { metadataBase = host ? new URL(`${proto}://${host}`) : undefined; } catch { /* malformed host: leave unset */ }
  const name = info.kind === "TENANT" ? info.name : "Elmanassa | المنصة";
  const logo = info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id : undefined;
  // A tenant with its own logo uses it as the tab icon; everything else shows the Elmanassa icon (app/icon.png).
  const icons = logo ? { icon: `/api/bff/files/${logo}/content?variant=thumb` } : undefined;
  return {
    metadataBase,
    title: { default: name, template: `%s | ${name}` },
    description: info.kind === "TENANT" ? name : "Online stores and clinic websites in minutes · متاجر ومواقع عيادات في دقائق",
    manifest: "/manifest.webmanifest",
    ...(icons ? { icons } : {}),
    openGraph: { title: name, siteName: name, images: logo ? undefined : [{ url: "/brand/og.png", width: 1200, height: 630 }] },
  };
}

const HEX = /^#[0-9a-fA-F]{3,8}$/;

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const locale = await getLocale();
  const info = await resolveHost(await currentHost());
  // Tenant branding only ever overrides design tokens (validated colours), never injects arbitrary CSS (spec 28).
  const style: Record<string, string> = {};
  if (info.kind === "TENANT") {
    if (info.branding.primary_color && HEX.test(info.branding.primary_color)) style["--brand"] = info.branding.primary_color;
    if (info.branding.secondary_color && HEX.test(info.branding.secondary_color)) style["--brand-2"] = info.branding.secondary_color;
  }
  return (
    <html lang={locale} dir={dir(locale)} style={style} className={cairo.variable}>
      <body className="min-h-screen antialiased">
        <I18nProvider locale={locale} currency={info.kind === "TENANT" ? info.currency : "EGP"} timezone={info.kind === "TENANT" ? info.timezone : "Africa/Cairo"} tenantType={info.kind === "TENANT" ? info.type : null} siteName={info.kind === "TENANT" ? info.name : ""}>
          {children}
        </I18nProvider>
      </body>
    </html>
  );
}
