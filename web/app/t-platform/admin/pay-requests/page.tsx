"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { Button, Card, Empty, ErrorText, Input, Loading, PageHeader, Pager, Tabs, StatusBadge } from "@/components/ui";
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
    <Card className="space-y-2">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <Link href={`/admin/tenants/${r.tenantId}`} className="text-lg font-bold text-brand">{r.tenantName}</Link>
          <p className="text-xs text-slate-500" dir="ltr">{r.primaryHost} · {r.tenantType === "STORE" ? t("type.store") : t("type.clinic")}</p>
        </div>
        <div className="flex items-center gap-2"><StatusBadge status={r.tenantStatus} /><StatusBadge status={r.status} /></div>
      </div>
      <p className="text-sm">{t("admin.requestedPlan")}: <b>{planLabel(t, r.planCode)}</b> · {money(r.amountMinor, r.currency, locale)} · <span className="text-slate-500">{dateTime(r.createdAt, locale)}</span></p>
      <p className="flex flex-wrap items-center gap-3 text-sm" dir="ltr">
        <a className="font-semibold text-brand" href={`tel:${r.phone}`}>📞 {r.phone}</a>
        <a className="text-emerald-700 underline" target="_blank" rel="noopener noreferrer" href={whatsappLink(r.phone, `${r.tenantName}`)}>{t("admin.whatsapp")}</a>
        {r.requesterEmail && <a className="text-slate-600" href={`mailto:${r.requesterEmail}`}>{r.requesterEmail}</a>}
      </p>
      {r.note && <p className="text-sm text-slate-600">“{r.note}”</p>}
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
    </Card>
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
