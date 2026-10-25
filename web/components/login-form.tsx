"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "./hooks";
import { useT } from "./i18n-provider";
import { Button, ErrorText, Field, Input } from "./ui";
import { IdentifierInput } from "@/components/inputs";

type Me = { roles: string[]; permissions: string[]; mustChangePassword?: boolean };
type Step = "login" | "activate" | "reset" | "mfa";

/** Phone/email + password. First-time users (and anyone who forgot their password) use the one-time PIN given by the business: no SMS. */
export function LoginForm({ defaultIdentifier = "" }: { defaultIdentifier?: string }) {
  const t = useT();
  const router = useRouter();
  const next = useSearchParams().get("next");
  const [step, setStep] = useState<Step>("login");
  const [show, setShow] = useState(false);
  const [identifier, setIdentifier] = useState(defaultIdentifier);
  const [password, setPassword] = useState("");
  const [pin, setPin] = useState("");
  const [code, setCode] = useState("");   // the authenticator (or recovery) code, asked only when two-step sign-in is on

  /** Where this identity belongs. `next` is honoured only when it is somewhere that identity can actually use. */
  async function finish() {
    const me = await api<Me>("auth/me");
    const patient = me.roles.includes("PATIENT");
    const staff = me.roles.some((r) => r !== "CUSTOMER" && r !== "PATIENT");
    let target = "/";
    let allowed: string[] = ["/"];
    if (me.permissions.some((p) => p.startsWith("platform."))) { target = "/admin"; allowed = ["/admin"]; }
    else if (patient && !staff) { target = me.mustChangePassword ? "/portal/account" : "/portal"; allowed = ["/portal"]; }
    else if (me.roles.length === 0) { target = "/portal"; allowed = ["/portal"]; }   // platform host: show every place this person belongs to
    else if (staff) { target = "/dashboard"; allowed = ["/dashboard", "/admin"]; }
    else allowed = ["/"];   // shop customer: keep the default
    const ok = !me.mustChangePassword && !!next && next.startsWith("/") && !next.startsWith("//") && allowed.some((a) => a === "/" ? next === "/" || !next.startsWith("/dashboard") : next === a || next.startsWith(a + "/") || next.startsWith(a + "?"));
    router.replace(ok ? next : target);
    router.refresh();
  }

  const login = useAction(async () => {
    try {
      await api("auth/login", { body: { identifier: identifier.trim(), password, ...(step === "mfa" ? { totpCode: code.trim() } : {}) } });
    } catch (e) {
      // a colleague who was invited has no password yet: carry on with the one-time PIN instead of "wrong password"
      if ((e as { code?: string }).code === "ACTIVATION_REQUIRED") { setStep("activate"); setPassword(""); return; }
      if ((e as { code?: string }).code === "MFA_REQUIRED" && step === "login") { setStep("mfa"); setCode(""); return; }
      throw e;
    }
    await finish();
  });
  const activate = useAction(async (path: "auth/activate" | "auth/reset-password") => {
    await api(path, { body: { identifier: identifier.trim(), pin: pin.trim(), newPassword: password } });
    await finish();
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (step === "login" || step === "mfa") void login.run();
    else void activate.run(step === "activate" ? "auth/activate" : "auth/reset-password");
  };
  const busy = login.loading || activate.loading;
  const error = login.error ?? activate.error;

  return (
    <form onSubmit={submit} className="space-y-4">
      <Field label={t("login.identifier")} hint={step === "login" ? t("login.identifierHint") : undefined}>
        <IdentifierInput value={identifier} onValue={setIdentifier} required disabled={step !== "login"} autoFocus={step === "login"} />
      </Field>

      {step === "activate" && <p className="rounded-lg bg-sky-50 p-3 text-sm text-sky-900">{t("login.firstTime")}</p>}
      {step === "reset" && <p className="rounded-lg bg-sky-50 p-3 text-sm text-sky-900">{t("login.resetInfo")}</p>}
      {step !== "login" && (
        <Field label={t("login.pin")} hint={t("login.pinHint")}>
          <Input value={pin} onChange={(e) => setPin(e.target.value)} inputMode="numeric" dir="ltr" autoComplete="one-time-code" required autoFocus />
        </Field>
      )}
      {step === "mfa" && (
        <Field label={t("login.mfaCode")} hint={t("login.mfaHint")}>
          <Input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" dir="ltr" autoComplete="one-time-code" maxLength={12} required autoFocus />
        </Field>
      )}
      <Field label={step === "login" || step === "mfa" ? t("login.password") : t("login.newPassword")} hint={step === "login" ? undefined : t("login.passwordRule")}>
        <div className="relative">
          <Input type={show ? "text" : "password"} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete={step === "login" || step === "mfa" ? "current-password" : "new-password"} minLength={step === "login" || step === "mfa" ? undefined : 8} readOnly={step === "mfa"} required className="pe-16" />
          <button type="button" onClick={() => setShow((v) => !v)} className="absolute end-3 top-1/2 -translate-y-1/2 text-xs font-semibold text-slate-500 hover:text-brand" tabIndex={-1}>{show ? t("login.hide") : t("login.show")}</button>
        </div>
      </Field>

      <ErrorText error={error} />
      <Button type="submit" loading={busy} className="w-full">{step === "login" || step === "mfa" ? t("login.submit") : t("login.createPassword")}</Button>
      {step === "login" && <button type="button" onClick={() => { setStep("reset"); setPassword(""); }} className="block w-full text-center text-sm text-slate-500 underline">{t("login.forgot")}</button>}
      {step !== "login" && <button type="button" onClick={() => { setStep("login"); setPassword(""); setPin(""); setCode(""); }} className="block w-full text-center text-sm text-slate-500 underline">{t("common.back")}</button>}
    </form>
  );
}
