"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { useProductCsv } from "@/components/dashboard/product-csv";
import { ActiveFilters, Chips, FilterGroup, SearchBox } from "@/components/dashboard/filters";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Menu, Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Button, Empty, ErrorText, Loading, PageHeader, Pager, StatusBadge, Table, Td } from "@/components/ui";
import { useNavTitle } from "@/components/dashboard/section";
import { money, num } from "@/lib/format";

type Row = { id: string; name: string; slug: string; status: string; currency: string; minPriceMinor: number; available: number };
const STATUSES = ["ACTIVE", "DRAFT", "ARCHIVED"] as const;

/** Stock cell: the pieces available, or a clear "out of stock" marker. */
function StockCell({ n }: { n: number }) {
  const { t, locale } = useI18n();
  if (n <= 0) return <span className="inline-flex items-center gap-1.5 rounded-full bg-red-50 px-2 py-0.5 text-[12px] font-semibold text-red-700 ring-1 ring-inset ring-red-600/15"><span className="h-1.5 w-1.5 rounded-full bg-current" />{t("cat.outOfStock")}</span>;
  return <span className="font-semibold text-ink tnum">{num(n, locale)}</span>;
}

export default function Products() {
  const { t, locale } = useI18n();
  const { can } = useMe();
  const router = useRouter();
  const toast = useToast();
  const title = useNavTitle(t("nav.products"));
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<string[]>([]);
  const [page, setPage] = useState(1);
  const [sheet, setSheet] = useState(false);
  const [archiving, setArchiving] = useState<Row | null>(null);
  const qs = `store/products?page=${page}&q=${encodeURIComponent(q)}${status[0] ? `&status=${status[0]}` : ""}`;
  const { data, loading, error, reload } = useApi<Page<Row>>(qs);
  const csv = useProductCsv(reload);
  const archive = useAction(async (p: Row) => {
    await api(`store/products/${p.id}`, { method: "DELETE" });
    setArchiving(null);
    toast(t("cat.archived", { name: p.name }));
    await reload();
  });
  const statusOptions = STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }));
  const filtered = !!q.trim() || status.length > 0;
  const clearAll = () => { setQ(""); setStatus([]); setPage(1); };
  const active = [
    ...(q.trim() ? [{ key: "q", label: `${t("common.search")}: ${q.trim()}`, onRemove: () => { setQ(""); setPage(1); } }] : []),
    ...(status[0] ? [{ key: "s", label: t(`status.${status[0]}`), onRemove: () => { setStatus([]); setPage(1); } }] : []),
  ];

  return (
    <>
      <PageHeader eyebrow={t("cat.eyebrow")} title={title} subtitle={t("cat.productsSub")}
        actions={<>
          {can("product.create") && csv.button}
          <Link href="/dashboard/products/labels" className="inline-flex h-10 items-center gap-1.5 rounded-[11px] bg-surface px-3.5 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:text-ink pointer-coarse:min-h-11"><Icon name="qr" className="h-4 w-4" /><span className="hidden sm:inline">{t("labels.title")}</span></Link>
          {can("product.create") && <Link href="/dashboard/products/new" className="press inline-flex h-10 items-center gap-1.5 rounded-[11px] bg-ink px-4 text-[13px] font-semibold text-surface shadow-[0_1px_2px_rgb(16_18_24/.2)] transition hover:opacity-90 pointer-coarse:min-h-11"><Icon name="plus" className="h-4 w-4" />{t("products.add")}</Link>}
        </>} />
      {csv.panel}

      <div className="mb-3 flex flex-wrap items-center gap-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("cat.searchProducts")} />
        <div className="hidden sm:block"><Chips allLabel={t("cat.allStatuses")} options={statusOptions} value={status} onChange={(v) => { setStatus(v); setPage(1); }} /></div>
        <button type="button" onClick={() => setSheet(true)} className={`inline-flex min-h-11 items-center gap-2 rounded-[11px] px-3.5 text-[13px] font-semibold ring-1 transition sm:hidden ${status.length ? "bg-brand-soft text-brand ring-brand/30" : "bg-surface text-ink-2 ring-line-strong"}`}>
          <Icon name="sliders" className="h-4 w-4" />{t("filter.title")}{status.length > 0 && <span className="grid h-5 min-w-5 place-items-center rounded-full bg-brand px-1 text-[11px] text-white">1</span>}
        </button>
        {data && <span className="ms-auto hidden text-[12.5px] font-medium text-muted tnum lg:inline">{t("cat.count", { n: data.meta.total })}</span>}
      </div>
      <ActiveFilters items={active} onClearAll={clearAll} />

      <ErrorText error={error} />
      {loading && !data ? <Loading variant="table" /> : data?.data.length === 0 ? (
        filtered
          ? <Empty icon="search" title={t("cat.noMatchTitle")} action={<Button variant="secondary" onClick={clearAll}>{t("filter.clearAll")}</Button>}>{t("cat.noMatchBody")}</Empty>
          : <Empty icon="box" title={t("products.none")} action={can("product.create") && <Link href="/dashboard/products/new"><Button variant="ink"><Icon name="plus" className="h-4 w-4" />{t("products.add")}</Button></Link>}>{t("cat.emptyBody")}</Empty>
      ) : (
        <div className={loading ? "opacity-60 transition-opacity" : "transition-opacity"}>
          <Table head={[t("products.name"), t("admin.status"), t("products.price"), t("products.stock"), ""]}>
            {data?.data.map((p) => (
              <tr key={p.id} className="cursor-pointer" onClick={() => router.push(`/dashboard/products/${p.id}`)}>
                <Td>
                  <Link href={`/dashboard/products/${p.id}`} onClick={(e) => e.stopPropagation()} className="group flex min-w-0 items-center gap-3">
                    <span className="grid h-10 w-10 shrink-0 place-items-center rounded-[11px] bg-surface-2 text-muted ring-1 ring-line"><Icon name="box" className="h-[18px] w-[18px]" /></span>
                    <span className="min-w-0 text-start">
                      <span className="block truncate font-semibold text-ink group-hover:text-brand">{p.name}</span>
                      <span className="block truncate text-[12px] text-faint" dir="ltr">/{p.slug}</span>
                    </span>
                  </Link>
                </Td>
                <Td><StatusBadge status={p.status} /></Td>
                <Td className="font-semibold text-ink tnum">{money(p.minPriceMinor, p.currency, locale)}</Td>
                <Td><StockCell n={p.available} /></Td>
                <Td className="w-12 text-end">
                  <span onClick={(e) => e.stopPropagation()}>
                    <Menu label={t("common.more")} items={[
                      { label: t("common.edit"), icon: "edit", onSelect: () => router.push(`/dashboard/products/${p.id}`) },
                      { label: t("nav.inventory"), icon: "inventory", onSelect: () => router.push("/dashboard/inventory") },
                      ...(can("product.delete") && p.status !== "ARCHIVED" ? ["divider" as const, { label: t("products.archive"), icon: "close", danger: true, onSelect: () => setArchiving(p) }] : []),
                    ]} trigger={({ toggle }) => (
                      <button type="button" onClick={toggle} aria-label={t("common.more")} className="grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-surface-2 hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="menu" className="h-[18px] w-[18px]" /></button>
                    )} />
                  </span>
                </Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </div>
      )}

      <Sheet open={sheet} onClose={() => setSheet(false)} title={t("filter.title")}
        footer={<div className="flex gap-2"><Button variant="secondary" className="flex-1" onClick={() => { setStatus([]); setPage(1); }}>{t("filter.clearAll")}</Button><Button variant="ink" className="flex-1" onClick={() => setSheet(false)}>{t("filter.show")}</Button></div>}>
        <FilterGroup label={t("admin.status")}><Chips allLabel={t("cat.allStatuses")} options={statusOptions} value={status} onChange={(v) => { setStatus(v); setPage(1); }} /></FilterGroup>
      </Sheet>
      <ConfirmDialog open={!!archiving} onClose={() => setArchiving(null)} onConfirm={() => archiving && void archive.run(archiving)} loading={archive.loading} danger
        title={t("cat.archiveTitle")} body={<>{t("cat.archiveBody", { name: archiving?.name ?? "" })}{!!archive.error && <span className="mt-2 block"><ErrorText error={archive.error} /></span>}</>}
        confirmLabel={t("products.archive")} cancelLabel={t("common.cancel")} />
    </>
  );
}
