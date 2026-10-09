import { AuthCard } from "@/components/auth-card";
import { GoogleRelay } from "@/components/google-relay";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

/** Shared Google sign-in for every shop (Google only accepts addresses it has been told about, so shops come here). */
export default async function GoogleSignIn({ searchParams }: { searchParams: Promise<{ return?: string; state?: string }> }) {
  const { t } = await getT();
  const { return: ret, state } = await searchParams;
  // Only hand a Google token to a real shop of this platform, never to an arbitrary address.
  let origin: string | null = null;
  try {
    const u = new URL(ret ?? "");
    if ((u.protocol === "https:" || u.protocol === "http:") && !u.username && !u.password) {
      const info = await resolveHost(u.host);
      if (info.kind === "TENANT" && info.type === "STORE") origin = u.origin;
    }
  } catch { /* not a URL */ }
  return (
    <AuthCard title={t("login.title")} subtitle={t("login.shopHint")}>
      {origin && state && /^[0-9a-f-]{36}$/.test(state) ? <GoogleRelay returnOrigin={origin} state={state} /> : <p className="text-center text-sm text-red-600">{t("login.googleFailed")}</p>}
    </AuthCard>
  );
}
