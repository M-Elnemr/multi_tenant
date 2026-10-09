"use client";

import Link from "next/link";
import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Loading, Pager } from "@/components/ui";
import { dateTime, money } from "@/lib/format";

type Order = { id: string; orderNumber: string; status: string; paymentStatus: string; totalMinor: number; currency: string; createdAt: string };
const TONE: Record<string, string> = { REQUESTED: "bg-amber-100 text-amber-800", PREPARING: "bg-sky-100 text-sky-800", SHIPPED: "bg-indigo-100 text-indigo-800", ARRIVED: "bg-emerald-100 text-emerald-800", RETURNED: "bg-slate-200 text-slate-700", CANCELLED: "bg-red-100 text-red-700" };

export default function Orders() {
  const { t, locale } = useI18n();
  const [page, setPage] = useState(1);
  const { data, loading, error } = useApi<Page<Order>>(`shop/orders?page=${page}`);
  return (
    <>
      <h1 className="mb-6 text-3xl font-extrabold">{t("orders.mine")}</h1>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? (
        <div className="s-card grid place-items-center gap-3 p-14 text-center"><span className="text-5xl">📦</span><p className="font-extrabold">{t("orders.none")}</p><Link href="/products" className="s-btn">{t("shop.browse")}</Link></div>
      ) : (
        <>
          <ul className="space-y-3">
            {data?.data.map((o) => (
              <li key={o.id}>
                <Link href={`/orders/${o.id}`} className="s-card s-lift flex flex-wrap items-center justify-between gap-3 p-5">
                  <div><p className="font-mono text-lg font-extrabold" dir="ltr" style={{ color: "var(--brand)" }}>{o.orderNumber}</p><p className="text-sm text-[var(--s-mute)]">{dateTime(o.createdAt, locale)}</p></div>
                  <span className={`rounded-full px-3 py-1 text-xs font-extrabold ${TONE[o.status] ?? "bg-slate-100"}`}>{t(`status.${o.status}`)}</span>
                  <b className="text-lg">{money(o.totalMinor, o.currency, locale)}</b>
                </Link>
              </li>
            ))}
          </ul>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
