"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ActiveFilters, Chips, FilterGroup, SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { Icon } from "@/components/icons";
import { Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Badge, Button, Empty, ErrorText, Field, Input, Loading, PageHeader, Pager, Select, Table, Tabs, Td } from "@/components/ui";
import { dateTime, num } from "@/lib/format";
import { useNavTitle, useSection } from "@/components/dashboard/section";
import { useFieldErrors } from "@/lib/use-field-errors";

type Row = { branchId: string; branchName: string; variantId: string; sku: string; productId: string; productName: string; comboKey: string; quantityOnHand: number; quantityReserved: number; available: number; lowStockThreshold: number };
type Move = { id: string; type: string; quantityDelta: number; reason?: string; createdAt: string; branchName?: string; productName: string; sku: string; comboKey: string };
type Detail = { id: string; name: string; variants: { id: string; sku: string; comboKey: string; status: string; stock?: { branchId: string; quantityOnHand: number; quantityReserved: number }[] }[] };

const label = (combo: string) => combo.replace(/\|/g, " / ").replace(/[^/|]*=/g, "").trim() || "—";

export default function Inventory() {
  const { t, locale } = useI18n();
  const [tab] = useSection(["stock", "moves"] as const, "stock");
  const [low, setLow] = useState(false);
  const [q, setQ] = useState("");
  const [branch, setBranch] = useState("");
  const [page, setPage] = useState(1);
  const [filters, setFilters] = useState(false);
  const branches = useApi<{ id: string; name: string }[]>("store/branches");
  const qs = new URLSearchParams({ page: String(page), lowStock: String(low) });
  if (q.trim()) qs.set("q", q.trim());
  if (branch) qs.set("branchId", branch);
  const { data, loading, error, reload } = useApi<Page<Row>>(tab === "stock" ? `store/inventory?${qs}` : null);
  const [product, setProduct] = useState<{ id: string; name: string } | null>(null);
  const toast = useToast();
  const multi = (branches.data?.length ?? 0) > 1;

  // rows grouped by product, so each product shows its sizes / colours together with one "add stock" button
  const groups = useMemo(() => {
    const g: { productId: string; productName: string; rows: Row[] }[] = [];
    for (const r of data?.data ?? []) {
      const last = g[g.length - 1];
      if (last && last.productId === r.productId) last.rows.push(r); else g.push({ productId: r.productId, productName: r.productName, rows: [r] });
    }
    return g;
  }, [data]);

  const branchName = branches.data?.find((b) => b.id === branch)?.name ?? "";
  const active = [
    ...(q.trim() ? [{ key: "q", label: `${t("common.search")}: ${q.trim()}`, onRemove: () => { setQ(""); setPage(1); } }] : []),
    ...(branch ? [{ key: "b", label: branchName, onRemove: () => { setBranch(""); setPage(1); } }] : []),
    ...(low ? [{ key: "l", label: t("inventory.lowOnly"), onRemove: () => { setLow(false); setPage(1); } }] : []),
  ];
  const clearAll = () => { setQ(""); setBranch(""); setLow(false); setPage(1); };
  const lowChip = <Chips options={[{ value: "low", label: t("inventory.lowOnly") }]} value={low ? ["low"] : []} onChange={(v) => { setLow(v.length > 0); setPage(1); }} />;
  const branchSelect = multi && <Select value={branch} onChange={(e) => { setBranch(e.target.value); setPage(1); }} className="!w-auto min-h-11"><option value="">{t("orders.allBranches")}</option>{branches.data!.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select>;
  const lowGroups = groups.reduce((s, g) => s + g.rows.filter((r) => r.available <= r.lowStockThreshold).length, 0);

  return (
    <>
      <PageHeader eyebrow={t("cat.eyebrow")} title={useNavTitle(t("nav.inventory"))} subtitle={t("inv.subtitle")}
        actions={<Link href="/dashboard/purchasing" className="inline-flex h-10 items-center gap-1.5 rounded-[11px] bg-surface px-3.5 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:text-ink pointer-coarse:min-h-11"><Icon name="truck" className="h-4 w-4" />{t("nav.purchasing")}</Link>} />
      {tab === "moves" ? <Movements locale={locale} /> : (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">
            <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("inv.search")} />
            <div className="hidden items-center gap-2.5 sm:flex">{branchSelect}{lowChip}</div>
            <button type="button" onClick={() => setFilters(true)} className={`inline-flex min-h-11 items-center gap-2 rounded-[11px] px-3.5 text-[13px] font-semibold ring-1 transition sm:hidden ${low || branch ? "bg-brand-soft text-brand ring-brand/30" : "bg-surface text-ink-2 ring-line-strong"}`}><Icon name="sliders" className="h-4 w-4" />{t("filter.title")}</button>
          </div>
          <ActiveFilters items={active} onClearAll={clearAll} />
          {data && lowGroups > 0 && !low && (
            <button type="button" onClick={() => { setLow(true); setPage(1); }} className="mb-3 flex w-full items-center gap-2.5 rounded-[12px] bg-amber-50 px-4 py-2.5 text-start text-[13px] font-medium text-amber-900 ring-1 ring-inset ring-amber-600/20">
              <Icon name="alert" className="h-4 w-4 shrink-0" /><span className="min-w-0 flex-1">{t("cat.lowBanner", { n: lowGroups })}</span><span className="font-semibold underline-offset-4 hover:underline">{t("inventory.lowOnly")}</span>
            </button>
          )}
          <ErrorText error={error} />
          {loading && !data ? <Loading variant="list" /> : groups.length === 0 ? (
            low || q.trim() || branch
              ? <Empty icon="search" title={t("cat.noMatchTitle")} action={<Button variant="secondary" onClick={clearAll}>{t("filter.clearAll")}</Button>}>{t("inv.none")}</Empty>
              : <Empty icon="inventory" title={t("inv.none")} action={<Link href="/dashboard/products/new"><Button variant="ink">{t("products.add")}</Button></Link>}>{t("cat.stockEmptyBody")}</Empty>
          ) : (
            <div className={`space-y-3 transition-opacity ${loading ? "opacity-60" : ""}`}>
              {groups.map((g) => {
                const total = g.rows.reduce((s, r) => s + r.available, 0);
                return (
                  <section key={g.productId} className="overflow-hidden rounded-[18px] bg-surface shadow-card">
                    <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 hairline-b">
                      <div className="min-w-0">
                        <Link href={`/dashboard/products/${g.productId}`} className="block truncate font-display text-[15px] font-semibold text-ink hover:text-brand">{g.productName}</Link>
                        <p className="text-[12px] text-muted tnum">{t("inv.piecesTotal", { n: total })}</p>
                      </div>
                      <Button size="sm" variant="secondary" onClick={() => setProduct({ id: g.productId, name: g.productName })}><Icon name="plus" className="h-3.5 w-3.5" />{t("inv.addStock")}</Button>
                    </div>
                    <div className="grid gap-2 p-3 [grid-template-columns:repeat(auto-fill,minmax(160px,1fr))]">
                      {g.rows.map((r) => {
                        const out = r.available <= 0;
                        const lowNow = !out && r.available <= r.lowStockThreshold;
                        const pct = Math.min(100, r.lowStockThreshold > 0 ? (r.available / (r.lowStockThreshold * 3)) * 100 : r.available > 0 ? 100 : 0);
                        return (
                          <div key={r.branchId + r.variantId} className={`rounded-[14px] px-3 py-2.5 ring-1 ${out ? "bg-red-50 ring-red-600/20" : lowNow ? "bg-amber-50 ring-amber-600/25" : "bg-surface-2 ring-line"}`}>
                            <div className="flex items-start justify-between gap-2">
                              <p className="min-w-0 truncate text-[13px] font-semibold text-ink">{label(r.comboKey)}</p>
                              {(out || lowNow) && <span className={`shrink-0 rounded-full px-1.5 py-0.5 text-[10.5px] font-bold ${out ? "bg-red-600 text-white" : "bg-amber-500 text-white"}`}>{out ? t("cat.outOfStock") : t("cat.low")}</span>}
                            </div>
                            <p className="truncate font-mono text-[11px] text-muted" dir="ltr">{r.sku}{multi ? ` · ${r.branchName}` : ""}</p>
                            <p className="mt-1.5 flex items-baseline gap-2"><span className={`font-display text-[22px] font-semibold leading-none tnum ${out ? "text-red-600" : lowNow ? "text-amber-700" : "text-ink"}`}>{num(r.available, locale)}</span>
                              {r.quantityReserved > 0 && <span className="text-[11px] text-muted">{t("matrix.reserved", { n: r.quantityReserved })}</span>}</p>
                            <div className="mt-2 h-1 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_8%,transparent)]"><div className={`h-full rounded-full ${out ? "bg-red-500" : lowNow ? "bg-amber-500" : "bg-emerald-500"}`} style={{ width: `${pct}%` }} /></div>
                          </div>
                        );
                      })}
                    </div>
                  </section>
                );
              })}
              <Pager meta={data?.meta} onPage={setPage} />
            </div>
          )}
        </>
      )}
      <Sheet open={filters} onClose={() => setFilters(false)} title={t("filter.title")}
        footer={<div className="flex gap-2"><Button variant="secondary" className="flex-1" onClick={clearAll}>{t("filter.clearAll")}</Button><Button variant="ink" className="flex-1" onClick={() => setFilters(false)}>{t("filter.show")}</Button></div>}>
        <div className="space-y-5">
          {multi && <FilterGroup label={t("inventory.branch")}>{branchSelect}</FilterGroup>}
          <FilterGroup label={t("inventory.available")}>{lowChip}</FilterGroup>
        </div>
      </Sheet>
      {product && <StockSheet product={product} branches={branches.data ?? []} onClose={() => setProduct(null)} onDone={async () => { setProduct(null); toast(t("cat.stockSaved")); await reload(); }} />}
    </>
  );
}

