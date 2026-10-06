"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Empty, ErrorText, Loading, PageHeader, Pager, Select, StatusBadge } from "@/components/ui";
import { dateTime } from "@/lib/format";

type R = { id: string; rating: number; reviewText?: string; status: string; createdAt: string; productName: string };

export default function Reviews() {
  const { t, locale } = useI18n();
  const [status, setStatus] = useState("PENDING");
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<R>>(`store/reviews?page=${page}&status=${status}`);
  const moderate = useAction(async (id: string, s: string) => { await api(`store/reviews/${id}/status`, { body: { status: s } }); await reload(); });
  return (
    <>
      <PageHeader title={t("nav.reviews")} actions={<Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>{["PENDING", "APPROVED", "REJECTED", "HIDDEN"].map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}</Select>} />
      <ErrorText error={error ?? moderate.error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("reviews.none")}</Empty> : (
        <ul className="space-y-3">{data?.data.map((r) => (
          <li key={r.id} className="rounded-xl border bg-white p-4 text-sm">
            <div className="flex items-start justify-between gap-3">
              <div><p className="font-medium">{r.productName} · <span className="text-amber-600">{"★".repeat(r.rating)}</span></p><p className="mt-1 text-slate-700">{r.reviewText}</p><p className="mt-1 text-xs text-slate-400">{dateTime(r.createdAt, locale)}</p></div>
              <div className="flex shrink-0 gap-2"><StatusBadge status={r.status} />{r.status !== "APPROVED" && <Button size="sm" onClick={() => moderate.run(r.id, "APPROVED")}>{t("reviews.approve")}</Button>}{r.status !== "REJECTED" && <Button size="sm" variant="ghost" onClick={() => moderate.run(r.id, "REJECTED")}>{t("reviews.reject")}</Button>}</div>
            </div>
          </li>
        ))}</ul>
      )}
      <Pager meta={data?.meta} onPage={setPage} />
    </>
  );
}
