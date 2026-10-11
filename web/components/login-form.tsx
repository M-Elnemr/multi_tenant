"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "./hooks";
import { useT } from "./i18n-provider";
import { Button, ErrorText, Field, Input } from "./ui";
import { IdentifierInput } from "@/components/inputs";

type Me = { roles: string[]; permissions: string[]; mustChangePassword?: boolean };
type Step = "identify" | "password" | "activate" | "reset";

/** Phone/email + password. First-time users (and anyone who forgot their password) use the one-time PIN given by the business: no SMS. */
export function LoginForm({ defaultIdentifier = "" }: { defaultIdentifier?: string }) {
  const t = useT();
  const router = useRouter();
  const next = useSearchParams().get("next");
  const [step, setStep] = useState<Step>("identify");
  const [identifier, setIdentifier] = useState(defaultIdentifier);
  const [password, setPassword] = useState("");
  const [pin, setPin] = useState("");

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

  const identify = useAction(async () => {
    const r = await api<{ next: "ENTER_PASSWORD" | "ACTIVATE" }>("auth/check-identifier", { body: { identifier: identifier.trim() } });
    setStep(r.next === "ACTIVATE" ? "activate" : "password");
  });
  const login = useAction(async () => {
    await api("auth/login", { body: { identifier: identifier.trim(), password } });
    await finish();
  });
  const activate = useAction(async (path: "auth/activate" | "auth/reset-password") => {
    await api(path, { body: { identifier: identifier.trim(), pin: pin.trim(), newPassword: password } });
    await finish();
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (step === "identify") void identify.run();
    else if (step === "password") void login.run();
    else void activate.run(step === "activate" ? "auth/activate" : "auth/reset-password");
  };
  const busy = identify.loading || login.loading || activate.loading;
  const error = identify.error ?? login.error ?? activate.error;

  return (
    <form onSubmit={submit} className="space-y-4">
      <Field label={t("login.identifier")} hint={t("login.identifierHint")}>
        <IdentifierInput value={identifier} onValue={setIdentifier} required disabled={step !== "identify"} autoFocus />
      </Field>

      {step === "activate" && <p className="rounded-lg bg-sky-50 p-3 text-sm text-sky-900">{t("login.firstTime")}</p>}
      {step === "reset" && <p className="rounded-lg bg-sky-50 p-3 text-sm text-sky-900">{t("login.resetInfo")}</p>}
      {(step === "activate" || step === "reset") && (
        <Field label={t("login.pin")} hint={t("login.pinHint")}>
          <Input value={pin} onChange={(e) => setPin(e.target.value)} inputMode="numeric" dir="ltr" autoComplete="one-time-code" required autoFocus />
        </Field>
      )}
      {step !== "identify" && (
        <Field label={step === "password" ? t("login.password") : t("login.newPassword")} hint={step === "password" ? undefined : t("login.passwordRule")}>
          <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete={step === "password" ? "current-password" : "new-password"} minLength={step === "password" ? undefined : 8} required autoFocus={step === "password"} />
        </Field>
      )}

      <ErrorText error={error} />
      <Button type="submit" loading={busy} className="w-full">
        {step === "identify" ? t("common.continue") : step === "password" ? t("login.submit") : t("login.createPassword")}
      </Button>
      {step === "password" && <button type="button" onClick={() => { setStep("reset"); setPassword(""); }} className="block w-full text-center text-sm text-slate-500 underline">{t("login.forgot")}</button>}
      {step !== "identify" && <button type="button" onClick={() => { setStep("identify"); setPassword(""); setPin(""); }} className="block w-full text-center text-sm text-slate-500 underline">{t("common.back")}</button>}
    </form>
  );
}
