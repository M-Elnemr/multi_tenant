"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { useT } from "./i18n-provider";
import { startGoogleHandoff } from "./google-handoff";
import { ErrorText } from "./ui";

type Cfg = { googleEnabled: boolean; googleClientId?: string; signInHost?: string };
type GoogleId = { accounts: { id: { initialize: (o: { client_id: string; callback: (r: { credential: string }) => void }) => void; renderButton: (el: HTMLElement, o: Record<string, unknown>) => void } } };

/** Google's own button, rendered into a box. Only works on an address listed in the Google client (the platform address). */
export function GoogleGis({ clientId, onCredential }: { clientId: string; onCredential: (credential: string) => void }) {
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const start = () => {
      const g = (window as unknown as { google?: GoogleId }).google;
      if (!g || !box.current) return;
      g.accounts.id.initialize({ client_id: clientId, callback: (r) => onCredential(r.credential) });
      g.accounts.id.renderButton(box.current, { theme: "outline", size: "large", shape: "pill", text: "continue_with", width: 280, locale: document.documentElement.lang || "ar" });
    };
    if ((window as unknown as { google?: GoogleId }).google) { start(); return; }
    const s = document.createElement("script");
    s.src = "https://accounts.google.com/gsi/client";
    s.async = true;
    s.onload = start;
    document.head.appendChild(s);
  }, [clientId, onCredential]);
  return <div ref={box} className="flex justify-center" />;
}

/** Google sign-in for shop clients. Hidden until the platform has a Google client id; the shop never sees or stores a Google password.
 *  On the platform address Google's button works directly; on a shop address the visitor is sent through the platform address and comes back signed in. */
export function GoogleButton({ onSignedIn, next }: { onSignedIn: () => void; next?: string | null }) {
  const t = useT();
  const [cfg, setCfg] = useState<Cfg | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => { api<Cfg>("auth/client/config").then(setCfg).catch(() => setCfg({ googleEnabled: false })); }, []);

  if (!cfg) return null;
  if (!cfg.googleEnabled || !cfg.googleClientId) return <p className="rounded-xl bg-slate-50 p-3 text-center text-sm text-slate-500">{t("login.googleSoon")}</p>;
  const viaPlatform = !!cfg.signInHost && window.location.host.toLowerCase() !== cfg.signInHost.toLowerCase();
  return (
    <div className="space-y-2">
      {viaPlatform ? (
        <div className="flex justify-center">
          <button type="button" onClick={() => startGoogleHandoff(cfg.signInHost!, next ?? window.location.pathname)} className="rounded-full border border-slate-300 bg-white px-6 py-2.5 text-sm font-medium text-slate-700 shadow-sm transition hover:bg-slate-50 active:scale-[.97]">
            {t("login.google")}
          </button>
        </div>
      ) : (
        <GoogleGis clientId={cfg.googleClientId} onCredential={(c) => { api("auth/client/google", { body: { credential: c } }).then(onSignedIn).catch(setError); }} />
      )}
      <ErrorText error={error} />
    </div>
  );
}
