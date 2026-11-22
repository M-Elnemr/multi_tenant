"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Empty, ErrorText, Input, Loading, PageHeader, StatusBadge, Tabs } from "@/components/ui";
import { AdminSearch } from "@/components/admin/kit";
import { dateTime, money } from "@/lib/format";

type Topup = { id: string; tenantId: string; tenantName: string; amountMinor: number; phone?: string; note?: string; status: string; adminNote?: string; createdAt: string; balanceMinor: number };
type Balance = { tenantId: string; tenantName: string; balanceMinor: number };

function Row({ r, onDone }: { r: Topup; onDone: () => void }) {
  const { t, locale } = useI18n();
  const [note, setNote] = useState("");
  const act = useAction(async (approve: boolean) => { await api(`platform/wallet/topups/${r.id}`, { body: { approve, adminNote: note || undefined } }); onDone(); });
  return (
    <div className={`space-y-2 rounded-[14px] bg-surface p-4 shadow-card ${r.status === "PENDING" ? "ring-2 ring-amber-400/60" : ""}`}>
      <div className="flex flex-wrap items-center justify-between gap-2"><Link href={`/admin/tenants/${r.tenantId}`} className="font-bold text-ink hover:text-brand">{r.tenantName}</Link><StatusBadge status={r.status} /></div>
      <p className="tnum text-sm text-ink-2">{money(r.amountMinor, "EGP", locale)} · {t("wa.balanceNow")}: {money(r.balanceMinor, "EGP", locale)} · <span className="text-xs text-muted">{dateTime(r.createdAt, locale)}</span></p>
      {r.phone && <a className="text-sm font-semibold text-brand" dir="ltr" href={`tel:${r.phone}`}>{r.phone}</a>}
      {r.status === "PENDING" && (
        <div className="flex flex-wrap items-center gap-2">
          <Input className="max-w-xs" placeholder={t("admin.note")} value={note} onChange={(e) => setNote(e.target.value)} />
          <Button size="sm" onClick={() => void act.run(true)} loading={act.loading}>{t("wa.approve")}</Button>
          <Button size="sm" variant="ghost" onClick={() => void act.run(false)}>{t("wa.reject")}</Button>
        </div>
      )}
      <ErrorText error={act.error} />
    </div>
  );
}

export default function AdminWallet() {
  const { t, locale } = useI18n();
  const [status, setStatus] = useState("PENDING");
  const topups = useApi<Page<Topup>>(`platform/wallet/topups?status=${status}`);
  const [q, setQ] = useState("");
  const balances = useApi<Balance[]>(`platform/wallet/balances?q=${encodeURIComponent(q)}`);
  const [adj, setAdj] = useState({ tenantId: "", egp: 0, note: "" });
  const adjust = useAction(async () => { await api("platform/wallet/adjust", { body: { tenantId: adj.tenantId, amountMinor: Math.round(adj.egp * 100), note: adj.note || undefined } }); setAdj({ tenantId: "", egp: 0, note: "" }); await balances.reload(); });
  return (
    <>
      <PageHeader title={t("wa.adminTitle")} />
      <div className="mb-3"><Tabs value={status} onChange={setStatus} tabs={["PENDING", "APPROVED", "REJECTED"].map((s) => ({ key: s, label: t(`status.${s}`) }))} /></div>
      {topups.loading && !topups.data ? <Loading /> : topups.data?.data.length === 0 ? <Empty>{t("wa.noTopups")}</Empty> : <div className="space-y-3">{topups.data?.data.map((r) => <Row key={r.id} r={r} onDone={() => { void topups.reload(); void balances.reload(); }} />)}</div>}

      <h2 className="mb-2 mt-8 text-lg font-bold text-ink">{t("wa.balances")}</h2>
      <div className="mb-3 max-w-xs"><AdminSearch placeholder={t("common.search")} value={q} onChange={setQ} /></div>
      <ul className="mb-6 divide-y divide-line overflow-hidden rounded-[14px] bg-surface text-sm shadow-card">{balances.data?.map((b) => <li key={b.tenantId} className="flex items-center justify-between px-4 py-2.5"><button type="button" className="text-start font-medium text-ink-2 hover:text-brand" onClick={() => setAdj({ ...adj, tenantId: b.tenantId })}>{b.tenantName}</button><span className="tnum font-semibold text-ink">{money(b.balanceMinor, "EGP", locale)}</span></li>)}</ul>
      <form onSubmit={(e) => { e.preventDefault(); void adjust.run(); }} className="max-w-xl space-y-2 rounded-[14px] bg-surface p-4 shadow-card">
        <h3 className="font-semibold">{t("wa.adjust")}</h3>
        <Input placeholder="tenant id" dir="ltr" value={adj.tenantId} onChange={(e) => setAdj({ ...adj, tenantId: e.target.value })} />
        <Input type="number" placeholder={t("wa.adjustAmount")} value={adj.egp} onChange={(e) => setAdj({ ...adj, egp: Number(e.target.value) })} />
        <Input placeholder={t("admin.note")} value={adj.note} onChange={(e) => setAdj({ ...adj, note: e.target.value })} />
        <ErrorText error={adjust.error} />
        <Button type="submit" loading={adjust.loading} disabled={!adj.tenantId || !adj.egp}>{t("wa.adjustBtn")}</Button>
      </form>
    </>
  );
}
