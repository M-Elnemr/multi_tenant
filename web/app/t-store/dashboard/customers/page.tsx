"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { localPhone } from "@/lib/phone";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Avatar, Badge, Empty, ErrorText, Loading, PageHeader, Pager, StatusBadge, Table, Td, Toolbar } from "@/components/ui";
import { Icon } from "@/components/icons";
import { Menu, Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { SearchBox } from "@/components/dashboard/filters";
import { FlaggedPhones } from "@/components/dashboard/flagged-phones";
import { useNavTitle } from "@/components/dashboard/section";
import { errText } from "@/components/dashboard/marketing/kit";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { dateOnly, dateTime, money } from "@/lib/format";

type C = {
  id: string; customerNumber: string; name: string; phone?: string; email?: string; hasAccount: boolean; createdAt: string; ordersCount: number; totalSpentMinor: number; lastOrderAt?: string;
  address?: { addressLine1?: string; city?: string; district?: string } | null; gender?: "MALE" | "FEMALE" | null; genderSource?: "AI" | "SELF" | "OWNER" | null;
};
type O = { id: string; orderNumber: string; status: string; totalMinor: number; currency: string; createdAt: string };

/** The shop's clients. Read-only: they appear when someone orders, and a shop can contact them but never edit or add them. Each row opens the full profile. */
export default function Customers() {
  const { t, locale, currency } = useI18n();
  const router = useRouter();
  const title = useNavTitle(t("nav.customers"));
  const [page, setPage] = useState(1);
  const [q, setQ] = useState("");
  const [range, setRange] = useState<DateRange>({});
  const rq = (b: string) => { const qs = rangeQuery(new URLSearchParams(), range).toString(); return qs ? `${b}${b.includes("?") ? "&" : "?"}${qs}` : b; };
  const [peek, setPeek] = useState<C | null>(null);
  const { data, loading, error, reload } = useApi<Page<C>>(rq(`store/customers?page=${page}&q=${encodeURIComponent(q)}`));
  const profile = (c: C) => `/dashboard/customers/${c.id}`;
  const filtered = !!q.trim() || !!range.from || !!range.to;
  return (
    <>
      <PageHeader eyebrow={t("navgrp.service")} title={title} subtitle={t("customers.readOnly")}
        badge={data?.meta ? <Badge tone="slate">{t("cux.count", { n: data.meta.total })}</Badge> : undefined} />
      <div className="space-y-5">
        <FlaggedPhones />
        <div>
          <Toolbar>
            <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("customers.search")} />
            <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} label={t("customers.joined")} />
          </Toolbar>
          <ErrorText error={error} />
          {loading && !data ? <Loading variant="table" /> : data && data.data.length === 0 ? (
            <Empty icon="users" title={filtered ? t("cux.noMatch") : t("cux.none.title")}>{filtered ? t("cux.noMatch.body") : t("cux.none.body")}</Empty>
          ) : (
            <>
              <Table head={[t("orders.customer"), t("staff.phone"), t("customers.orders"), t("customers.spent"), t("customers.lastOrder"), ""]}>
                {data?.data.map((c) => (
                  <tr key={c.id} className="cursor-pointer" onClick={() => router.push(profile(c))}>
                    <Td>
                      <Link href={profile(c)} onClick={(e) => e.stopPropagation()} className="group flex min-w-0 items-center gap-3">
                        <Avatar name={c.name || "?"} className="h-9 w-9 text-[13px]" />
                        <span className="min-w-0">
                          <span className="flex items-center gap-1.5 font-semibold text-ink group-hover:text-brand">{c.name}{c.hasAccount && <Badge tone="blue">Google</Badge>}</span>
                          <span className="block font-mono text-[11.5px] text-faint">{c.customerNumber}</span>
                        </span>
                      </Link>
                    </Td>
                    <Td><span dir="ltr" className="tnum text-ink-2">{localPhone(c.phone)}</span></Td>
                    <Td><span className="tnum">{c.ordersCount}</span></Td>
                    <Td><span className="font-semibold tnum text-ink">{money(c.totalSpentMinor, currency, locale)}</span></Td>
                    <Td><span className="text-muted">{c.lastOrderAt ? dateOnly(c.lastOrderAt, locale) : "-"}</span></Td>
                    <Td>
                      <span className="flex items-center justify-end gap-1.5" onClick={(e) => e.stopPropagation()}>
                        {c.phone && (
                          <a href={`tel:${c.phone}`} aria-label={t("customers.call")} title={t("customers.call")}
                            className="grid h-8 w-8 place-items-center rounded-full bg-ink text-surface transition hover:opacity-85"><Icon name="phone" className="h-4 w-4" /></a>
                        )}
                        <WhatsAppButton phone={c.phone} message={t("customers.waHello", { name: c.name })} size={32} />
                        <Menu label={t("common.more")} items={[
                          { label: t("cux.openProfile"), icon: "users", onSelect: () => router.push(profile(c)) },
                          { label: t("cux.quickView"), icon: "eye", onSelect: () => setPeek(c) },
                        ]} trigger={({ toggle }) => (
                          <button type="button" onClick={toggle} aria-label={t("common.more")} className="grid h-8 w-8 place-items-center rounded-[9px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"><Icon name="menu" className="h-4 w-4" /></button>
                        )} />
                      </span>
                    </Td>
                  </tr>
                ))}
              </Table>
              <Pager meta={data?.meta} onPage={setPage} />
            </>
          )}
        </div>
      </div>
      <QuickView c={peek} onClose={() => setPeek(null)} onChanged={(c) => { setPeek(c); void reload(); }} profileHref={peek ? profile(peek) : "#"} />
    </>
  );
}

