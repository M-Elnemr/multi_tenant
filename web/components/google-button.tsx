"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { useT } from "./i18n-provider";
import { ErrorText } from "./ui";

type Cfg = { googleEnabled: boolean; googleClientId?: string };
type GoogleId = { accounts: { id: { initialize: (o: { client_id: string; callback: (r: { credential: string }) => void }) => void; renderButton: (el: HTMLElement, o: Record<string, unknown>) => void } } };

/** Google sign-in for shop clients. Hidden until the platform has a Google client id; the shop never sees or stores a Google password. */
export function GoogleButton({ onSignedIn }: { onSignedIn: () => void }) {
  const t = useT();
  const box = useRef<HTMLDivElement>(null);
  const [cfg, setCfg] = useState<Cfg | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => { api<Cfg>("auth/client/config").then(setCfg).catch(() => setCfg({ googleEnabled: false })); }, []);

  useEffect(() => {
    if (!cfg?.googleEnabled || !cfg.googleClientId) return;
    const clientId = cfg.googleClientId;
    const start = () => {
      const g = (window as unknown as { google?: GoogleId }).google;
      if (!g || !box.current) return;
      g.accounts.id.initialize({
        client_id: clientId,
        callback: (r) => { api("auth/client/google", { body: { credential: r.credential } }).then(onSignedIn).catch(setError); },
      });
      g.accounts.id.renderButton(box.current, { theme: "outline", size: "large", shape: "pill", text: "continue_with", width: 280, locale: document.documentElement.lang || "ar" });
    };
    if ((window as unknown as { google?: GoogleId }).google) { start(); return; }
    const s = document.createElement("script");
    s.src = "https://accounts.google.com/gsi/client";
    s.async = true;
    s.onload = start;
    document.head.appendChild(s);
  }, [cfg, onSignedIn]);

  if (!cfg) return null;
  if (!cfg.googleEnabled) return <p className="rounded-xl bg-slate-50 p-3 text-center text-sm text-slate-500">{t("login.googleSoon")}</p>;
  return (
    <div className="space-y-2">
      <div ref={box} className="flex justify-center" />
      <ErrorText error={error} />
    </div>
  );
}
