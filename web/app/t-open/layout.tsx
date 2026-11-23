import type { Metadata, Viewport } from "next";
import "./open.css";
import { OpenFooter } from "@/components/open/footer";
import { OpenHeader } from "@/components/open/header";
import { OpenTabBar } from "@/components/open/tab-bar";
import { currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { openJson, type OpenCategory } from "@/lib/open";
import { resolveHost } from "@/lib/tenant";

export const viewport: Viewport = { themeColor: "#0d1535", width: "device-width", initialScale: 1, viewportFit: "cover", interactiveWidget: "resizes-content" };

export async function generateMetadata(): Promise<Metadata> {
  const { t } = await getT();
  const title = t("open.meta.title");
  return { title: { default: title, template: `%s | ${t("brand.name")} Open` }, description: t("open.meta.description"), openGraph: { title, description: t("open.meta.description"), siteName: `${t("brand.name")} Open`, images: [{ url: "/brand/og.png", width: 1200, height: 630 }] } };
}

/** The Open Store shell: one marketplace over every shop. It wears the platform's marketplace look, not any single shop's branding. */
export default async function OpenLayout({ children }: { children: React.ReactNode }) {
  const { t } = await getT();
  const info = await resolveHost(await currentHost());
  const rootDomain = info.kind === "OPEN" ? info.rootDomain : "";
  const tree = await openJson<OpenCategory[]>("/categories").catch(() => [] as OpenCategory[]);
  return (
    <div className="open flex min-h-dvh flex-col">
      <OpenHeader tree={tree} rootDomain={rootDomain} />
      <main className="flex-1 pb-chrome">{children}</main>
      <OpenFooter t={t} rootDomain={rootDomain} />
      <OpenTabBar />
    </div>
  );
}
