"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Button, ErrorText, Field, Input } from "@/components/ui";

export default function ShopRegister() {
  const t = useT();
  const router = useRouter();
  const [f, setF] = useState({ firstName: "", lastName: "", phone: "", email: "", password: "" });
  const reg = useAction(async () => {
    await api("shop/customers/register", { body: { ...f, email: f.email || undefined } });
    router.replace("/");
    router.refresh();
  });
  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-6 text-2xl font-semibold">{t("login.createAccount")}</h1>
      <form onSubmit={(e) => { e.preventDefault(); void reg.run(); }} className="space-y-4 rounded-2xl border border-slate-200 bg-white p-6">
        <div className="grid grid-cols-2 gap-3">
          <Field label={t("register.firstName")}><Input value={f.firstName} onChange={(e) => setF({ ...f, firstName: e.target.value })} required /></Field>
          <Field label={t("register.lastName")}><Input value={f.lastName} onChange={(e) => setF({ ...f, lastName: e.target.value })} /></Field>
        </div>
        <Field label={t("register.phone")}><Input value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} dir="ltr" inputMode="tel" required /></Field>
        <Field label={t("register.email")}><Input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} dir="ltr" /></Field>
        <Field label={t("login.password")} hint={t("login.passwordRule")}><Input type="password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} minLength={8} required /></Field>
        <ErrorText error={reg.error} />
        <Button type="submit" loading={reg.loading} className="w-full">{t("login.createAccount")}</Button>
      </form>
      <p className="mt-4 text-center text-sm text-slate-600">{t("login.haveAccount")} <Link href="/login" className="font-medium text-brand underline">{t("nav.login")}</Link></p>
    </div>
  );
}