/** A side drawer with the contact details, the gender the shop set and the latest orders (bottom sheet on a phone). */
function QuickView({ c, onClose, onChanged, profileHref }: { c: C | null; onClose: () => void; onChanged: (c: C) => void; profileHref: string }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const orders = useApi<O[]>(c ? `store/customers/${c.id}/orders` : null);
  const setGender = useAction(async (g: string) => {
    if (!c) return;
    await api(`store/customers/${c.id}/gender`, { method: "PUT", body: { gender: g } });
    onChanged({ ...c, gender: (g || null) as C["gender"], genderSource: g ? "OWNER" : null });
    toast(t("common.saved"));
  });
  const source = c?.genderSource === "SELF" ? t("customers.genderSelf") : c?.genderSource === "AI" ? t("customers.genderAi") : c?.genderSource === "OWNER" ? t("customers.genderOwner") : t("customers.genderUnknown");
  return (
    <Sheet open={!!c} onClose={onClose} title={c?.name ?? ""} width="28rem"
      footer={<Link href={profileHref} className="flex h-10 w-full items-center justify-center gap-2 rounded-[11px] bg-ink text-sm font-semibold text-surface transition hover:opacity-90">{t("cux.openProfile")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>}>
      {c && (
        <div className="space-y-5 text-sm">
          <div className="flex items-center gap-3">
            <Avatar name={c.name || "?"} className="h-12 w-12 text-base" />
            <div className="min-w-0">
              <p dir="ltr" className="text-start font-semibold tnum text-ink">{localPhone(c.phone)}</p>
              {c.email && <p dir="ltr" className="truncate text-start text-muted">{c.email}</p>}
              <p className="text-[12px] text-faint">{t("customers.since")} {dateOnly(c.createdAt, locale)}</p>
            </div>
          </div>
          {c.address?.addressLine1 && <p className="flex items-start gap-2 rounded-[12px] bg-canvas p-3 text-ink-2 ring-1 ring-line"><Icon name="pin" className="mt-0.5 h-4 w-4 shrink-0 text-muted" />{c.address.addressLine1}, {c.address.city}</p>}
          <div className="flex items-center gap-2">
            {c.phone && <a href={`tel:${c.phone}`} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-3 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-surface-2"><Icon name="phone" className="h-4 w-4" />{t("customers.call")}</a>}
            <WhatsAppButton phone={c.phone} message={t("customers.waHello", { name: c.name })} size={36} />
          </div>
          <div>
            <p className="mb-2 text-[12px] font-semibold uppercase tracking-wide text-faint">{t("customers.gender")}</p>
            <div className="flex flex-wrap items-center gap-2">
              {(["MALE", "FEMALE"] as const).map((g) => (
                <button key={g} type="button" disabled={c.genderSource === "SELF" || setGender.loading} aria-pressed={c.gender === g}
                  onClick={() => void setGender.run(c.gender === g && c.genderSource === "OWNER" ? "" : g)} className={`chip disabled:opacity-60 ${c.gender === g ? "chip-on" : ""}`}>{t(`gender.${g}`)}</button>
              ))}
            </div>
            <p className="mt-1.5 text-[12px] text-muted">{source}</p>
            {setGender.error ? <p className="mt-1 text-[12px] text-red-600">{errText(setGender.error, t("inbox.saveFailed"))}</p> : null}
          </div>
          <div>
            <p className="mb-2 text-[12px] font-semibold uppercase tracking-wide text-faint">{t("nav.orders")}</p>
            {orders.loading && !orders.data ? <div className="space-y-2">{[0, 1, 2].map((i) => <div key={i} className="skeleton h-12 rounded-[12px]" />)}</div>
              : (orders.data ?? []).length === 0 ? <p className="rounded-[12px] bg-canvas p-4 text-center text-[13px] text-muted ring-1 ring-line">{t("inbox.noOrders")}</p> : (
                <ul className="divide-y divide-line overflow-hidden rounded-[14px] ring-1 ring-line">
                  {orders.data?.map((o) => (
                    <li key={o.id}>
                      <Link href={`/dashboard/orders/${o.id}`} className="flex items-center justify-between gap-2 px-3 py-2.5 transition hover:bg-surface-2">
                        <span className="min-w-0"><span className="block font-mono text-[12px] font-semibold text-ink">{o.orderNumber}</span><span className="block text-[11.5px] text-faint">{dateTime(o.createdAt, locale)}</span></span>
                        <span className="flex shrink-0 items-center gap-2"><StatusBadge status={o.status} /><b className="tnum">{money(o.totalMinor, o.currency, locale)}</b></span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
          </div>
        </div>
      )}
    </Sheet>
  );
}
