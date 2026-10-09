"use client";

import Link from "next/link";
import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, ErrorText, Input, Loading, PageHeader, Pager, Select, StatusBadge, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { GOVERNORATES, governorateName } from "@/lib/governorates";

type Order = { id: string; orderNumber: string; status: string; paymentStatus: string; paymentMethod: string; totalMinor: number; currency: string; customerNameSnapshot: string; customerPhoneSnapshot: string; governorateCode?: string; confirmedAt?: string | null; courierName?: string; createdAt: string };
const STATUSES = ["REQUESTED", "PREPARING", "SHIPPED", "ARRIVED", "RETURNED", "CANCELLED"];

export default function StoreOrders() {
  const { t, locale } = useI18n();
  const [status, setStatus] = useState("");
  const [gov, setGov] = useState("");
  const [q, setQ] = useState("");
  const [unconfirmed, setUnconfirmed] = useState(false);
  const [page, setPage] = useState(1);
  const summary = useApi<{ unconfirmed: number }>("store/orders/summary");
  const qs = new URLSearchParams({ page: String(page) });
  if (status) qs.set("status", status);
  if (gov) qs.set("governorate", gov);
  if (q.trim()) qs.set("q", q.trim());
  if (unconfirmed) qs.set("unconfirmed", "true");
  const { data, loading, error } = useApi<Page<Order>>(`store/orders?${qs}`);
  const reset = () => setPage(1);
  const pending = summary.data?.unconfirmed ?? 0;
  return (
    <>
      <PageHeader title={t("nav.orders")} />
      {pending > 0 && (
        <button onClick={() => { setUnconfirmed(!unconfirmed); reset(); }} className={`mb-4 flex w-full items-center justify-between rounded-2xl border p-4 text-start text-sm font-semibold transition ${unconfirmed ? "border-amber-400 bg-amber-100" : "border-amber-200 bg-amber-50 hover:bg-amber-100"}`}>
          <span>📞 {t("confirm.banner", { n: pending })}</span><span className="text-amber-800">{unconfirmed ? t("filter.clear") : t("confirm.show")}</span>
        </button>
      )}
      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <Input placeholder={t("orders.searchHint")} value={q} onChange={(e) => { setQ(e.target.value); reset(); }} />
        <Select value={status} onChange={(e) => { setStatus(e.target.value); reset(); }}><option value="">{t("admin.allStatuses")}</option>{STATUSES.map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}</Select>
        <Select value={gov} onChange={(e) => { setGov(e.target.value); reset(); }}><option value="">{t("orders.allGovernorates")}</option>{GOVERNORATES.map((g) => <option key={g.code} value={g.code}>{locale === "ar" ? g.ar : g.en}</option>)}</Select>
      </div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("orders.number"), t("orders.customer"), t("checkout.governorate"), t("admin.status"), t("orders.payment"), t("checkout.total"), t("admin.created")]}>
            {data?.data.map((o) => (
              <tr key={o.id} className="hover:bg-slate-50">
                <Td><Link href={`/dashboard/orders/${o.id}`} className="font-mono text-brand">{o.orderNumber}</Link></Td>
                <Td>{o.customerNameSnapshot}<div dir="ltr" className="text-xs text-slate-500">{o.customerPhoneSnapshot}</div></Td>
                <Td>{governorateName(o.governorateCode, locale) || "—"}</Td>
                <Td><StatusBadge status={o.status} />{o.status === "REQUESTED" && !o.confirmedAt && <div className="mt-1"><Badge tone="amber">📞 {t("confirm.unconfirmed")}</Badge></div>}</Td>
                <Td><StatusBadge status={o.paymentStatus} /> <span className="text-xs text-slate-500">{t(`pay.${o.paymentMethod}`)}</span></Td>
                <Td>{money(o.totalMinor, o.currency, locale)}</Td><Td>{dateTime(o.createdAt, locale)}</Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
