"use client";

import { useEffect, useState } from "react";
import { clearGoogleHandoff, readGoogleHandoff, safeNext } from "@/components/google-handoff";
import { useI18n } from "@/components/i18n-provider";
import { oapi } from "@/lib/open-client";

/** Takes the Google token from the address fragment, checks it is the sign-in this browser started, and exchanges it for the marketplace session. */
export function GoogleCompleteOpen() {
  const { t } = useI18n();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const h = new URLSearchParams(window.location.hash.slice(1));
    const credential = h.get("credential");
    const mine = readGoogleHandoff();
    history.replaceState(null, "", window.location.pathname);   // the token must not stay in the address bar / history
    clearGoogleHandoff();
    const valid = !!credential && !!mine && mine.state === h.get("state");
    (valid ? oapi("auth/google", { body: { credential } }) : Promise.reject(new Error("bad handoff")))
      .then(() => window.location.replace(safeNext(mine?.next)))
      .catch(() => setFailed(true));
  }, []);
  if (failed) return <p className="text-sm font-bold text-[var(--o-red)]">{t("login.googleFailed")} <a className="underline" href="/login">{t("open.signIn")}</a></p>;
  return <p className="o-mute text-sm">{t("login.googleBack")}</p>;
}
