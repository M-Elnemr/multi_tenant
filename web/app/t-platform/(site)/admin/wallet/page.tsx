"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Empty, ErrorText, Input, Loading, PageHeader, StatusBadge } from "@/components/ui";
import { dateTime, money } from "@/lib/format";

type Topup = { id: string; tenantId: string; tenantName: string; amountMinor: number; phone?: string; note?: string; status: string; adminNote?: string; createdAt: string; balanceMinor: number };
type Balance = { tenantId: string; tenantName: string; balanceMinor: number };

function Row({ r, onDone }: { r: Topup; onDone: () => void }) {
  const { t, locale } = useI18n();
  const [note, setNote] = useState("");
  const act = useAction(async (approve: boolean) => { await api(`platform/wallet/topups/${r.id}`, { body: { approve, adminNote: note || undefined } }); onDone(); });
  return (
    <div className={`space-y-2 rounded-xl bg-white p-4 shadow-card ${r.status === "PENDING" ? "ring-2 ring-amber-300/70" : ""}`}>
      <div className="flex flex-wrap items-center justify-between gap-2"><p className="font-bold">{r.tenantName}</p><StatusBadge status={r.status} /></div>
      <p className="text-sm">{money(r.amountMinor, "EGP", locale)} · {t("wa.balanceNow")}: {money(r.balanceMinor, "EGP", locale)} · <span className="text-xs text-slate-400">{dateTime(r.createdAt, locale)}</span></p>
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
      <div className="mb-3 flex gap-2">{["PENDING", "APPROVED", "REJECTED"].map((s) => <button key={s} onClick={() => setStatus(s)} className={`rounded-full border px-3 py-1.5 text-sm font-semibold ${status === s ? "border-brand bg-brand-soft text-brand" : ""}`}>{t(`status.${s}`)}</button>)}</div>
      {topups.loading && !topups.data ? <Loading /> : topups.data?.data.length === 0 ? <Empty>{t("wa.noTopups")}</Empty> : <div className="space-y-3">{topups.data?.data.map((r) => <Row key={r.id} r={r} onDone={() => { void topups.reload(); void balances.reload(); }} />)}</div>}

      <h2 className="mb-2 mt-8 text-lg font-bold">{t("wa.balances")}</h2>
      <Input className="mb-3 max-w-xs" placeholder={t("common.search")} value={q} onChange={(e) => setQ(e.target.value)} />
      <ul className="mb-4 space-y-1 text-sm">{balances.data?.map((b) => <li key={b.tenantId} className="flex items-center justify-between rounded-lg bg-white px-3 py-2 shadow-card"><button type="button" className="text-start font-semibold hover:text-brand" onClick={() => setAdj({ ...adj, tenantId: b.tenantId })}>{b.tenantName}</button><span>{money(b.balanceMinor, "EGP", locale)}</span></li>)}</ul>
      <form onSubmit={(e) => { e.preventDefault(); void adjust.run(); }} className="max-w-xl space-y-2 rounded-xl border bg-white p-4">
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
