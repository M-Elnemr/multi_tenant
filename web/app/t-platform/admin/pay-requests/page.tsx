"use client";

import { localPhone } from "@/lib/phone";
import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { Avatar, Button, Empty, ErrorText, Input, Loading, PageHeader, Pager, Tabs, StatusBadge } from "@/components/ui";
import { Icon } from "@/components/icons";
import { dateTime, money } from "@/lib/format";
import { whatsappLink } from "@/lib/whatsapp";

type Req = {
  id: string; tenantId: string; planCode: string; amountMinor: number; currency: string; phone: string; note?: string; status: string; adminNote?: string; createdAt: string;
  tenantName: string; slug: string; tenantType: string; tenantStatus: string; primaryHost?: string; requesterName?: string; requesterEmail?: string;
};

function Row({ r, onDone }: { r: Req; onDone: () => void }) {
  const { t, locale } = useI18n();
  const [note, setNote] = useState(r.adminNote ?? "");
  const act = useAction(async (status: string) => { await api(`platform/pay-requests/${r.id}`, { body: { status, adminNote: note || undefined, activate: true } }); onDone(); });
  const open = r.status === "NEW" || r.status === "CONTACTED";
  return (
    <div className={`space-y-3 rounded-2xl bg-white p-5 shadow-card ${r.status === "NEW" ? "ring-2 ring-amber-300/70" : ""}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <Link href={`/admin/tenants/${r.tenantId}`} className="flex min-w-0 items-center gap-3">
          <Avatar name={r.tenantName} className="h-11 w-11 text-base" />
          <div className="min-w-0"><p className="truncate text-lg font-bold text-ink">{r.tenantName}</p><p className="truncate text-xs text-slate-500" dir="ltr">{r.primaryHost} · {r.tenantType === "STORE" ? t("type.store") : t("type.clinic")}</p></div>
        </Link>
        <div className="flex items-center gap-2"><StatusBadge status={r.tenantStatus} /><StatusBadge status={r.status} /></div>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-xl bg-slate-50 p-3"><p className="text-xs text-slate-500">{t("admin.requestedPlan")}</p><p className="font-bold">{planLabel(t, r.planCode)}</p><p className="text-sm text-slate-600">{money(r.amountMinor, r.currency, locale)} · <span className="text-xs text-slate-400">{dateTime(r.createdAt, locale)}</span></p></div>
        <div className="flex items-center justify-between gap-3 rounded-xl bg-slate-50 p-3">
          <div className="min-w-0"><p className="text-xs text-slate-500">{t("admin.contact")}</p><a className="text-lg font-extrabold text-brand" dir="ltr" href={`tel:${r.phone}`}>{localPhone(r.phone)}</a></div>
          <div className="flex shrink-0 gap-2">
            <a href={`tel:${r.phone}`} aria-label={t("admin.call")} className="flex h-10 w-10 items-center justify-center rounded-full bg-brand text-white"><Icon name="phone" className="h-5 w-5" /></a>
            <a href={whatsappLink(r.phone, r.tenantName)} target="_blank" rel="noopener noreferrer" aria-label={t("admin.whatsapp")} className="flex h-10 w-10 items-center justify-center rounded-full bg-emerald-500 text-white"><Icon name="chat" className="h-5 w-5" /></a>
          </div>
        </div>
      </div>
      {r.note && <p className="rounded-lg bg-sky-50 p-3 text-sm text-sky-900">“{r.note}”</p>}
      {open && (
        <div className="space-y-2 border-t border-slate-100 pt-3">
          <Input placeholder={t("admin.note")} value={note} onChange={(e) => setNote(e.target.value)} />
          <ErrorText error={act.error} />
          <div className="flex flex-wrap gap-2">
            {r.status === "NEW" && <Button variant="secondary" loading={act.loading} onClick={() => act.run("CONTACTED")}>{t("admin.markContacted")}</Button>}
            <Button loading={act.loading} onClick={() => act.run("PAID")}>{t("admin.markPaid")}</Button>
            <Button variant="ghost" loading={act.loading} onClick={() => act.run("REJECTED")}>{t("admin.reject")}</Button>
          </div>
        </div>
      )}
      {!open && r.adminNote && <p className="text-xs text-slate-500">{t("admin.note")}: {r.adminNote}</p>}
    </div>
  );
}

export default function PayRequests() {
  const { t } = useI18n();
  const [status, setStatus] = useState("NEW");
  const [page, setPage] = useState(1);
  const { data, error, loading, reload } = useApi<Page<Req>>(`platform/pay-requests?page=${page}&status=${status}`);
  return (
    <>
      <PageHeader title={t("admin.payRequests")} />
      <div className="mb-4"><Tabs value={status} onChange={(s) => { setStatus(s); setPage(1); }} tabs={[
        { key: "NEW", label: t("status.NEW") }, { key: "CONTACTED", label: t("status.CONTACTED") }, { key: "PAID", label: t("status.PAID") }, { key: "REJECTED", label: t("status.REJECTED") },
      ]} /></div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <div className="space-y-3">
          {data?.data.length === 0 && <Empty>{t("admin.noRequests")}</Empty>}
          {data?.data.map((r) => <Row key={r.id} r={r} onDone={() => void reload()} />)}
          <Pager meta={data?.meta} onPage={setPage} />
        </div>
      )}
    </>
  );
}
