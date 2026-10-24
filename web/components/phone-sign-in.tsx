"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "./hooks";
import { useT } from "./i18n-provider";
import { PhoneInput } from "./inputs";
import { Alert, Button, ErrorText, Field, Input } from "./ui";

type Cfg = { phoneRegisterEnabled?: boolean };
type Mode = "login" | "register" | "forgot";

/**
 * Phone and password for shop customers: sign in, sign up (a code arrives on WhatsApp first, so only the owner of the number can create the account),
 * and "forgot password" (the same code). Customers who ordered through WhatsApp were already sent their number and a temporary password there.
 */
export function PhoneSignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const t = useT();
  const [mode, setMode] = useState<Mode>("login");
  const [codes, setCodes] = useState(false);
  const [f, setF] = useState({ name: "", phone: "", password: "", code: "", sent: false });
  useEffect(() => { api<Cfg>("auth/client/config").then((c) => setCodes(!!c.phoneRegisterEnabled)).catch(() => undefined); }, []);

  const login = useAction(async () => { await api("auth/client/login", { body: { phone: f.phone, password: f.password } }); onSignedIn(); });
  const requestCode = useAction(async () => {
    if (mode === "register") await api("auth/client/register", { body: { name: f.name, phone: f.phone, password: f.password } });
    else await api("auth/client/password/forgot", { body: { phone: f.phone } });
    setF({ ...f, sent: true });
  });
  const finish = useAction(async () => {
    if (mode === "register") await api("auth/client/register/verify", { body: { phone: f.phone, code: f.code } });
    else await api("auth/client/password/reset", { body: { phone: f.phone, code: f.code, newPassword: f.password } });
    onSignedIn();
  });
  const go = (m: Mode) => { setMode(m); setF({ ...f, sent: false, code: "", password: "" }); };

  return (
    <div className="mt-6 space-y-3 border-t pt-5">
      <div className="flex justify-center gap-2 text-sm font-semibold">
        <button type="button" onClick={() => go("login")} className={`rounded-full px-3 py-1 ${mode === "login" ? "bg-brand text-white" : "bg-slate-100"}`}>{t("psi.login")}</button>
        {codes && <button type="button" onClick={() => go("register")} className={`rounded-full px-3 py-1 ${mode === "register" ? "bg-brand text-white" : "bg-slate-100"}`}>{t("psi.register")}</button>}
      </div>
      <p className="text-center text-xs text-slate-500">{mode === "login" ? t("psi.loginHint") : mode === "register" ? t("psi.registerHint") : t("psi.forgotHint")}</p>

      {mode === "login" && (
        <form onSubmit={(e) => { e.preventDefault(); void login.run(); }} className="space-y-3">
          <Field label={t("register.phone")}><PhoneInput value={f.phone} onValue={(phone) => setF({ ...f, phone })} required /></Field>
          <Field label={t("psi.password")}><Input type="password" autoComplete="current-password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required /></Field>
          <ErrorText error={login.error} />
          <Button type="submit" className="w-full" loading={login.loading}>{t("psi.login")}</Button>
          {codes && <button type="button" onClick={() => go("forgot")} className="block w-full text-center text-sm text-brand underline">{t("psi.forgot")}</button>}
        </form>
      )}

      {mode !== "login" && !f.sent && (
        <form onSubmit={(e) => { e.preventDefault(); void requestCode.run(); }} className="space-y-3">
          {mode === "register" && <Field label={t("form.name")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>}
          <Field label={t("register.phone")} hint={t("psi.phoneHint")}><PhoneInput value={f.phone} onValue={(phone) => setF({ ...f, phone })} required /></Field>
          {mode === "register" && <Field label={t("psi.password")} hint={t("psi.passwordHint")}><Input type="password" autoComplete="new-password" minLength={8} value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required /></Field>}
          <ErrorText error={requestCode.error} />
          <Button type="submit" className="w-full" loading={requestCode.loading}>{t("psi.sendCode")}</Button>
          {mode === "forgot" && <button type="button" onClick={() => go("login")} className="block w-full text-center text-sm text-slate-500 underline">{t("psi.back")}</button>}
        </form>
      )}

      {mode !== "login" && f.sent && (
        <form onSubmit={(e) => { e.preventDefault(); void finish.run(); }} className="space-y-3">
          <Alert tone="green">{t("psi.codeSent")}</Alert>
          <Field label={t("psi.code")}><Input dir="ltr" inputMode="numeric" maxLength={6} autoComplete="one-time-code" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value.replace(/\D/g, "") })} required /></Field>
          {mode === "forgot" && <Field label={t("psi.newPassword")} hint={t("psi.passwordHint")}><Input type="password" autoComplete="new-password" minLength={8} value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required /></Field>}
          <ErrorText error={finish.error} />
          <Button type="submit" className="w-full" loading={finish.loading} disabled={f.code.length !== 6}>{mode === "register" ? t("psi.confirmRegister") : t("psi.confirmReset")}</Button>
          <button type="button" onClick={() => setF({ ...f, sent: false, code: "" })} className="block w-full text-center text-sm text-slate-500 underline">{t("psi.resend")}</button>
        </form>
      )}
    </div>
  );
}