/** Add (or set) stock for every colour / size of one product in one go. */
function StockSheet({ product, branches, onClose, onDone }: { product: { id: string; name: string }; branches: { id: string; name: string }[]; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const d = useApi<Detail>(`store/products/${product.id}`);
  const [mode, setMode] = useState<"add" | "set">("add");
  const [type, setType] = useState("PURCHASE");
  const [reason, setReason] = useState("");
  const [branch, setBranch] = useState(branches[0]?.id ?? "");
  const [vals, setVals] = useState<Record<string, string>>({});
  const [all, setAll] = useState("");
  const fe = useFieldErrors();
  const variants = (d.data?.variants ?? []).filter((v) => v.status === "ACTIVE");
  const onHand = (v: Detail["variants"][number]) => v.stock?.find((s) => s.branchId === branch)?.quantityOnHand ?? 0;
  const save = useAction(async () => {
    const lines = variants.map((v) => ({ v, n: vals[v.id] })).filter((x) => x.n !== undefined && x.n !== "")
      .map(({ v, n }) => mode === "set" ? { branchId: branch, variantId: v.id, setTo: Number(n), reason } : { branchId: branch, variantId: v.id, delta: (type === "DAMAGE" ? -1 : 1) * Math.abs(Number(n)), type, reason });
    if (!fe.check({ lines: lines.length === 0 && t("inv.enterSome") })) return;
    try { await api("store/inventory/bulk", { body: lines }); } catch (e) { fe.fromError(e); throw e; }
    onDone();
  });
  return (
    <Sheet open onClose={onClose} width="34rem" title={`${t("inv.addStock")} · ${product.name}`}
      footer={<div className="space-y-2">{!fe.any && <ErrorText error={save.error} />}<Button variant="ink" className="w-full" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button></div>}>
      <div className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <Tabs value={mode} onChange={setMode} tabs={[{ key: "add", label: t("inv.modeAdd") }, { key: "set", label: t("inv.modeSet") }]} />
          {branches.length > 1 && <Select value={branch} onChange={(e) => setBranch(e.target.value)} className="!w-auto">{branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select>}
          {mode === "add" && <Select value={type} onChange={(e) => setType(e.target.value)} className="!w-auto">{["PURCHASE", "RETURN", "ADJUSTMENT", "DAMAGE"].map((x) => <option key={x} value={x}>{t(`inventory.type.${x}`)}</option>)}</Select>}
        </div>
        <div className="flex items-end gap-2 rounded-[14px] bg-surface-2 p-3">
          <label className="space-y-1"><span className="block text-xs font-semibold text-muted">{mode === "add" ? t("inv.sameForAll") : t("inv.setAllTo")}</span><Input className="!w-28" dir="ltr" inputMode="numeric" value={all} onChange={(e) => setAll(e.target.value.replace(/[^0-9]/g, ""))} /></label>
          <Button type="button" size="sm" variant="secondary" disabled={!all} onClick={() => setVals(Object.fromEntries(variants.map((v) => [v.id, all])))}>{t("matrix.apply")}</Button>
        </div>
        {d.loading && !d.data ? <Loading variant="list" /> : (
          <div className="grid gap-2" data-field="lines">
            {variants.map((v, i) => {
              const now = onHand(v);
              const n = Number(vals[v.id] || 0);
              const after = mode === "set" ? (vals[v.id] === undefined || vals[v.id] === "" ? now : n) : now + (type === "DAMAGE" ? -n : n);
              return (
                <div key={v.id} className={`flex items-center gap-3 rounded-[14px] p-3 ring-1 ${fe.errUnder(`lines[${i}]`) ? "bg-red-50 ring-red-600/30" : "ring-line"}`}>
                  <div className="min-w-0 flex-1"><p className="truncate text-[13px] font-semibold text-ink">{label(v.comboKey)}</p>
                    <p className="text-xs text-muted tnum">{t("inv.now", { n: now })} → <b className={after < 0 ? "text-red-600" : "text-emerald-700"}>{after}</b></p></div>
                  <div className="flex items-center gap-1">
                    <button type="button" aria-label="-1" className="grid h-10 w-9 place-items-center rounded-[10px] ring-1 ring-line-strong hover:bg-surface-2" onClick={() => setVals({ ...vals, [v.id]: String(Math.max(0, n - 1)) })}><Icon name="minus" className="h-4 w-4" /></button>
                    <Input className="!w-16 text-center" dir="ltr" inputMode="numeric" placeholder={mode === "add" ? "+0" : String(now)} value={vals[v.id] ?? ""} onChange={(e) => setVals({ ...vals, [v.id]: e.target.value.replace(/[^0-9]/g, "") })} />
                    <button type="button" aria-label="+1" className="grid h-10 w-9 place-items-center rounded-[10px] ring-1 ring-line-strong hover:bg-surface-2" onClick={() => setVals({ ...vals, [v.id]: String(n + 1) })}><Icon name="plus" className="h-4 w-4" /></button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {fe.errUnder("lines") && <p className="text-sm font-medium text-red-600">{fe.errUnder("lines")}</p>}
        <Field label={t("inventory.reason")}><Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("inv.reasonHint")} /></Field>
      </div>
    </Sheet>
  );
}

function Movements({ locale }: { locale: "ar" | "en" }) {
  const { t } = useI18n();
  const [range, setRange] = useState<DateRange>({});
  const [type, setType] = useState<string[]>([]);
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const qs = rangeQuery(new URLSearchParams({ page: String(page) }), range);
  if (type[0]) qs.set("type", type[0]);
  if (q.trim()) qs.set("q", q.trim());
  const { data, loading, error } = useApi<Page<Move>>(`store/inventory/movements?${qs}`);
  const filtered = !!q.trim() || type.length > 0 || !!range.from || !!range.to;
  const clearAll = () => { setQ(""); setType([]); setRange({}); setPage(1); };
  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("inv.search")} />
        <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} />
      </div>
      <div className="mb-3"><Chips allLabel={t("orders.allStatuses")} options={["PURCHASE", "SALE", "ADJUSTMENT", "RETURN", "DAMAGE", "TRANSFER_IN", "TRANSFER_OUT"].map((x) => ({ value: x, label: t(`inv.move.${x}`) }))} value={type} onChange={(v) => { setType(v); setPage(1); }} /></div>
      <ErrorText error={error} />
      {loading && !data ? <Loading variant="table" /> : data?.data.length === 0 ? (
        <Empty icon="list" title={t("cat.movesEmpty")} action={filtered ? <Button variant="secondary" onClick={clearAll}>{t("filter.clearAll")}</Button> : undefined}>{t("cat.movesEmptyBody")}</Empty>
      ) : (
        <div className={`transition-opacity ${loading ? "opacity-60" : ""}`}>
          <Table head={[t("admin.created"), t("products.name"), t("inventory.type"), t("inventory.quantity"), t("inventory.branch"), t("inventory.reason")]}>
            {data?.data.map((m) => (
              <tr key={m.id}>
                <Td className="whitespace-nowrap text-[13px] text-muted">{dateTime(m.createdAt, locale)}</Td>
                <Td><span className="font-semibold text-ink">{m.productName}</span><div className="text-xs text-muted">{label(m.comboKey)} · <span className="font-mono" dir="ltr">{m.sku}</span></div></Td>
                <Td><Badge tone={m.quantityDelta > 0 ? "green" : "amber"}>{t(`inv.move.${m.type}`)}</Badge></Td>
                <Td className={`font-display text-[15px] font-semibold tnum ${m.quantityDelta > 0 ? "text-emerald-600" : "text-red-600"}`}><span className="inline-block" dir="ltr">{m.quantityDelta > 0 ? "+" : ""}{m.quantityDelta}</span></Td>
                <Td>{m.branchName ?? "—"}</Td><Td className="text-muted">{m.reason || "—"}</Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </div>
      )}
    </>
  );
}
