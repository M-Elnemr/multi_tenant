"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Empty, ErrorText, Field, FilterBar, Input, Loading, Modal, Pager, Table, Tabs, Td } from "@/components/ui";
import { fromMinor, money, toMinor } from "@/lib/format";
import { ProviderMark } from "./common";

type Listing = {
  id: string; accountId: string; provider: string; country: string; variantId?: string; productId?: string; externalProductId: string; externalSku?: string; title: string; imageUrl?: string;
  priceMinor?: number; compareAtMinor?: number; quantity?: number; stockSync: boolean; currency: string; status: "SYNCED" | "PENDING" | "ERROR"; lastError?: string; shopProductName?: string; shopSku?: string; shopAvailable?: number;
};

function EditListing({ l, onClose, onDone }: { l: Listing; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const [f, setF] = useState({ price: fromMinor(l.priceMinor ?? 0), compare: l.compareAtMinor ? fromMinor(l.compareAtMinor) : "", qty: String(l.quantity ?? 0), sync: l.stockSync });
  const save = useAction(async () => {
    await api(`store/integrations/listings/${l.id}`, { method: "PATCH", body: { priceMinor: toMinor(f.price), compareAtMinor: f.compare ? toMinor(f.compare) : 0, quantity: f.sync ? undefined : Number(f.qty) || 0, stockSync: f.sync } });
    onDone();
  });
  return (
    <Modal open onClose={onClose} title={l.title}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
        <p className="text-sm text-slate-500">{t("ch.listing.editHint")}</p>
        <div className="grid grid-cols-2 gap-3">
          <Field label={`${t("ch.price")} (${l.currency})`}><Input inputMode="decimal" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} required /></Field>
          <Field label={t("ch.compareAt")}><Input inputMode="decimal" value={f.compare} onChange={(e) => setF({ ...f, compare: e.target.value })} /></Field>
        </div>
        {l.variantId && <label className="flex items-start gap-3 rounded-xl border border-slate-200 p-3 text-sm"><input type="checkbox" className="mt-1" checked={f.sync} onChange={(e) => setF({ ...f, sync: e.target.checked })} /><span><b>{t("ch.stockSync")}</b><br /><span className="text-slate-500">{t("ch.stockSyncHint", { n: l.shopAvailable ?? 0 })}</span></span></label>}
        {!f.sync && <Field label={t("ch.quantity")}><Input inputMode="numeric" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} required /></Field>}
        <ErrorText error={save.error} />
        <div className="flex justify-end gap-2"><Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button type="submit" loading={save.loading}>{t("common.save")}</Button></div>
      </form>
    </Modal>
  );
}

type ProductRow = { id: string; name: string };
type ProductDetail = { id: string; name: string; variants: { id: string; sku: string; comboKey: string }[] };

