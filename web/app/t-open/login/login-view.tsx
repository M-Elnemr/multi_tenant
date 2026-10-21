"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { errMsg, oapi } from "@/lib/open-client";

export function LoginView({ next, initialMode }: { next: string; initialMode: "login" | "register" }) {
  const { t } = useI18n();
  const router = useRouter();
  const [mode, setMode] = useState(initialMode);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError("");
    try {
      await oapi(mode === "login" ? "auth/login" : "auth/register", { body: mode === "login" ? { phone, password } : { name, phone, password } });
      router.replace(next);
      router.refresh();
    } catch (err) { setError(errMsg(t, err)); } finally { setBusy(false); }
  };
  return (
    <div className="o-container grid max-w-md pt-8 md:pt-16">
      <div className="o-card o-reveal overflow-hidden">
        <div className="bg-gradient-to-br from-[var(--o-navy)] to-[var(--o-navy-2)] px-6 py-7 text-white">
          <p className="text-3xl">👋</p>
          <h1 className="mt-2 text-2xl font-black">{mode === "login" ? t("open.auth.welcomeBack") : t("open.auth.createAccount")}</h1>
          <p className="mt-1 text-sm text-white/75">{t("open.auth.sub")}</p>
        </div>
        <form onSubmit={submit} className="grid gap-4 p-6">
          <div className="grid grid-cols-2 gap-1 rounded-full bg-[var(--o-soft)] p-1">
            {(["login", "register"] as const).map((m) => <button key={m} type="button" onClick={() => { setMode(m); setError(""); }} className={`rounded-full py-2 text-sm font-extrabold transition ${mode === m ? "bg-white shadow" : "text-[var(--o-mute)]"}`}>{t(m === "login" ? "open.signIn" : "open.auth.register")}</button>)}
          </div>
          {mode === "register" && <div><label className="o-label" htmlFor="a-name">{t("open.checkout.name")}</label><input id="a-name" value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" required className="o-input" /></div>}
          <div><label className="o-label" htmlFor="a-phone">{t("open.checkout.phone")}</label><input id="a-phone" value={phone} onChange={(e) => setPhone(e.target.value)} type="tel" inputMode="tel" autoComplete="tel" placeholder="01xxxxxxxxx" dir="ltr" required className="o-input" /></div>
          <div><label className="o-label" htmlFor="a-pass">{t("open.auth.password")}</label><input id="a-pass" value={password} onChange={(e) => setPassword(e.target.value)} type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} minLength={mode === "register" ? 8 : undefined} required className="o-input" />{mode === "register" && <p className="o-mute mt-1 text-xs">{t("error.WEAK_PASSWORD")}</p>}</div>
          {error && <p role="alert" className="rounded-xl bg-[#fef3f2] px-3.5 py-2.5 text-sm font-bold text-[#b42318]">{error}</p>}
          <button type="submit" disabled={busy} className="o-btn o-btn-block !min-h-12">{busy ? t("common.loading") : t(mode === "login" ? "open.signIn" : "open.auth.register")}</button>
          <p className="o-mute text-center text-xs">{t("open.auth.guestNote")}</p>
        </form>
      </div>
    </div>
  );
}
