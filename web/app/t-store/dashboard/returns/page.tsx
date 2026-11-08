"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { useNavTitle } from "@/components/dashboard/section";
import { ActiveChips, Bar, Cell, Chip, ChipRow, DataTable, Row, RowMenu } from "@/components/dashboard/sell/kit";
import { Badge, Button, Empty, ErrorText, PageHeader, Pager } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { whatsappLink } from "@/lib/whatsapp";

type Ret = { id: string; orderId: string; orderNumber: string; reason: string; details: string; status: string; ownerNote: string; createdAt: string; customerNameSnapshot: string; customerPhoneSnapshot: string; totalMinor: number; currency: string };
const TONE: Record<string, "amber" | "blue" | "red" | "green"> = { REQUESTED: "amber", APPROVED: "blue", REJECTED: "red", RECEIVED: "green" };
const STATUSES = ["REQUESTED", "APPROVED", "REJECTED", "RECEIVED"];

/** Return requests from customers: approve or reject each one, then mark it received when the goods come back. */
export default function Returns() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const title = useNavTitle(t("nav.returns"));
  const [status, setStatus] = useState("");
  const [range, setRange] = useState<DateRange>({});
  const [reject, setReject] = useState<Ret | null>(null);
  const [busy, setBusy] = useState(false);
  const rq = (b: string) => { const qs = rangeQuery(new URLSearchParams(), range).toString(); return qs ? `${b}${b.includes("?") ? "&" : "?"}${qs}` : b; };
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<Ret>>(rq(`store/returns?page=${page}&status=${status}`));
  const [err, setErr] = useState<unknown>(null);
  const decide = async (id: string, s: string) => {
    setBusy(true); setErr(null);
    try { await api(`store/returns/${id}/decision`, { body: { status: s } }); toast(t(`return.${s}`)); await reload(); } catch (e) { setErr(e); } finally { setBusy(false); }
  };
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const hasRange = !!range.from || !!range.to;
  const chips = [status && { key: "st", label: t(`return.${status}`), onRemove: () => { setStatus(""); setPage(1); } }].filter(Boolean) as { key: string; label: string; onRemove: () => void }[];
  const actions = (r: Ret) => [
    ...(r.status === "REQUESTED" ? [{ label: t("returns.approve"), icon: "check", onSelect: () => void decide(r.id, "APPROVED") }, { label: t("returns.reject"), icon: "xcircle", danger: true, onSelect: () => setReject(r) }] : []),
    ...(r.status === "APPROVED" ? [{ label: t("returns.received"), icon: "box", onSelect: () => void decide(r.id, "RECEIVED") }] : []),
    { label: t("sell.openOrder"), icon: "eye", onSelect: () => router.push(`/dashboard/orders/${r.orderId}`) },
  ];

  return (
    <div className="space-y-5">
      <PageHeader eyebrow={t("sell.eyebrow")} title={title} subtitle={data ? t("sell.returns.subtitleN", { n: n(data.meta.total) }) : t("sell.returns.subtitle")} />
      <div className="space-y-3">
        <Bar>
          <div className="min-w-0 flex-1"><ChipRow>
            <Chip on={status === ""} onClick={() => { setStatus(""); setPage(1); }}>{t("admin.allStatuses")}</Chip>
            {STATUSES.map((s) => <Chip key={s} on={status === s} onClick={() => { setStatus(status === s ? "" : s); setPage(1); }}>{t(`return.${s}`)}</Chip>)}
          </ChipRow></div>
          <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} />
        </Bar>
        <ActiveChips items={chips} onClearAll={() => { setStatus(""); setPage(1); }} />
      </div>
      <ErrorText error={error ?? err} />
      {data && data.data.length === 0 ? (
        <Empty icon="return" title={status || hasRange ? t("orders.noneMatch") : t("returns.empty")} action={<Link href="/dashboard/orders" className="inline-flex h-10 items-center rounded-[11px] bg-ink px-4 text-[13px] font-semibold text-surface">{t("nav.orders")}</Link>}>
          {t("sell.returns.emptyHint")}
        </Empty>
      ) : (
        <>
          <DataTable loading={loading && !data} cols={[{ label: t("orders.number") }, { label: t("orders.customer") }, { label: t("track.reason") }, { label: t("admin.status") }, { label: t("admin.created") }, { label: "", end: true }]}>
            {data?.data.map((r) => (
              <Row key={r.id}>
                <Cell><Link href={`/dashboard/orders/${r.orderId}`} className="font-mono text-[13px] font-semibold text-brand hover:underline" dir="ltr">{r.orderNumber}</Link><div className="text-[12px] text-muted tnum">{money(r.totalMinor, r.currency, locale)}</div></Cell>
                <Cell><span className="font-medium text-ink">{r.customerNameSnapshot}</span><a href={whatsappLink(r.customerPhoneSnapshot)} target="_blank" rel="noopener noreferrer" dir="ltr" className="block text-[12px] text-emerald-700 hover:underline tnum">{r.customerPhoneSnapshot}</a></Cell>
                <Cell>{t(`reason.${r.reason}`)}{r.details && <div className="max-w-xs text-[12px] text-muted sm:max-w-[16rem]">{r.details}</div>}</Cell>
                <Cell><Badge tone={TONE[r.status]}>{t(`return.${r.status}`)}</Badge></Cell>
                <Cell className="whitespace-nowrap text-[12.5px] text-muted tnum">{dateTime(r.createdAt, locale)}</Cell>
                <Cell end>
                  <div className="flex items-center justify-end gap-1.5">
                    {r.status === "REQUESTED" && <><Button size="sm" variant="ink" disabled={busy} onClick={() => void decide(r.id, "APPROVED")}><Icon name="check" className="h-3.5 w-3.5" />{t("returns.approve")}</Button><Button size="sm" variant="secondary" disabled={busy} onClick={() => setReject(r)}>{t("returns.reject")}</Button></>}
                    {r.status === "APPROVED" && <Button size="sm" variant="ink" disabled={busy} onClick={() => void decide(r.id, "RECEIVED")}>{t("returns.received")}</Button>}
                    <RowMenu label={t("inbox.more")} items={actions(r)} />
                  </div>
                </Cell>
              </Row>
            ))}
          </DataTable>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      <ConfirmDialog open={!!reject} onClose={() => setReject(null)} danger title={t("sell.rejectTitle")} body={t("sell.rejectBody", { n: reject?.orderNumber ?? "" })} confirmLabel={t("returns.reject")} cancelLabel={t("common.back")} loading={busy}
        onConfirm={async () => { if (reject) await decide(reject.id, "REJECTED"); setReject(null); }} />
    </div>
  );
}