/** Link a platform product to one of the shop's variants (so price/stock/publish line up). */
function LinkModal({ l, onClose, onDone }: { l: Listing; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const [q, setQ] = useState(l.externalSku ?? "");
  const [pid, setPid] = useState<string | null>(null);
  const found = useApi<Page<ProductRow>>(`store/products?q=${encodeURIComponent(q)}&page=1`);
  const detail = useApi<ProductDetail>(pid ? `store/products/${pid}` : null);
  const link = useAction(async (variantId: string) => { await api(`store/integrations/listings/${l.id}/link`, { body: { variantId } }); onDone(); });
  return (
    <Modal open onClose={onClose} title={t("ch.link.title")}>
      <p className="mb-3 text-sm text-slate-500">{t("ch.link.hint", { title: l.title })}</p>
      <Input placeholder={t("ch.link.search")} value={q} onChange={(e) => { setQ(e.target.value); setPid(null); }} />
      <ul className="mt-3 max-h-64 divide-y divide-slate-100 overflow-y-auto rounded-xl border border-slate-200">
        {found.data?.data.map((p) => (
          <li key={p.id}>
            <button onClick={() => setPid(p.id)} className={`w-full px-3 py-2.5 text-start text-sm hover:bg-slate-50 ${pid === p.id ? "bg-brand-soft font-semibold" : ""}`}>{p.name}</button>
            {pid === p.id && detail.data && <ul className="bg-slate-50 px-3 pb-2">{detail.data.variants.map((v) => <li key={v.id}><button onClick={() => void link.run(v.id)} className="w-full rounded-lg px-2 py-1.5 text-start text-xs hover:bg-white"><span className="font-mono">{v.sku}</span> {v.comboKey && <span className="text-slate-500">· {v.comboKey}</span>}</button></li>)}</ul>}
          </li>
        ))}
        {found.data?.data.length === 0 && <li className="p-3 text-sm text-slate-500">{t("ch.link.none")}</li>}
      </ul>
      <div className="mt-3"><ErrorText error={link.error} /></div>
    </Modal>
  );
}

export function ListingsView({ accountId, canImport }: { accountId: string | null; canImport: boolean }) {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState("");
  const [page, setPage] = useState(1);
  const [edit, setEdit] = useState<Listing | null>(null);
  const [link, setLink] = useState<Listing | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [msg, setMsg] = useState("");
  const qs = new URLSearchParams({ page: String(page) });
  if (q.trim()) qs.set("q", q.trim());
  if (filter) qs.set("filter", filter);
  if (accountId) qs.set("accountId", accountId);
  const { data, loading, error, reload } = useApi<Page<Listing>>(`store/integrations/listings?${qs}`);
  const unlink = useAction(async (id: string) => { await api(`store/integrations/listings/${id}/unlink`, { body: {} }); await reload(); });
  const imp = useAction(async () => {
    const r = await api<{ created: number; skipped: number; errors: string[] }>(`store/integrations/accounts/${accountId}/import-to-shop`, { body: { listingIds: selected.length ? selected : undefined } });
    setSelected([]); setMsg(t("ch.import.done", { n: r.created, s: r.skipped })); await reload();
  });
  return (
    <div className="space-y-4">
      <Tabs tabs={[{ key: "", label: t("ch.all") }, { key: "unlinked", label: t("ch.f.unlinked") }, { key: "linked", label: t("ch.f.linked") }, { key: "error", label: t("ch.f.error") }, { key: "low", label: t("ch.f.low") }]} value={filter} onChange={(k) => { setFilter(k); setPage(1); }} />
      <FilterBar>
        <Input className="max-w-sm" placeholder={t("ch.listings.search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} />
        {accountId && canImport && <Button size="sm" variant="secondary" loading={imp.loading} onClick={() => void imp.run()}>{selected.length ? t("ch.import.selected", { n: selected.length }) : t("ch.import.all")}</Button>}
      </FilterBar>
      {msg && <Alert tone="green">{msg}</Alert>}
      <ErrorText error={error ?? imp.error ?? unlink.error} />
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? <Empty>{t("ch.listings.none")}</Empty> : (
        <>
          <Table head={[accountId && canImport ? "" : "", t("products.name"), t("ch.platform"), t("ch.price"), t("ch.quantity"), t("ch.shopProduct"), t("admin.status"), ""]}>
            {data?.data.map((l) => (
              <tr key={l.id}>
                <Td>{accountId && canImport && !l.variantId ? <input type="checkbox" aria-label="select" checked={selected.includes(l.id)} onChange={(e) => setSelected(e.target.checked ? [...selected, l.id] : selected.filter((x) => x !== l.id))} /> : null}</Td>
                <Td><span className="flex items-center gap-3">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  {l.imageUrl ? <img src={l.imageUrl} alt="" className="h-11 w-11 rounded-lg object-cover" /> : <span className="grid h-11 w-11 place-items-center rounded-lg bg-slate-100 text-slate-400">▫</span>}
                  <span className="min-w-0"><span className="block max-w-[260px] truncate font-medium">{l.title}</span>{l.externalSku && <span className="font-mono text-xs text-slate-400">{l.externalSku}</span>}</span></span></Td>
                <Td><ProviderMark provider={l.provider} className="h-7 w-7 text-[10px]" /></Td>
                <Td className="font-semibold">{l.priceMinor != null ? money(l.priceMinor, l.currency, locale) : "—"}</Td>
                <Td>{l.quantity ?? "—"}{l.stockSync && <span title={t("ch.stockSync")} className="ms-1 text-emerald-600">⟳</span>}</Td>
                <Td>{l.variantId ? <span className="text-xs">{l.shopProductName}<br /><span className="font-mono text-slate-400">{l.shopSku}</span></span> : <Badge tone="amber">{t("ch.f.unlinked")}</Badge>}</Td>
                <Td>{l.status === "ERROR" ? <span title={l.lastError}><Badge tone="red">{t("ch.f.error")}</Badge></span> : l.status === "PENDING" ? <Badge tone="amber">{t("ch.pending")}</Badge> : <Badge tone="green">{t("ch.synced")}</Badge>}</Td>
                <Td><span className="flex gap-1">
                  <Button size="sm" variant="ghost" onClick={() => setEdit(l)}>{t("common.edit")}</Button>
                  {l.variantId ? <Button size="sm" variant="ghost" onClick={() => void unlink.run(l.id)}>{t("ch.unlink")}</Button> : <Button size="sm" variant="ghost" onClick={() => setLink(l)}>{t("ch.link")}</Button>}
                </span></Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      {edit && <EditListing l={edit} onClose={() => setEdit(null)} onDone={() => { setEdit(null); void reload(); }} />}
      {link && <LinkModal l={link} onClose={() => setLink(null)} onDone={() => { setLink(null); void reload(); }} />}
    </div>
  );
}

