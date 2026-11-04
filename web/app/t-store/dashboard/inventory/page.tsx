"use client";

import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Chips, SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { Badge, Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Pager, Select, Table, Tabs, Td } from "@/components/ui";
import { dateTime } from "@/lib/format";
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
  const branches = useApi<{ id: string; name: string }[]>("store/branches");
  const qs = new URLSearchParams({ page: String(page), lowStock: String(low) });
  if (q.trim()) qs.set("q", q.trim());
  if (branch) qs.set("branchId", branch);
  const { data, loading, error, reload } = useApi<Page<Row>>(tab === "stock" ? `store/inventory?${qs}` : null);
  const [product, setProduct] = useState<{ id: string; name: string } | null>(null);

  // rows grouped by product, so each product shows its sizes / colours together with one "add stock" button
  const groups = useMemo(() => {
    const g: { productId: string; productName: string; rows: Row[] }[] = [];
    for (const r of data?.data ?? []) {
      const last = g[g.length - 1];
      if (last && last.productId === r.productId) last.rows.push(r); else g.push({ productId: r.productId, productName: r.productName, rows: [r] });
    }
    return g;
  }, [data]);

  return (
    <>
      <PageHeader title={useNavTitle(t("nav.inventory"))} subtitle={t("inv.subtitle")} />
      {tab === "moves" ? <Movements locale={locale} /> : (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-2 rounded-2xl bg-white p-3 shadow-card">
            <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("inv.search")} />
            {(branches.data?.length ?? 0) > 1 && <Select value={branch} onChange={(e) => { setBranch(e.target.value); setPage(1); }} className="!w-auto min-h-11"><option value="">{t("orders.allBranches")}</option>{branches.data!.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select>}
            <Chips options={[{ value: "low", label: "⚠️ " + t("inventory.lowOnly") }]} value={low ? ["low"] : []} onChange={(v) => { setLow(v.length > 0); setPage(1); }} />
          </div>
          <ErrorText error={error} />
          {loading && !data ? <Loading /> : (
            <div className="space-y-3">
              {groups.map((g) => (
                <div key={g.productId} className="animate-fade-up overflow-hidden rounded-2xl bg-white shadow-card">
                  <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-4 py-3">
                    <div><p className="font-bold text-ink">{g.productName}</p><p className="text-xs text-slate-500">{t("inv.piecesTotal", { n: g.rows.reduce((s, r) => s + r.available, 0) })}</p></div>
                    <Button size="sm" onClick={() => setProduct({ id: g.productId, name: g.productName })}>+ {t("inv.addStock")}</Button>
                  </div>
                  <div className="flex flex-wrap gap-2 p-3">
                    {g.rows.map((r) => {
                      const lowNow = r.available <= r.lowStockThreshold;
                      return (
                        <div key={r.branchId + r.variantId} className={`min-w-[150px] rounded-xl border px-3 py-2 ${lowNow ? "border-red-200 bg-red-50" : "border-slate-100 bg-slate-50/60"}`}>
                          <p className="text-sm font-semibold text-slate-800">{label(r.comboKey)}</p>
                          <p className="font-mono text-[11px] text-slate-500">{r.sku}{(branches.data?.length ?? 0) > 1 ? ` · ${r.branchName}` : ""}</p>
                          <p className="mt-1 flex items-baseline gap-2"><span className={`text-xl font-bold ${lowNow ? "text-red-600" : "text-ink"}`}>{r.available}</span>
                            {r.quantityReserved > 0 && <span className="text-[11px] text-slate-500">{t("matrix.reserved", { n: r.quantityReserved })}</span>}</p>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
              {groups.length === 0 && <div className="rounded-2xl border border-dashed border-slate-300 bg-white/60 p-10 text-center text-sm text-slate-500">{t("inv.none")}</div>}
              <Pager meta={data?.meta} onPage={setPage} />
            </div>
          )}
        </>
      )}
      {product && <StockSheet product={product} branches={branches.data ?? []} onClose={() => setProduct(null)} onDone={async () => { setProduct(null); await reload(); }} />}
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
    <Modal open onClose={onClose} title={`${t("inv.addStock")} · ${product.name}`} wide>
      <div className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <Tabs value={mode} onChange={setMode} tabs={[{ key: "add", label: t("inv.modeAdd") }, { key: "set", label: t("inv.modeSet") }]} />
          {branches.length > 1 && <Select value={branch} onChange={(e) => setBranch(e.target.value)} className="!w-auto">{branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select>}
          {mode === "add" && <Select value={type} onChange={(e) => setType(e.target.value)} className="!w-auto">{["PURCHASE", "RETURN", "ADJUSTMENT", "DAMAGE"].map((x) => <option key={x} value={x}>{t(`inventory.type.${x}`)}</option>)}</Select>}
        </div>
        <div className="flex items-end gap-2 rounded-xl bg-slate-50 p-3">
          <label className="space-y-1"><span className="block text-xs font-semibold text-slate-500">{mode === "add" ? t("inv.sameForAll") : t("inv.setAllTo")}</span><Input className="!w-28" dir="ltr" inputMode="numeric" value={all} onChange={(e) => setAll(e.target.value.replace(/[^0-9]/g, ""))} /></label>
          <Button type="button" size="sm" variant="secondary" disabled={!all} onClick={() => setVals(Object.fromEntries(variants.map((v) => [v.id, all])))}>{t("matrix.apply")}</Button>
        </div>
        {d.loading && !d.data ? <Loading /> : (
          <div className="grid gap-2 sm:grid-cols-2" data-field="lines">
            {variants.map((v, i) => {
              const now = onHand(v);
              const n = Number(vals[v.id] || 0);
              const after = mode === "set" ? (vals[v.id] === undefined || vals[v.id] === "" ? now : n) : now + (type === "DAMAGE" ? -n : n);
              return (
                <div key={v.id} className={`flex items-center gap-3 rounded-xl border p-3 ${fe.errUnder(`lines[${i}]`) ? "border-red-300 bg-red-50" : "border-slate-100"}`}>
                  <div className="min-w-0 flex-1"><p className="truncate font-semibold text-slate-800">{label(v.comboKey)}</p>
                    <p className="text-xs text-slate-500">{t("inv.now", { n: now })} → <b className={after < 0 ? "text-red-600" : "text-emerald-700"}>{after}</b></p></div>
                  <div className="flex items-center gap-1">
                    <button type="button" className="grid h-9 w-8 place-items-center rounded-lg border border-slate-200" onClick={() => setVals({ ...vals, [v.id]: String(Math.max(0, n - 1)) })}>−</button>
                    <Input className="!w-16 text-center" dir="ltr" inputMode="numeric" placeholder={mode === "add" ? "+0" : String(now)} value={vals[v.id] ?? ""} onChange={(e) => setVals({ ...vals, [v.id]: e.target.value.replace(/[^0-9]/g, "") })} />
                    <button type="button" className="grid h-9 w-8 place-items-center rounded-lg border border-slate-200" onClick={() => setVals({ ...vals, [v.id]: String(n + 1) })}>+</button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {fe.errUnder("lines") && <p className="text-sm font-medium text-red-600">{fe.errUnder("lines")}</p>}
        <Field label={t("inventory.reason")}><Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("inv.reasonHint")} /></Field>
        {!fe.any && <ErrorText error={save.error} />}
        <Button className="w-full" loading={save.loading} onClick={() => save.run()}>{t("common.save")}</Button>
      </div>
    </Modal>
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
  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2 rounded-2xl bg-white p-3 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("inv.search")} />
        <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} />
      </div>
      <div className="mb-3"><Chips allLabel={t("orders.allStatuses")} options={["PURCHASE", "SALE", "ADJUSTMENT", "RETURN", "DAMAGE", "TRANSFER_IN", "TRANSFER_OUT"].map((x) => ({ value: x, label: t(`inv.move.${x}`) }))} value={type} onChange={(v) => { setType(v); setPage(1); }} /></div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("admin.created"), t("products.name"), t("inventory.type"), t("inventory.quantity"), t("inventory.branch"), t("inventory.reason")]}>
            {data?.data.map((m) => (
              <tr key={m.id}>
                <Td>{dateTime(m.createdAt, locale)}</Td>
                <Td>{m.productName}<div className="text-xs text-slate-500">{label(m.comboKey)} · <span className="font-mono">{m.sku}</span></div></Td>
                <Td><Badge tone={m.quantityDelta > 0 ? "green" : "amber"}>{t(`inv.move.${m.type}`)}</Badge></Td>
                <Td className={`font-bold ${m.quantityDelta > 0 ? "text-emerald-600" : "text-red-600"}`} >{m.quantityDelta > 0 ? "+" : ""}{m.quantityDelta}</Td>
                <Td>{m.branchName ?? "—"}</Td><Td className="text-slate-500">{m.reason || "—"}</Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
