"use client";

import { useEffect, useState } from "react";
import { GoogleGis } from "../google-button";
import { startGoogleHandoff } from "../google-handoff";
import { useI18n } from "../i18n-provider";
import { errMsg, oapi } from "@/lib/open-client";

type Cfg = { googleEnabled: boolean; googleClientId?: string; signInHost?: string };

/** Google sign-in for the marketplace: the shops' own clients, so one Google account is the same customer everywhere.
 *  Google only trusts the platform address, so from open.<root> the visitor goes through it and comes back signed in (same handoff the shops use). */
export function GoogleSignIn({ next, onSignedIn }: { next: string; onSignedIn: () => void }) {
  const { t } = useI18n();
  const [cfg, setCfg] = useState<Cfg | null>(null);
  const [error, setError] = useState("");
  useEffect(() => { oapi<Cfg>("auth/config").then(setCfg).catch(() => setCfg({ googleEnabled: false })); }, []);
  if (!cfg) return <div className="o-skel mx-auto h-11 w-64" style={{ borderRadius: 99 }} />;
  if (!cfg.googleEnabled || !cfg.googleClientId) return <p className="rounded-xl bg-[var(--o-soft)] p-3 text-center text-sm text-[var(--o-mute)]">{t("login.googleSoon")}</p>;
  const viaPlatform = !!cfg.signInHost && window.location.host.toLowerCase() !== cfg.signInHost.toLowerCase();
  return (
    <div className="grid gap-3">
      {viaPlatform ? (
        <button type="button" onClick={() => startGoogleHandoff(cfg.signInHost!, next)} className="o-btn o-btn-ghost o-btn-block !min-h-12">
          <svg viewBox="0 0 48 48" className="h-5 w-5" aria-hidden><path fill="#FFC107" d="M43.6 20.1H42V20H24v8h11.3A12 12 0 1 1 24 12c3 0 5.8 1.1 7.9 3l5.7-5.7A20 20 0 1 0 44 24c0-1.3-.1-2.7-.4-3.9z" /><path fill="#FF3D00" d="m6.3 14.7 6.6 4.8A12 12 0 0 1 24 12c3 0 5.8 1.1 7.9 3l5.7-5.7A20 20 0 0 0 6.3 14.7z" /><path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2A12 12 0 0 1 12.7 28l-6.5 5A20 20 0 0 0 24 44z" /><path fill="#1976D2" d="M43.6 20.1H42V20H24v8h11.3a12 12 0 0 1-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.7-.4-3.9z" /></svg>
          {t("login.google")}
        </button>
      ) : (
        <GoogleGis clientId={cfg.googleClientId} onCredential={(c) => { oapi("auth/google", { body: { credential: c } }).then(onSignedIn).catch((e) => setError(errMsg(t, e))); }} />
      )}
      {error && <p role="alert" className="text-center text-sm font-bold text-[var(--o-red)]">{error}</p>}
    </div>
  );
}
