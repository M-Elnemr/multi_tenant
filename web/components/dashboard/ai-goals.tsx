"use client";

import Link from "next/link";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Alert, ErrorText } from "@/components/ui";
import { SettingsCard, ToggleRow } from "./kit";

type Goals = { goals: string[]; available: string[]; services: number };

/** What the owner wants the assistant to do with the people it talks to: take product orders, collect them as customers (leads), take appointments for services. */
export function AiGoals({ write }: { write: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<Goals>("store/ai/goals");
  const save = useAction(async (goals: string[]) => { await api("store/ai/goals", { method: "PUT", body: { goals } }); toast(t("common.saved")); await reload(); });
  if (!data) return <div className="skeleton h-40 rounded-[18px]" />;
  const on = (g: string) => data.goals.includes(g);
  const flip = (g: string, v: boolean) => void save.run(v ? [...data.goals, g] : data.goals.filter((x) => x !== g));
  const row = (g: "ORDERS" | "LEADS" | "BOOKINGS", icon: "bag" | "users" | "calendar") => (
    <ToggleRow icon={icon} title={t(`aig.${g}.title`)} hint={t(`aig.${g}.hint`)} control={<Switch checked={on(g)} disabled={!write || save.loading} onChange={(v) => flip(g, v)} label={t(`aig.${g}.title`)} />} />
  );
  return (
    <SettingsCard tone="ai" icon="sparkles" title={t("aig.title")} description={t("aig.sub")}>
      <div className="space-y-1 px-5 pb-5">
        {row("ORDERS", "bag")}
        {row("LEADS", "users")}
        {row("BOOKINGS", "calendar")}
        {on("LEADS") && <Alert tone="blue">{t("aig.leadsNote")}</Alert>}
        {on("BOOKINGS") && data.services === 0 && <Alert tone="amber"><span>{t("aig.noServices")} </span><Link href="/dashboard/bookings?tab=services" className="font-semibold underline">{t("aig.addServices")}</Link></Alert>}
        {on("BOOKINGS") && data.services > 0 && <p className="pt-2 text-[12.5px] text-muted"><Link href="/dashboard/bookings" className="font-semibold text-brand hover:underline">{t("aig.manageBookings")}</Link></p>}
        {!on("ORDERS") && !on("LEADS") && !on("BOOKINGS") && <Alert tone="amber">{t("aig.allOff")}</Alert>}
        <ErrorText error={save.error} />
      </div>
    </SettingsCard>
  );
}
