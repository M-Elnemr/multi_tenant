"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/client";
import { useT } from "./i18n-provider";
import { GoogleGis } from "./google-button";
import { clearGoogleHandoff, readGoogleHandoff, safeNext } from "./google-handoff";
import { ErrorText } from "./ui";

/** Platform address: signs the visitor in with Google, then sends the Google token back to the shop that asked (in the URL fragment). */
export function GoogleRelay({ returnOrigin, state }: { returnOrigin: string; state: string }) {
  const [clientId, setClientId] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    api<{ googleEnabled: boolean; googleClientId?: string }>("auth/client/config").then((c) => setClientId(c.googleEnabled ? c.googleClientId ?? null : null)).catch(setError);
  }, []);
  const done = useCallback((credential: string) => {
    window.location.replace(`${returnOrigin}/google/complete#credential=${encodeURIComponent(credential)}&state=${encodeURIComponent(state)}`);
  }, [returnOrigin, state]);
  const t = useT();
  if (error) return <ErrorText error={error} />;
  if (!clientId) return <p className="text-center text-sm text-slate-500">{t("login.googleSoon")}</p>;
  return <GoogleGis clientId={clientId} onCredential={done} />;
}

/** Shop address: takes the Google token from the fragment, checks it is the sign-in this browser started, and exchanges it for the shop's own session. */
export function GoogleComplete() {
  const t = useT();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const h = new URLSearchParams(window.location.hash.slice(1));
    const credential = h.get("credential");
    const mine = readGoogleHandoff();
    history.replaceState(null, "", window.location.pathname);   // the token must not stay in the address bar / history
    clearGoogleHandoff();
    if (!credential || !mine || mine.state !== h.get("state")) { setFailed(true); return; }
    api("auth/client/google", { body: { credential } })
      .then(() => window.location.replace(safeNext(mine.next)))
      .catch(() => setFailed(true));
  }, []);
  if (failed) return <p className="text-center text-sm text-red-600">{t("login.googleFailed")} <a className="underline" href="/login">{t("login.title")}</a></p>;
  return <p className="text-center text-sm text-slate-500">{t("login.googleBack")}</p>;
}
