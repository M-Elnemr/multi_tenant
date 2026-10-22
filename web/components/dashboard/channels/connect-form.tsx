"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input, Modal } from "@/components/ui";
import { ProviderMark, type CatalogItem, type Country } from "./common";

export function ConnectForm({ item, country, carrier, onClose, onDone }: { item: CatalogItem; country: Country; carrier?: boolean; onClose: () => void; onDone: (name: string) => void }) {
  const { t, locale } = useI18n();
  const [cred, setCred] = useState<Record<string, string>>({});
  const [label, setLabel] = useState("");
  const connect = useAction(async () => {
    const r = await api<{ name?: string }>(carrier ? "store/shipping/carriers" : "store/integrations/accounts", { body: { provider: item.id, country, label: label.trim() || undefined, credentials: cred } });
    onDone(r.name ?? item.name);
  });
  const oauth = useAction(async () => {
    const r = await api<{ url: string }>(`store/integrations/oauth/${item.id}/start`, { body: { country } });
    window.location.assign(r.url);
  });
  const missing = item.fields.some((f) => !f.optional && !(cred[f.key] ?? "").trim());
  return (
    <Modal open onClose={onClose} title={t("ch.connectTo", { name: item.name })} wide>
      <div className="space-y-5">
        <div className="flex items-center gap-3"><ProviderMark provider={item.id} name={item.name} className="h-12 w-12" /><p className="text-sm text-slate-600">{locale === "ar" ? item.helpAr : item.helpEn}</p></div>
        {item.status === "BETA" && <Alert tone="amber">{t("ch.betaNote")}</Alert>}
        {item.oauth && !carrier && (
          <div className="rounded-2xl bg-brand-soft p-4">
            <Button loading={oauth.loading} onClick={() => void oauth.run()}>{t("ch.loginWith", { name: item.name })}</Button>
            <p className="mt-2 text-xs text-slate-600">{t("ch.loginWith.hint")}</p>
            <ErrorText error={oauth.error} />
          </div>
        )}
        {item.oauth && !carrier && <p className="text-center text-xs font-semibold uppercase tracking-wider text-slate-400">{t("ch.orPaste")}</p>}
        <form onSubmit={(e) => { e.preventDefault(); void connect.run(); }} className="space-y-4">
          {item.fields.map((f) => (
            <Field key={f.key} label={`${locale === "ar" ? f.labelAr : f.labelEn}${f.optional ? ` (${t("ch.optional")})` : ""}`}>
              {f.multiline
                ? <textarea dir="ltr" rows={5} autoComplete="off" spellCheck={false} placeholder={f.placeholder} value={cred[f.key] ?? ""} onChange={(e) => setCred({ ...cred, [f.key]: e.target.value })} className="w-full rounded-xl border border-slate-300 px-3 py-2 font-mono text-xs focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20" />
                : <Input dir="ltr" type={f.secret ? "password" : "text"} autoComplete="off" placeholder={f.placeholder} value={cred[f.key] ?? ""} onChange={(e) => setCred({ ...cred, [f.key]: e.target.value })} />}
            </Field>
          ))}
          <Field label={`${t("ch.accountName")} (${t("ch.optional")})`}><Input value={label} onChange={(e) => setLabel(e.target.value)} /></Field>
          <p className="text-xs text-slate-500">🔒 {t("ch.secure")}</p>
          <ErrorText error={connect.error} />
          <div className="flex flex-wrap items-center justify-between gap-2">
            <a href={item.docsUrl} target="_blank" rel="noreferrer" className="text-sm font-semibold text-brand hover:underline">{t("ch.docs")} ↗</a>
            <div className="flex gap-2"><Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button type="submit" loading={connect.loading} disabled={missing}>{t("ch.connectBtn")}</Button></div>
          </div>
        </form>
      </div>
    </Modal>
  );
}
