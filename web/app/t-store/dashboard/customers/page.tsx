"use client";

import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Card, ErrorText, Input, Loading, Modal, PageHeader, Pager, StatusBadge, Table, Td } from "@/components/ui";
import { Icon } from "@/components/icons";
import { FlaggedPhones } from "@/components/dashboard/flagged-phones";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { dateOnly, dateTime, money } from "@/lib/format";

type C = {
  id: string; customerNumber: string; name: string; phone?: string; email?: string; hasAccount: boolean; createdAt: string; ordersCount: number; totalSpentMinor: number; lastOrderAt?: string;
  address?: { addressLine1?: string; city?: string; district?: string } | null;
};
type O = { id: string; orderNumber: string; status: string; totalMinor: number; currency: string; createdAt: string };

/** The shop's clients. Read-only: they appear when someone orders, and a shop can contact them but never edit or add them. */
export default function Customers() {
  const { t, locale, currency } = useI18n();
  const [page, setPage] = useState(1);
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<C | null>(null);
  const { data, loading, error } = useApi<Page<C>>(`store/customers?page=${page}&q=${encodeURIComponent(q)}`);
  const orders = useApi<O[]>(open ? `store/customers/${open.id}/orders` : null);
  const call = (c: C) => c.phone && (
    <a href={`tel:${c.phone}`} onClick={(e) => e.stopPropagation()} aria-label={t("customers.call")} title={t("customers.call")}
      className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-brand text-white transition hover:brightness-110"><Icon name="phone" className="h-4 w-4" /></a>
  );
  return (
    <>
      <PageHeader title={t("nav.customers")} subtitle={t("customers.readOnly")} />
      <FlaggedPhones />
      <div className="mb-4 max-w-md"><Input placeholder={t("customers.search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} /></div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("orders.customer"), t("staff.phone"), t("customers.orders"), t("customers.spent"), t("customers.lastOrder"), ""]}>
            {data?.data.map((c) => (
              <tr key={c.id} className="cursor-pointer hover:bg-slate-50" onClick={() => setOpen(c)}>
                <Td>{c.name} {c.hasAccount && <Badge tone="blue">Google</Badge>}<div className="font-mono text-xs text-slate-500">{c.customerNumber}</div></Td>
                <Td><span dir="ltr">{c.phone}</span></Td>
                <Td>{c.ordersCount}</Td>
                <Td>{money(c.totalSpentMinor, currency, locale)}</Td>
                <Td>{c.lastOrderAt ? dateOnly(c.lastOrderAt, locale) : "-"}</Td>
                <Td><span className="inline-flex items-center gap-2">{call(c)}<WhatsAppButton phone={c.phone} message={t("customers.waHello", { name: c.name })} size={32} /></span></Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      <Modal open={!!open} onClose={() => setOpen(null)} title={open?.name ?? ""}>
        {open && (
          <div className="space-y-4 text-sm">
            <Card className="space-y-1">
              <p dir="ltr" className="font-medium">{open.phone}</p>
              {open.email && <p dir="ltr" className="text-slate-500">{open.email}</p>}
              {open.address?.addressLine1 && <p className="text-slate-600">{open.address.addressLine1}, {open.address.city}</p>}
              <p className="text-slate-500">{t("customers.since")} {dateOnly(open.createdAt, locale)}</p>
              <div className="flex items-center gap-2 pt-2">{call(open)}<WhatsAppButton phone={open.phone} message={t("customers.waHello", { name: open.name })} size={32} /></div>
            </Card>
            <div>
              <h3 className="mb-2 font-medium">{t("nav.orders")}</h3>
              {orders.loading && !orders.data ? <Loading /> : <ul className="space-y-1.5">{orders.data?.map((o) => <li key={o.id} className="flex items-center justify-between gap-2 rounded-lg border p-2"><span className="font-mono text-xs">{o.orderNumber}</span><StatusBadge status={o.status} /><b>{money(o.totalMinor, o.currency, locale)}</b><span className="text-xs text-slate-500">{dateTime(o.createdAt, locale)}</span></li>)}</ul>}
            </div>
          </div>
        )}
      </Modal>
    </>
  );
}
