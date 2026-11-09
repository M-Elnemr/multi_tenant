"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Empty, ErrorText, Field, Input, Skeleton, StatusBadge } from "@/components/ui";
import { Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { SaveBar, SettingsCard, ToggleRow } from "./kit";
import { money } from "@/lib/format";
import { PointsWallet } from "./points-wallet";

type Overview = {
  settings: { enabled: boolean; confirmAfterHours: number; reminderEnabled: boolean; reminderAfterHours: number; brandName?: string | null};
  balanceMinor: number; priceMinor: number;
  topups: { id: string; amountMinor: number; status: string; adminNote?: string; createdAt: string }[];
};
type Msg = { kind: string; status: string; costMinor: number; error?: string; sentAt?: string; createdAt: string; orderNumber: string };

/** Automatic WhatsApp order confirmation: settings, what is still missing, prepaid credit and the log of sent messages. */
export function AiWhatsApp({ write }: { write: boolean }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<Overview>("store/ai/whatsapp");
  const log = useApi<Msg[]>("store/ai/whatsapp/messages");
  const [edit, setEdit] = useState<Partial<Overview["settings"]>>({});
  const f = data ? { ...data.settings, ...edit } : null;

  const save = useAction(async () => {
    if (!f) return;
    await api("store/ai/whatsapp", { method: "PUT", body: { enabled: f.enabled, confirmAfterHours: f.confirmAfterHours, reminderEnabled: f.reminderEnabled, reminderAfterHours: f.reminderAfterHours, brandName: f.brandName ?? "" } });
    setEdit({}); await reload(); toast(t("ai.saved"));
  });
  if (!data || !f) return <div className="max-w-3xl space-y-4"><Skeleton className="h-24 w-full !rounded-[18px]" /><Skeleton className="h-48 w-full !rounded-[18px]" /><Skeleton className="h-64 w-full !rounded-[18px]" /></div>;
  const dirty = Object.keys(edit).length > 0;

  return (
    <div className="max-w-3xl space-y-5">
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }}>
        <fieldset disabled={!write} className="disabled:opacity-80">
          <SettingsCard icon="settings" title={t("nav.aiWhatsapp")}>
            <ToggleRow title={t("wa.enable")} control={<Switch checked={f.enabled} onChange={(v) => setEdit({ ...edit, enabled: v })} disabled={!write} label={t("wa.enable")} />} />
            <div className="grid gap-4 border-t border-line py-4 sm:grid-cols-2">
              <Field label={t("wa.firstAfter")} hint={t("wa.firstAfterHint")}><Input type="number" min={1} max={72} value={f.confirmAfterHours} onChange={(e) => setEdit({ ...edit, confirmAfterHours: Math.max(1, Math.min(72, Number(e.target.value) || 1)) })} /></Field>
              <Field label={t("wa.brand")} hint={t("wa.brandHint")}><Input maxLength={40} value={f.brandName ?? ""} onChange={(e) => setEdit({ ...edit, brandName: e.target.value })} /></Field>
            </div>
            <ToggleRow title={t("wa.reminder")} control={<Switch checked={f.reminderEnabled} onChange={(v) => setEdit({ ...edit, reminderEnabled: v })} disabled={!write} label={t("wa.reminder")} />} />
            {f.reminderEnabled && <div className="pt-4"><Field label={t("wa.reminderAfter")} hint={t("wa.reminderAfterHint")}><Input className="sm:max-w-xs" type="number" min={1} max={72} value={f.reminderAfterHours} onChange={(e) => setEdit({ ...edit, reminderAfterHours: Math.max(1, Math.min(72, Number(e.target.value) || 1)) })} /></Field></div>}
          </SettingsCard>
        </fieldset>
        <div className="mt-3"><ErrorText error={save.error} /></div>
        {write && <SaveBar dirty={dirty} saving={save.loading} onDiscard={() => setEdit({})} />}
      </form>

      <PointsWallet write={write} />

      <SettingsCard icon="list" title={t("wa.log")}>
        {log.data?.length === 0 && <Empty compact icon="list">{t("wa.noLog")}</Empty>}
        <ul className="divide-y divide-line">{log.data?.map((m, i) => <li key={i} className="flex flex-wrap items-center justify-between gap-2 py-2.5 text-[13px] first:pt-0 last:pb-0"><span><span className="font-mono text-xs" dir="ltr">{m.orderNumber}</span> · {t(`wa.kind.${m.kind}`)}</span><span className="flex flex-wrap items-center gap-2 text-xs"><StatusBadge status={m.status} />{m.costMinor > 0 && <span className="tnum text-muted">{money(m.costMinor, "EGP", locale)}</span>}{m.error && <span className="text-red-600">{m.error}</span>}</span></li>)}</ul>
      </SettingsCard>
    </div>
  );
}
