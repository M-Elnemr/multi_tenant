"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Button, ErrorText, Loading, PageHeader, Pager, Select, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { whatsappLink } from "@/lib/whatsapp";

type Ret = { id: string; orderId: string; orderNumber: string; reason: string; details: string; status: string; ownerNote: string; createdAt: string; customerNameSnapshot: string; customerPhoneSnapshot: string; totalMinor: number; currency: string };
const TONE: Record<string, "amber" | "blue" | "red" | "green"> = { REQUESTED: "amber", APPROVED: "blue", REJECTED: "red", RECEIVED: "green" };

export default function Returns() {
  const { t, locale } = useI18n();
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<Ret>>(`store/returns?page=${page}&status=${status}`);
  const decide = useAction(async (id: string, s: string) => { await api(`store/returns/${id}/decision`, { body: { status: s } }); await reload(); });
  return (
    <>
      <PageHeader title={t("nav.returns")} actions={<Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}><option value="">{t("admin.allStatuses")}</option>{["REQUESTED", "APPROVED", "REJECTED", "RECEIVED"].map((s) => <option key={s} value={s}>{t(`return.${s}`)}</option>)}</Select>} />
      <ErrorText error={error ?? decide.error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <p className="rounded-2xl border border-dashed p-10 text-center text-slate-500">{t("returns.empty")}</p> : (
        <>
          <Table head={[t("orders.number"), t("orders.customer"), t("track.reason"), t("admin.status"), t("admin.created"), ""]}>
            {data?.data.map((r) => (
              <tr key={r.id}>
                <Td><Link href={`/dashboard/orders/${r.orderId}`} className="font-mono text-brand">{r.orderNumber}</Link><div className="text-xs text-slate-500">{money(r.totalMinor, r.currency, locale)}</div></Td>
                <Td>{r.customerNameSnapshot}<a href={whatsappLink(r.customerPhoneSnapshot)} target="_blank" rel="noopener noreferrer" dir="ltr" className="block text-xs text-emerald-700 underline">{r.customerPhoneSnapshot}</a></Td>
                <Td>{t(`reason.${r.reason}`)}{r.details && <div className="max-w-xs text-xs text-slate-500">{r.details}</div>}</Td>
                <Td><Badge tone={TONE[r.status]}>{t(`return.${r.status}`)}</Badge></Td>
                <Td>{dateTime(r.createdAt, locale)}</Td>
                <Td className="text-end"><div className="flex justify-end gap-1.5">
                  {r.status === "REQUESTED" && <><Button size="sm" onClick={() => decide.run(r.id, "APPROVED")}>{t("returns.approve")}</Button><Button size="sm" variant="secondary" onClick={() => decide.run(r.id, "REJECTED")}>{t("returns.reject")}</Button></>}
                  {r.status === "APPROVED" && <Button size="sm" onClick={() => decide.run(r.id, "RECEIVED")}>{t("returns.received")}</Button>}
                </div></Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
