"use client";

import { localPhone } from "@/lib/phone";
import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { Avatar, Button, Empty, ErrorText, Input, Loading, PageHeader, Pager, Tabs, StatusBadge } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { ContactButtons, Fact, TypeTag } from "@/components/admin/kit";

type Req = {
  id: string; tenantId: string; planCode: string; amountMinor: number; timing?: string; creditMinor?: number; currency: string; phone: string; note?: string; status: string; adminNote?: string; createdAt: string;
  tenantName: string; slug: string; tenantType: string; tenantStatus: string; primaryHost?: string; requesterName?: string; requesterEmail?: string;
};

function Row({ r, onDone }: { r: Req; onDone: () => void }) {
  const { t, locale } = useI18n();
  const [note, setNote] = useState(r.adminNote ?? "");
  const act = useAction(async (status: string) => { await api(`platform/pay-requests/${r.id}`, { body: { status, adminNote: note || undefined, activate: true } }); onDone(); });
  const open = r.status === "NEW" || r.status === "CONTACTED";
  return (
    <div className={`space-y-3 rounded-[14px] bg-surface p-4 shadow-card sm:p-5 ${r.status === "NEW" ? "ring-2 ring-amber-400/60" : ""}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <Link href={`/admin/tenants/${r.tenantId}`} className="flex min-w-0 items-center gap-3">
          <Avatar name={r.tenantName} className="h-11 w-11 text-base" />
          <div className="min-w-0"><p className="truncate text-base font-bold text-ink">{r.tenantName}</p><p className="flex items-center gap-2 truncate text-xs text-muted"><span dir="ltr" className="truncate">{r.primaryHost}</span><TypeTag type={r.tenantType} /></p></div>
        </Link>
        <div className="flex items-center gap-2"><StatusBadge status={r.tenantStatus} /><StatusBadge status={r.status} /></div>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-[12px] bg-surface-2 p-3.5">
          <dl><Fact label={t("admin.requestedPlan")}>{planLabel(t, r.planCode)}</Fact></dl>
          <p className="tnum mt-1 text-sm text-ink-2">{money(r.amountMinor, r.currency, locale)} · <span className="text-xs text-muted">{dateTime(r.createdAt, locale)}</span></p>
          {r.timing === "NEXT_PERIOD" ? <p className="mt-1 text-xs font-semibold text-sky-700 dark:text-sky-300">{t("admin.nextPeriodNote")}</p> : (r.creditMinor ?? 0) > 0 && <p className="mt-1 text-xs font-semibold text-sky-700 dark:text-sky-300">{t("admin.upgradeNowNote", { credit: money(r.creditMinor ?? 0, r.currency, locale) })}</p>}
        </div>
        <div className="flex items-center justify-between gap-3 rounded-[12px] bg-surface-2 p-3.5">
          <div className="min-w-0"><p className="text-[11.5px] font-medium text-muted">{t("admin.contact")}</p><a className="tnum text-lg font-bold text-brand" dir="ltr" href={`tel:${r.phone}`}>{localPhone(r.phone)}</a></div>
          <ContactButtons phone={r.phone} name={r.tenantName} size="md" />
        </div>
      </div>
      {r.note && <p className="rounded-[10px] bg-sky-50 p-3 text-sm text-sky-900 dark:bg-sky-500/10 dark:text-sky-200">“{r.note}”</p>}
      {open && (
        <div className="space-y-2 border-t border-line pt-3">
          <Input placeholder={t("admin.note")} value={note} onChange={(e) => setNote(e.target.value)} />
          <ErrorText error={act.error} />
          <div className="flex flex-wrap gap-2">
            {r.status === "NEW" && <Button variant="secondary" loading={act.loading} onClick={() => act.run("CONTACTED")}>{t("admin.markContacted")}</Button>}
            {r.timing === "NEXT_PERIOD"
              ? <Button loading={act.loading} onClick={() => act.run("SCHEDULED")}>{t("admin.schedule")}</Button>
              : <Button loading={act.loading} onClick={() => act.run("PAID")}>{t("admin.markPaid")}</Button>}
            <Button variant="ghost" loading={act.loading} onClick={() => act.run("REJECTED")}>{t("admin.reject")}</Button>
          </div>
        </div>
      )}
      {!open && r.adminNote && <p className="text-xs text-muted">{t("admin.note")}: {r.adminNote}</p>}
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
        { key: "NEW", label: t("status.NEW") }, { key: "CONTACTED", label: t("status.CONTACTED") }, { key: "PAID", label: t("status.PAID") }, { key: "SCHEDULED", label: t("status.SCHEDULED") }, { key: "REJECTED", label: t("status.REJECTED") },
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
