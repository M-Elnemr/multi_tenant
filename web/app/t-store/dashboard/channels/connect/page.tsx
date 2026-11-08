"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Badge, Empty, ErrorText, PageHeader } from "@/components/ui";
import { ConnectForm } from "@/components/dashboard/channels/connect-form";
import { CountryChips, ProviderMark, type CatalogItem, type Country } from "@/components/dashboard/channels/common";
import { CardSkeletons, PillTabs } from "@/components/dashboard/marketing/kit";

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
      <PageHeader eyebrow={t("ch.title")} title={t("ch.connect")} subtitle={t("ch.connect.sub")}
        actions={<Link href="/dashboard/channels" className="inline-flex h-10 items-center gap-1.5 rounded-[11px] px-3.5 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-surface"><Icon name="arrow" className="h-4 w-4 rotate-180 rtl:rotate-0" />{t("common.back")}</Link>} />
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <CountryChips value={country} onChange={setCountry} />
        <PillTabs id="connect-group" value={group} onChange={setGroup} tabs={(["ALL", "MARKETPLACE", "STORE_PLATFORM"] as const).map((g) => ({ key: g, label: t(`ch.group.${g}`) }))} />
      </div>
      <ErrorText error={catalog.error} />
      {catalog.loading && !catalog.data ? <CardSkeletons n={6} className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" h="h-56" /> : items.length === 0 ? <Empty icon="globe" title={t("ch.none")} /> : (
        <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {items.map((c) => (
            <li key={c.id} className="hover-lift flex flex-col gap-4 rounded-[18px] bg-surface p-5 ring-1 ring-line">
              <div className="flex items-center gap-3">
                <ProviderMark provider={c.id} name={c.name} className="h-12 w-12" />
                <div className="min-w-0 flex-1"><p className="truncate font-display text-[16px] font-semibold text-ink">{c.name}</p><p className="text-[12px] text-muted">{t(`ch.kind.${c.kind}`)}</p></div>
                {c.status === "BETA" && <Badge tone="amber">{t("ch.beta")}</Badge>}
                {have(c.id) && <Badge tone="green">{t("ch.status.ACTIVE")}</Badge>}
              </div>
              <p className="flex-1 text-[13px] leading-relaxed text-muted">{locale === "ar" ? c.helpAr : c.helpEn}</p>
              <div className="flex flex-wrap gap-1.5">{c.can.map((k) => <span key={k} className="rounded-full bg-canvas px-2.5 py-0.5 text-[11.5px] font-medium text-ink-2 ring-1 ring-line">{t(`ch.can.${k}`)}</span>)}</div>
              {c.status === "SOON" ? <button type="button" disabled className="h-10 rounded-[10px] text-sm font-semibold text-muted ring-1 ring-line">{t("ch.soon")}</button>
                : <button type="button" onClick={() => setOpen(c)} className={`h-10 rounded-[10px] text-sm font-semibold transition ${have(c.id) ? "text-ink-2 ring-1 ring-line-strong hover:bg-surface-2" : "bg-ink text-surface hover:opacity-90"}`}>{have(c.id) ? t("ch.addAnother") : t("ch.connectBtn")}</button>}
            </li>
          ))}
        </ul>
      )}
      {open && <ConnectForm item={open} country={country} onClose={() => setOpen(null)} onDone={(name) => router.replace(`/dashboard/channels?connected=${encodeURIComponent(name)}`)} />}
    </>
  );
}
