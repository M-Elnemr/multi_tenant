"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Checkbox, Sheet, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Badge, Button, Empty, ErrorText, Field, Input, Pager, Table, Td, Toolbar } from "@/components/ui";
import { SearchBox } from "@/components/dashboard/filters";
import { CardSkeletons, PillTabs, Thumb, errText } from "@/components/dashboard/marketing/kit";
import { fromMinor, money, toMinor } from "@/lib/format";
import { ProviderMark } from "./common";

type Listing = {
  id: string; accountId: string; provider: string; country: string; variantId?: string; productId?: string; externalProductId: string; externalSku?: string; title: string; imageUrl?: string;
  priceMinor?: number; compareAtMinor?: number; quantity?: number; stockSync: boolean; currency: string; status: "SYNCED" | "PENDING" | "ERROR"; lastError?: string; shopProductName?: string; shopSku?: string; shopAvailable?: number;
};

function EditListing({ l, onClose, onDone }: { l: Listing; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const [f, setF] = useState({ price: fromMinor(l.priceMinor ?? 0), compare: l.compareAtMinor ? fromMinor(l.compareAtMinor) : "", qty: String(l.quantity ?? 0), sync: l.stockSync });
  const toast = useToast();
  const save = useAction(async () => {
    await api(`store/integrations/listings/${l.id}`, { method: "PATCH", body: { priceMinor: toMinor(f.price), compareAtMinor: f.compare ? toMinor(f.compare) : 0, quantity: f.sync ? undefined : Number(f.qty) || 0, stockSync: f.sync } });
    toast(t("common.saved"));
    onDone();
  });
  return (
    <Sheet open onClose={onClose} title={l.title}
      footer={<div className="flex justify-end gap-2"><Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button variant="ink" loading={save.loading} disabled={!f.price} onClick={() => void save.run()}>{t("common.save")}</Button></div>}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
        <div className="flex items-center gap-3"><Thumb src={l.imageUrl} className="h-14 w-14" /><p className="text-[13px] text-muted">{t("ch.listing.editHint")}</p></div>
        <div className="grid grid-cols-2 gap-3">
          <Field label={`${t("ch.price")} (${l.currency})`}><Input inputMode="decimal" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} required /></Field>
          <Field label={t("ch.compareAt")}><Input inputMode="decimal" value={f.compare} onChange={(e) => setF({ ...f, compare: e.target.value })} /></Field>
        </div>
        {l.variantId && <div className="flex items-start justify-between gap-4 rounded-[14px] bg-canvas p-3.5 ring-1 ring-line"><span className="min-w-0 text-[13px]"><b className="block text-ink">{t("ch.stockSync")}</b><span className="text-muted">{t("ch.stockSyncHint", { n: l.shopAvailable ?? 0 })}</span></span><Switch checked={f.sync} onChange={(v) => setF({ ...f, sync: v })} label={t("ch.stockSync")} /></div>}
        {!f.sync && <Field label={t("ch.quantity")}><Input inputMode="numeric" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} required /></Field>}
        <ErrorText error={save.error} />
      </form>
    </Sheet>
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
  const toast = useToast();
  const link = useAction(async (variantId: string) => { await api(`store/integrations/listings/${l.id}/link`, { body: { variantId } }); toast(t("chx.linked")); onDone(); });
  return (
    <Sheet open onClose={onClose} title={t("ch.link.title")}>
      <p className="mb-3 text-[13px] text-muted">{t("ch.link.hint", { title: l.title })}</p>
      <SearchBox placeholder={t("ch.link.search")} value={q} onChange={(v) => { setQ(v); setPid(null); }} />
      <ul className="mt-3 divide-y divide-line overflow-y-auto rounded-[14px] ring-1 ring-line">
        {found.data?.data.map((p) => (
          <li key={p.id}>
            <button onClick={() => setPid(p.id)} className={`w-full px-3.5 py-2.5 text-start text-[13.5px] hover:bg-surface-2 ${pid === p.id ? "bg-brand-soft font-semibold" : ""}`}>{p.name}</button>
            {pid === p.id && detail.data && <ul className="bg-canvas px-3 pb-2">{detail.data.variants.map((v) => <li key={v.id}><button onClick={() => void link.run(v.id)} className="w-full rounded-lg px-2 py-1.5 text-start text-xs hover:bg-surface"><span className="font-mono">{v.sku}</span> {v.comboKey && <span className="text-muted">· {v.comboKey}</span>}</button></li>)}</ul>}
          </li>
        ))}
        {found.data?.data.length === 0 && <li className="p-3 text-sm text-muted">{t("ch.link.none")}</li>}
      </ul>
      <div className="mt-3"><ErrorText error={link.error} /></div>
    </Sheet>
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
  const toast = useToast();
  const qs = new URLSearchParams({ page: String(page) });
  if (q.trim()) qs.set("q", q.trim());
  if (filter) qs.set("filter", filter);
  if (accountId) qs.set("accountId", accountId);
  const { data, loading, error, reload } = useApi<Page<Listing>>(`store/integrations/listings?${qs}`);
  const unlink = useAction(async (id: string) => { try { await api(`store/integrations/listings/${id}/unlink`, { body: {} }); toast(t("chx.unlinked")); await reload(); } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); } });
  const imp = useAction(async () => {
    const r = await api<{ created: number; skipped: number; errors: string[] }>(`store/integrations/accounts/${accountId}/import-to-shop`, { body: { listingIds: selected.length ? selected : undefined } });
    setSelected([]); toast(t("ch.import.done", { n: r.created, s: r.skipped })); await reload();
  });
  return (
    <div className="space-y-4">
      <PillTabs id="ch-listings" tabs={[{ key: "", label: t("ch.all") }, { key: "unlinked", label: t("ch.f.unlinked") }, { key: "linked", label: t("ch.f.linked") }, { key: "error", label: t("ch.f.error") }, { key: "low", label: t("ch.f.low") }]} value={filter} onChange={(k) => { setFilter(k); setPage(1); }} />
      <Toolbar>
        <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("ch.listings.search")} />
        {accountId && canImport && <Button size="sm" variant="ink" loading={imp.loading} onClick={() => void imp.run()}><Icon name="plus" className="h-4 w-4" />{selected.length ? t("ch.import.selected", { n: selected.length }) : t("ch.import.all")}</Button>}
      </Toolbar>
      <ErrorText error={error ?? imp.error} />
      {loading && !data ? <CardSkeletons n={6} className="space-y-2" h="h-14" /> : data && data.data.length === 0 ? <Empty icon="bag" title={t("ch.listings.none")} /> : (
        <>
          <Table head={[accountId && canImport ? "" : "", t("products.name"), t("ch.platform"), t("ch.price"), t("ch.quantity"), t("ch.shopProduct"), t("admin.status"), ""]}>
            {data?.data.map((l) => (
              <tr key={l.id}>
                <Td>{accountId && canImport && !l.variantId ? <Checkbox label="select" checked={selected.includes(l.id)} onChange={(v) => setSelected(v ? [...selected, l.id] : selected.filter((x) => x !== l.id))} /> : null}</Td>
                <Td><span className="flex items-center gap-3">
                  <Thumb src={l.imageUrl} className="h-11 w-11" />
                  <span className="min-w-0"><span className="block max-w-[260px] truncate font-medium">{l.title}</span>{l.externalSku && <span className="font-mono text-xs text-faint">{l.externalSku}</span>}</span></span></Td>
                <Td><ProviderMark provider={l.provider} className="h-7 w-7 text-[10px]" /></Td>
                <Td className="font-semibold">{l.priceMinor != null ? money(l.priceMinor, l.currency, locale) : "—"}</Td>
                <Td>{l.quantity ?? "—"}{l.stockSync && <span title={t("ch.stockSync")} className="ms-1 inline-block align-middle text-emerald-600"><Icon name="return" className="h-3.5 w-3.5" /></span>}</Td>
                <Td>{l.variantId ? <span className="text-xs">{l.shopProductName}<br /><span className="font-mono text-faint">{l.shopSku}</span></span> : <Badge tone="amber">{t("ch.f.unlinked")}</Badge>}</Td>
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

