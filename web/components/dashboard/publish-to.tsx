"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, toMinor } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Input, Loading } from "@/components/ui";
import { CountryFlag, ProviderMark, type Account } from "./channels/common";

/** What the owner typed for one variant on one platform (strings, as typed). */
type Offer = { price?: string; compare?: string; qty?: string };
export type PublishState = Record<string, { on?: boolean; offers?: Record<string, Offer> }>;
export type DraftRow = { key: string; label: string; sku: string; price: string; stock: string };
export type PublishResult = { accountId: string; provider?: string; ok: boolean; error?: string };

const eligible = (a: Account) => (a.kind === "MARKETPLACE" || a.kind === "STORE_PLATFORM") && a.status !== "PAUSED" && (a.can.includes("CREATE") || a.can.includes("PUSH"));

export function usePublishAccounts() {
  const { data, loading } = useApi<Account[]>("store/integrations/accounts");
  return { accounts: (data ?? []).filter(eligible), loading };
}

function OfferGrid({ rows, get, set, currency }: { rows: { key: string; label: string }[]; get: (key: string) => Required<Offer>; set: (key: string, patch: Offer) => void; currency: string }) {
  const { t } = useI18n();
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[420px] text-sm">
        <thead><tr className="text-xs text-slate-500"><th className="py-1 text-start font-medium">{t("products.variant")}</th><th className="px-2 text-start font-medium">{t("ch.price")} ({currency})</th><th className="px-2 text-start font-medium">{t("ch.compareAt")}</th><th className="px-2 text-start font-medium">{t("ch.quantity")}</th></tr></thead>
        <tbody>
          {rows.map((r) => {
            const o = get(r.key);
            return (
              <tr key={r.key}>
                <td className="max-w-[160px] truncate py-1.5 pe-2 text-slate-600">{r.label || "—"}</td>
                <td className="px-2 py-1.5"><Input inputMode="decimal" value={o.price} onChange={(e) => set(r.key, { price: e.target.value })} aria-label={t("ch.price")} /></td>
                <td className="px-2 py-1.5"><Input inputMode="decimal" value={o.compare} onChange={(e) => set(r.key, { compare: e.target.value })} aria-label={t("ch.compareAt")} /></td>
                <td className="px-2 py-1.5"><Input inputMode="numeric" value={o.qty} onChange={(e) => set(r.key, { qty: e.target.value })} aria-label={t("ch.quantity")} /></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function PlatformCard({ a, checked, onToggle, children, note }: { a: Account; checked: boolean; onToggle: (on: boolean) => void; children: React.ReactNode; note?: React.ReactNode }) {
  const { t } = useI18n();
  return (
    <div className={`rounded-2xl border p-4 transition ${checked ? "border-brand/50 bg-brand-soft/30" : "border-slate-200 bg-white"}`}>
      <label className="flex cursor-pointer items-center gap-3">
        <input type="checkbox" className="h-5 w-5" checked={checked} onChange={(e) => onToggle(e.target.checked)} />
        <ProviderMark provider={a.provider} name={a.name} className="h-9 w-9 text-xs" />
        <span className="min-w-0 flex-1 font-semibold">{a.name} <CountryFlag code={a.country} /><span className="block truncate text-xs font-normal text-slate-500">{a.label}</span></span>
        {a.status === "ERROR" && <Badge tone="red">{t("ch.status.ERROR")}</Badge>}
      </label>
      {note}
      {checked && <div className="mt-3">{children}</div>}
    </div>
  );
}

const norm = (o: Offer | undefined, d: { price: string; qty: string }): Required<Offer> => ({ price: o?.price ?? d.price, compare: o?.compare ?? "", qty: o?.qty ?? d.qty });

/** In the new-product form: every connected platform, checked by default, each with its own price and quantity per variant. */
export function PublishToNew({ rows, value, onChange }: { rows: DraftRow[]; value: PublishState; onChange: (v: PublishState) => void }) {
  const { t } = useI18n();
  const { accounts, loading } = usePublishAccounts();
  if (loading) return null;
  if (accounts.length === 0) {
    return <Card className="flex flex-wrap items-center justify-between gap-3"><div><h2 className="font-bold">{t("pub.title")}</h2><p className="text-sm text-slate-500">{t("pub.noPlatforms")}</p></div><Link href="/dashboard/channels/connect" className="rounded-xl bg-brand-soft px-4 py-2 text-sm font-semibold text-brand-dark">{t("ch.connect")}</Link></Card>;
  }
  const on = (id: string) => value[id]?.on ?? true;
  return (
    <Card className="space-y-4">
      <div><h2 className="font-bold">{t("pub.title")}</h2><p className="text-sm text-slate-500">{t("pub.hint")}</p></div>
      {accounts.map((a) => (
        <PlatformCard key={a.id} a={a} checked={on(a.id)} onToggle={(v) => onChange({ ...value, [a.id]: { ...value[a.id], on: v } })} note={!a.can.includes("CREATE") ? <p className="mt-2 text-xs text-amber-700">{t("pub.linkOnly")}</p> : undefined}>
          <OfferGrid rows={rows} currency={a.currency}
            get={(k) => { const r = rows.find((x) => x.key === k)!; return norm(value[a.id]?.offers?.[k], { price: r.price, qty: r.stock }); }}
            set={(k, patch) => onChange({ ...value, [a.id]: { ...value[a.id], offers: { ...value[a.id]?.offers, [k]: { ...value[a.id]?.offers?.[k], ...patch } } } })} />
        </PlatformCard>
      ))}
    </Card>
  );
}

/** After the product exists: publish it to the platforms the owner left checked (created variants are matched to the form rows by SKU). */
export async function publishNew(productId: string, created: { id: string; sku: string }[], rows: DraftRow[], value: PublishState, accounts: Account[]): Promise<PublishResult[]> {
  const targets = accounts.filter((a) => value[a.id]?.on ?? true).map((a) => ({
    accountId: a.id,
    variants: rows.map((r) => {
      const o = norm(value[a.id]?.offers?.[r.key], { price: r.price, qty: r.stock });
      return { variantId: created.find((c) => c.sku === r.sku.trim())?.id, priceMinor: toMinor(o.price), compareAtMinor: o.compare ? toMinor(o.compare) : undefined, quantity: Math.max(0, Number(o.qty) || 0) };
    }).filter((v) => v.variantId),
  })).filter((t) => t.variants.length > 0);
  if (targets.length === 0) return [];
  return api<PublishResult[]>("store/integrations/publish", { body: { productId, targets } });
}

type Plan = {
  product: { id: string; name: string };
  variants: { id: string; sku: string; priceMinor: number; compareAtPriceMinor?: number; comboKey?: string; currency: string; available: number }[];
  accounts: (Account & { canCreate: boolean; listings: { variantId: string; priceMinor?: number; compareAtMinor?: number; quantity?: number; status: string; lastError?: string }[] })[];
};

/** On an existing product: see where it is published, change a platform's price/quantity, or publish it to a platform it is not on yet. */
export function PublishToExisting({ productId }: { productId: string }) {
  const { t } = useI18n();
  const plan = useApi<Plan>(`store/integrations/publish-plan?productId=${productId}`);
  const [state, setState] = useState<PublishState>({});
  const [results, setResults] = useState<PublishResult[] | null>(null);
  const go = useAction(async () => {
    const targets = (plan.data?.accounts ?? []).filter((a) => state[a.id]?.on ?? a.listings.length > 0).map((a) => ({
      accountId: a.id,
      variants: plan.data!.variants.map((v) => {
        const l = a.listings.find((x) => x.variantId === v.id);
        const o = norm(state[a.id]?.offers?.[v.id], { price: fromMinor(l?.priceMinor ?? v.priceMinor), qty: String(l?.quantity ?? v.available) });
        const compare = o.compare || (l?.compareAtMinor ? fromMinor(l.compareAtMinor) : v.compareAtPriceMinor ? fromMinor(v.compareAtPriceMinor) : "");
        return { variantId: v.id, priceMinor: toMinor(o.price), compareAtMinor: compare ? toMinor(compare) : undefined, quantity: Math.max(0, Number(o.qty) || 0) };
      }),
    }));
    setResults(await api<PublishResult[]>("store/integrations/publish", { body: { productId, targets } }));
    setState({});
    await plan.reload();
  });
  if (plan.loading && !plan.data) return <Loading />;
  if (!plan.data) return <ErrorText error={plan.error} />;
  const { variants, accounts } = plan.data;
  if (accounts.length === 0) {
    return <Card className="flex flex-wrap items-center justify-between gap-3"><div><h2 className="font-bold">{t("pub.title")}</h2><p className="text-sm text-slate-500">{t("pub.noPlatforms")}</p></div><Link href="/dashboard/channels/connect" className="rounded-xl bg-brand-soft px-4 py-2 text-sm font-semibold text-brand-dark">{t("ch.connect")}</Link></Card>;
  }
  const rows = variants.map((v) => ({ key: v.id, label: v.comboKey || v.sku }));
  const picked = accounts.filter((a) => state[a.id]?.on ?? a.listings.length > 0).length;
  return (
    <Card className="space-y-4">
      <div><h2 className="font-bold">{t("pub.title")}</h2><p className="text-sm text-slate-500">{t("pub.hintExisting")}</p></div>
      {accounts.map((a) => {
        const published = a.listings.length;
        const checked = state[a.id]?.on ?? published > 0;
        const bad = a.listings.find((l) => l.status === "ERROR");
        return (
          <PlatformCard key={a.id} a={a} checked={checked} onToggle={(v) => setState({ ...state, [a.id]: { ...state[a.id], on: v } })}
            note={<p className="mt-2 flex flex-wrap items-center gap-2 text-xs">{published > 0 ? <Badge tone="green">{t("pub.on", { n: published })}</Badge> : <Badge tone="slate">{t("pub.off")}</Badge>}{bad && <span className="text-red-600">{bad.lastError}</span>}{!a.canCreate && published === 0 && <span className="text-amber-700">{t("pub.linkOnly")}</span>}</p>}>
            <OfferGrid rows={rows} currency={a.currency}
              get={(k) => { const v = variants.find((x) => x.id === k)!; const l = a.listings.find((x) => x.variantId === k); return norm(state[a.id]?.offers?.[k], { price: fromMinor(l?.priceMinor ?? v.priceMinor), qty: String(l?.quantity ?? v.available) }); }}
              set={(k, patch) => setState({ ...state, [a.id]: { ...state[a.id], on: state[a.id]?.on ?? published > 0, offers: { ...state[a.id]?.offers, [k]: { ...state[a.id]?.offers?.[k], ...patch } } } })} />
          </PlatformCard>
        );
      })}
      <ErrorText error={go.error} />
      {results && results.map((r) => { const a = accounts.find((x) => x.id === r.accountId); return <Alert key={r.accountId} tone={r.ok ? "green" : "red"}>{a?.name ?? r.provider}: {r.ok ? t("pub.ok") : r.error}</Alert>; })}
      <Button type="button" loading={go.loading} disabled={picked === 0} onClick={() => void go.run()}>{t("pub.publish")}</Button>
    </Card>
  );
}
