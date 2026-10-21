import type { Metadata } from "next";
import { LoginView } from "./login-view";
import { getT } from "@/lib/i18n-server";

export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.signIn") }; }
export default async function LoginPage({ searchParams }: { searchParams: Promise<{ next?: string; mode?: string }> }) {
  const sp = await searchParams;
  const next = sp.next && sp.next.startsWith("/") && !sp.next.startsWith("//") ? sp.next : "/account";
  return <LoginView next={next} initialMode={sp.mode === "register" ? "register" : "login"} />;
}
