import type { Metadata } from "next";
import "./globals.css";
import { I18nProvider } from "@/components/i18n-provider";
import { currentHost } from "@/lib/backend";
import { dir } from "@/lib/i18n";
import { getLocale } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

export async function generateMetadata(): Promise<Metadata> {
  const info = await resolveHost(await currentHost());
  const name = info.kind === "TENANT" ? info.name : "Platform";
  return { title: { default: name, template: `%s | ${name}` }, description: name };
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
    <html lang={locale} dir={dir(locale)} style={style}>
      <body className="min-h-screen antialiased">
        <I18nProvider locale={locale} currency={info.kind === "TENANT" ? info.currency : "EGP"} timezone={info.kind === "TENANT" ? info.timezone : "Africa/Cairo"} tenantType={info.kind === "TENANT" ? info.type : null} siteName={info.kind === "TENANT" ? info.name : ""}>
          {children}
        </I18nProvider>
      </body>
    </html>
  );
}
