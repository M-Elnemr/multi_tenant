"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Button, Card, ErrorText, Loading, PageHeader } from "@/components/ui";
import { ConnectForm } from "@/components/dashboard/channels/connect-form";
import { CountryChips, ProviderMark, type CatalogItem, type Country } from "@/components/dashboard/channels/common";

type Group = "MARKETPLACE" | "STORE_PLATFORM";

export default function ConnectChannel() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [country, setCountry] = useState<Country>("EG");
  const [group, setGroup] = useState<Group | "ALL">("ALL");
  const [open, setOpen] = useState<CatalogItem | null>(null);
  const catalog = useApi<CatalogItem[]>(`store/integrations/catalog?country=${country}`);
  const accounts = useApi<{ provider: string; country: string }[]>("store/integrations/accounts");
  const items = (catalog.data ?? []).filter((c) => (c.kind === "MARKETPLACE" || c.kind === "STORE_PLATFORM") && (group === "ALL" || c.kind === group));
  const have = (id: string) => (accounts.data ?? []).some((a) => a.provider === id && a.country === country);
  return (
    <>
      <PageHeader title={t("ch.connect")} subtitle={t("ch.connect.sub")} actions={<Link href="/dashboard/channels"><Button variant="secondary">{t("common.back")}</Button></Link>} />
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <CountryChips value={country} onChange={setCountry} />
        <div className="inline-flex gap-1 rounded-xl bg-slate-100/80 p-1 text-sm font-semibold">
          {(["ALL", "MARKETPLACE", "STORE_PLATFORM"] as const).map((g) => <button key={g} onClick={() => setGroup(g)} className={`rounded-lg px-3 py-1.5 transition ${g === group ? "bg-white text-brand-dark shadow-sm" : "text-slate-600"}`}>{t(`ch.group.${g}`)}</button>)}
        </div>
      </div>
      <ErrorText error={catalog.error} />
      {catalog.loading && !catalog.data ? <Loading /> : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {items.map((c) => (
            <Card key={c.id} hover className="flex flex-col gap-4">
              <div className="flex items-center gap-3">
                <ProviderMark provider={c.id} name={c.name} className="h-12 w-12" />
                <div className="min-w-0 flex-1"><p className="text-lg font-bold">{c.name}</p><p className="text-xs text-slate-500">{t(`ch.kind.${c.kind}`)}</p></div>
                {c.status === "BETA" && <Badge tone="amber">{t("ch.beta")}</Badge>}
              </div>
              <p className="flex-1 text-sm text-slate-600">{locale === "ar" ? c.helpAr : c.helpEn}</p>
              <div className="flex flex-wrap gap-1.5">{c.can.map((k) => <span key={k} className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium text-slate-600">{t(`ch.can.${k}`)}</span>)}</div>
              {c.status === "SOON" ? <Button disabled variant="secondary">{t("ch.soon")}</Button>
                : <Button variant={have(c.id) ? "secondary" : "primary"} onClick={() => setOpen(c)}>{have(c.id) ? t("ch.addAnother") : t("ch.connectBtn")}</Button>}
            </Card>
          ))}
        </div>
      )}
      {open && <ConnectForm item={open} country={country} onClose={() => setOpen(null)} onDone={(name) => router.replace(`/dashboard/channels?connected=${encodeURIComponent(name)}`)} />}
    </>
  );
}
