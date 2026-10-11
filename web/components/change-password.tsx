"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "./hooks";
import { useT } from "./i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input } from "./ui";

/** The account owner changes their own password (a business that set the first one never needs to know the new one). */
export function ChangePasswordCard({ forced = false, onChanged }: { forced?: boolean; onChanged?: () => void }) {
  const t = useT();
  const [cur, setCur] = useState("");
  const [next, setNext] = useState("");
  const [done, setDone] = useState(false);
  const change = useAction(async () => {
    await api("auth/change-password", { body: { currentPassword: cur, newPassword: next } });
    setCur(""); setNext(""); setDone(true);
    window.dispatchEvent(new Event("auth:changed"));
    onChanged?.();
    // A temporary password was just replaced: reload into the portal so no stale "must change" state survives (it used to bounce back here).
    if (forced) window.location.assign("/portal");
  });
  return (
    <Card className="space-y-3">
      <h2 className="font-medium">{t("account.changePassword")}</h2>
      {forced && <Alert tone="blue">{t("account.mustChange")}</Alert>}
      <form onSubmit={(e) => { e.preventDefault(); setDone(false); void change.run(); }} className="space-y-3">
        <Field label={t("account.currentPassword")}><Input type="password" value={cur} onChange={(e) => setCur(e.target.value)} autoComplete="current-password" required /></Field>
        <Field label={t("login.newPassword")} hint={t("login.passwordRule")}><Input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" minLength={8} required /></Field>
        <ErrorText error={change.error} />
        {done && <Alert tone="green">{t("account.passwordChanged")}</Alert>}
        <Button type="submit" loading={change.loading}>{t("common.save")}</Button>
      </form>
    </Card>
  );
}
