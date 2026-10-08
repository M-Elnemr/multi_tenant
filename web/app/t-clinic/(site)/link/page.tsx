"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Button, ErrorText, Field, Input } from "@/components/ui";
import { IdentifierInput } from "@/components/inputs";

/** An existing account claims its patient file with its password and the one-time code the clinic handed over. */
export default function LinkRecord() {
  const t = useT();
  const router = useRouter();
  const [f, setF] = useState({ identifier: "", password: "", patientCode: "", pin: "" });
  const link = useAction(async () => { await api("clinic/portal/link", { body: f }); router.replace("/portal"); router.refresh(); });
  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-1 text-2xl font-semibold">{t("link.title")}</h1>
      <p className="mb-6 text-sm text-slate-500">{t("link.explain")}</p>
      <form onSubmit={(e) => { e.preventDefault(); void link.run(); }} className="space-y-4 rounded-2xl border bg-white p-6">
        <Field label={t("login.identifier")}><IdentifierInput value={f.identifier} onValue={(identifier) => setF({ ...f, identifier })} required /></Field>
        <Field label={t("login.password")}><Input type="password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required /></Field>
        <Field label={t("link.code")}><Input value={f.patientCode} onChange={(e) => setF({ ...f, patientCode: e.target.value.toUpperCase() })} dir="ltr" placeholder="PAT-XXXX-XXXX" required /></Field>
        <Field label={t("link.pin")}><Input value={f.pin} onChange={(e) => setF({ ...f, pin: e.target.value })} dir="ltr" inputMode="numeric" required /></Field>
        <ErrorText error={link.error} />
        <Button type="submit" loading={link.loading} className="w-full">{t("link.submit")}</Button>
      </form>
    </div>
  );
}
