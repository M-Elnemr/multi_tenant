"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { api } from "@/lib/client";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { useToast } from "@/components/toast";
import { Empty, ErrorText, PageHeader, Pager, StatusBadge, Toolbar } from "@/components/ui";
import { useNavTitle } from "@/components/dashboard/section";
import { CardSkeletons, errText, Stars } from "@/components/dashboard/marketing/kit";
import { dateTime } from "@/lib/format";

type R = { id: string; rating: number; reviewText?: string; status: string; createdAt: string; productName: string };
const STATUSES = ["PENDING", "APPROVED", "REJECTED", "HIDDEN"] as const;

/** Product reviews waiting for the shop's decision; approved ones show on the shop. */
export default function Reviews() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const reduce = useReducedMotion();
  const title = useNavTitle(t("nav.reviews"));
  const [status, setStatus] = useState<string>("PENDING");
  const [range, setRange] = useState<DateRange>({});
  const rq = (b: string) => { const qs = rangeQuery(new URLSearchParams(), range).toString(); return qs ? `${b}${b.includes("?") ? "&" : "?"}${qs}` : b; };
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<R>>(rq(`store/reviews?page=${page}&status=${status}`));
  const [busy, setBusy] = useState<string | null>(null);
  const moderate = async (id: string, s: string) => {
    setBusy(id + s);
    try {
      await api(`store/reviews/${id}/status`, { body: { status: s } });
      toast(s === "APPROVED" ? t("rvx.approved") : t("rvx.rejected"));
      await reload();
    } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); } finally { setBusy(null); }
  };
  return (
    <>
      <PageHeader eyebrow={t("navgrp.service")} title={title} subtitle={t("rvx.subtitle")} />
      <Toolbar>
        <div role="radiogroup" className="chip-row -mx-1 flex min-w-0 flex-1 gap-1.5 overflow-x-auto px-1 [scrollbar-width:none]">
          {STATUSES.map((s) => (
            <button key={s} type="button" role="radio" aria-checked={status === s} onClick={() => { setStatus(s); setPage(1); }} className={`chip ${status === s ? "chip-on" : ""}`}>
              {t(`status.${s}`)}{status === s && data?.meta && <span className="rounded-full bg-white/20 px-1.5 text-[11px] tnum">{data.meta.total}</span>}
            </button>
          ))}
        </div>
        <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} />
      </Toolbar>
      <ErrorText error={error} />
      {loading && !data ? <CardSkeletons n={4} className="grid gap-3 lg:grid-cols-2" h="h-36" /> : data?.data.length === 0 ? (
        <Empty icon="star" title={t("reviews.none")}>{status === "PENDING" ? t("rvx.emptyPending") : t("rvx.emptyOther")}</Empty>
      ) : (
        <ul className="grid gap-3 lg:grid-cols-2">
          <AnimatePresence initial={false}>
            {data?.data.map((r) => (
              <motion.li key={r.id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, scale: 0.98 }} transition={{ duration: 0.2 }}
                className="flex flex-col rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate text-[14px] font-semibold text-ink">{r.productName}</p>
                    <div className="mt-1 flex items-center gap-2"><Stars n={r.rating} /><span className="text-[12px] font-semibold tnum text-muted">{r.rating}/5</span></div>
                  </div>
                  <StatusBadge status={r.status} />
                </div>
                {r.reviewText ? <p className="mt-3 flex-1 whitespace-pre-wrap rounded-[12px] bg-canvas p-3 text-[13.5px] leading-relaxed text-ink-2 ring-1 ring-line">{r.reviewText}</p> : <p className="mt-3 flex-1 text-[13px] italic text-faint">{t("rvx.noText")}</p>}
                <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
                  <span className="flex items-center gap-1.5 text-[12px] text-faint"><Icon name="clock" className="h-3.5 w-3.5" />{dateTime(r.createdAt, locale)}</span>
                  <span className="flex gap-2">
                    {r.status !== "REJECTED" && (
                      <button type="button" disabled={!!busy} onClick={() => void moderate(r.id, "REJECTED")} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-3 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:bg-surface-2 disabled:opacity-50">
                        <Icon name="xcircle" className="h-4 w-4" />{t("reviews.reject")}
                      </button>
                    )}
                    {r.status !== "APPROVED" && (
                      <button type="button" disabled={!!busy} onClick={() => void moderate(r.id, "APPROVED")} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-ink px-3 text-[13px] font-semibold text-surface transition hover:opacity-90 disabled:opacity-50">
                        <Icon name="check" className="h-4 w-4" />{t("reviews.approve")}
                      </button>
                    )}
                  </span>
                </div>
              </motion.li>
            ))}
          </AnimatePresence>
        </ul>
      )}
      <Pager meta={data?.meta} onPage={setPage} />
    </>
  );
}
