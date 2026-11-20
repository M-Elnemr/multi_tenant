"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { useToast } from "@/components/toast";
import { Alert, Button, ErrorText, Textarea } from "@/components/ui";
import { SettingsCard } from "./kit";

type Setting = { mode: "AI" | "MESSAGE" | "SILENT"; message?: string | null; defaultMessage: string };
const MODES = ["AI", "MESSAGE", "SILENT"] as const;

/** What the assistant does when a customer is genuinely upset: calm them itself, send one fixed message and hand over, or stay silent and hand over. */
export function AiFrustration({ write }: { write: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<Setting>("store/ai/frustration");
  const [mode, setMode] = useState<Setting["mode"] | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const m = mode ?? data?.mode ?? "AI";
  const msg = message ?? data?.message ?? "";
  const dirty = !!data && (m !== data.mode || (m === "MESSAGE" && msg !== (data.message ?? "")));
  const save = useAction(async () => { await api("store/ai/frustration", { method: "PUT", body: { mode: m, message: m === "MESSAGE" ? msg : data?.message ?? null } }); toast(t("common.saved")); setMode(null); setMessage(null); await reload(); });
  if (!data) return <div className="skeleton h-48 rounded-[18px]" />;
  return (
    <SettingsCard tone="plain" icon="alert" title={t("fru.title")} description={t("fru.sub")}>
      <div className="space-y-3 px-5 pb-5">
        <div role="radiogroup" aria-label={t("fru.title")} className="grid gap-2">
          {MODES.map((k) => (
            <label key={k} className={`flex cursor-pointer items-start gap-3 rounded-[14px] p-3.5 ring-1 transition ${m === k ? "bg-brand-soft/40 ring-2 ring-brand" : "ring-line hover:ring-line-strong"} ${write ? "" : "cursor-not-allowed opacity-70"}`}>
              <input type="radio" name="frustration-mode" className="mt-1 h-4 w-4" checked={m === k} disabled={!write} onChange={() => setMode(k)} />
              <span className="min-w-0"><span className="block text-[13.5px] font-semibold text-ink">{t(`fru.${k}.title`)}</span><span className="block text-[12.5px] leading-relaxed text-muted">{t(`fru.${k}.hint`)}</span></span>
            </label>
          ))}
        </div>
        {m === "MESSAGE" && (
          <div className="space-y-1.5">
            <label className="block text-[13px] font-semibold text-ink-2" htmlFor="fru-msg">{t("fru.message")}</label>
            <Textarea id="fru-msg" rows={3} maxLength={600} disabled={!write} value={msg} placeholder={data.defaultMessage} onChange={(e) => setMessage(e.target.value)} />
            <p className="text-[12px] text-muted">{t("fru.messageHint")}</p>
            {!msg.trim() && <p className="rounded-[10px] bg-canvas p-2.5 text-[12.5px] text-ink-2 ring-1 ring-line"><span className="font-semibold">{t("fru.default")}: </span>{data.defaultMessage}</p>}
          </div>
        )}
        {m !== "AI" && <Alert tone="blue">{t("fru.note")}</Alert>}
        <ErrorText error={save.error} />
        {write && <Button variant="ink" loading={save.loading} disabled={!dirty} onClick={() => void save.run()}>{t("common.save")}</Button>}
      </div>
    </SettingsCard>
  );
}
