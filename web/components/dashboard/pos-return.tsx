"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input } from "../ui";
import { useWedgeScanner } from "./pos-picker";

type RLine = { orderItemId: string; sku: string; barcode?: string | null; name: string; variantName?: string | null; quantity: number; returned: number; returnable: number; unitMinor: number };
type ROrder = { id: string; orderNumber: string; status: string; currency: string; totalMinor: number; customerNameSnapshot: string; items: RLine[]; canReturn: boolean };
type Result = { refundMinor: number; valueMinor: number; orderFullyReturned: boolean; creditNote: { id: string; number: string } };

/** Returns by item: find the order by its number, scan each piece that came back (or tap +), and only those pieces are restocked, refunded and credited. */
export default function PosReturn() {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const [order, setOrder] = useState<ROrder | null>(null);
  const [take, setTake] = useState<Record<string, number>>({});
  const [reason, setReason] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);

  const find = useAction(async (number: string) => {
    setMsg(null); setResult(null);
    const o = await api<ROrder>(`store/pos/returns/order?q=${encodeURIComponent(number.trim())}`);
    setOrder(o); setTake({});
  });

  const bump = (l: RLine, by: number) => setTake((cur) => ({ ...cur, [l.orderItemId]: Math.max(0, Math.min(l.returnable, (cur[l.orderItemId] ?? 0) + by)) }));
  const scanned = (raw: string) => {
    if (!order) return;
    const c = raw.trim();
    let sku = c;
    if (c.startsWith("http")) { try { sku = new URL(c).searchParams.get("v") ?? c; } catch { sku = c; } }
    const l = order.items.find((x) => x.barcode === sku || x.sku === sku);
    if (!l) { setMsg(t("pos.retNotInOrder")); return; }
    if ((take[l.orderItemId] ?? 0) >= l.returnable) { setMsg(t("pos.retNoMore")); return; }
    setMsg(null); bump(l, 1);
  };
  useWedgeScanner(scanned, !!order);

  const chosen = order ? order.items.filter((l) => (take[l.orderItemId] ?? 0) > 0) : [];
  const value = chosen.reduce((n, l) => n + l.unitMinor * (take[l.orderItemId] ?? 0), 0);
  const submit = useAction(async () => {
    if (!order) return;
    const r = await api<Result>("store/pos/returns", { body: { orderId: order.id, reason: reason || undefined, items: chosen.map((l) => ({ orderItemId: l.orderItemId, quantity: take[l.orderItemId] })) } });
    setResult(r); setOrder(null); setTake({}); setReason("");
  });

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <Card className="space-y-3">
        <form onSubmit={(e) => { e.preventDefault(); void find.run(q); }} className="flex flex-wrap gap-2">
          <Input dir="ltr" className="flex-1 basis-48" placeholder={t("pos.retOrderHint")} value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
          <Button loading={find.loading}>{t("pos.retFind")}</Button>
        </form>
        <ErrorText error={find.error} />
      </Card>
      {result && (
        <Alert tone="green">
          <div className="font-semibold">{t("pos.retDone")}</div>
          <div className="text-sm">{t("pos.retRefunded")}: <b>{money(result.refundMinor, "EGP", locale)}</b>{result.orderFullyReturned ? ` · ${t("pos.retWhole")}` : ""} · <Link className="underline" href={`/dashboard/invoices/${result.creditNote.id}`}>{result.creditNote.number}</Link></div>
        </Alert>
      )}
      {order && (
        <Card className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2"><div><div className="font-bold" dir="ltr">{order.orderNumber}</div><div className="text-sm text-slate-500">{order.customerNameSnapshot} · {money(order.totalMinor, order.currency, locale)}</div></div></div>
          {!order.canReturn && <Alert tone="amber">{t("pos.retNotAllowed")}</Alert>}
          {order.canReturn && <p className="text-sm text-slate-600">{t("pos.retScanHint")}</p>}
          <ul className="divide-y">
            {order.items.map((l) => (
              <li key={l.orderItemId} className="flex items-center gap-3 py-2 text-sm">
                <div className="min-w-0 flex-1"><div className="truncate font-medium">{l.name}{l.variantName ? ` · ${l.variantName}` : ""}</div><div dir="ltr" className="text-start text-xs text-slate-500">{l.sku}{l.barcode ? ` · ${l.barcode}` : ""}</div><div className="text-xs text-slate-500">{t("pos.retSold")} {l.quantity} · {t("pos.retBack")} {l.returned} · {money(l.unitMinor, order.currency, locale)}</div></div>
                {order.canReturn && l.returnable > 0 && (
                  <div className="flex items-center rounded-full border"><button className="h-9 w-9" onClick={() => bump(l, -1)}>−</button><span className="w-7 text-center font-bold">{take[l.orderItemId] ?? 0}</span><button className="h-9 w-9" onClick={() => bump(l, 1)}>+</button></div>
                )}
              </li>
            ))}
          </ul>
          {msg && <Alert tone="amber">{msg}</Alert>}
          {order.canReturn && (
            <>
              <Field label={t("pos.retReason")}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
              <ErrorText error={submit.error} />
              <Button className="w-full" loading={submit.loading} disabled={chosen.length === 0} onClick={() => void submit.run()}>{t("pos.retDo")} {money(value, order.currency, locale)}</Button>
            </>
          )}
        </Card>
      )}
    </div>
  );
}
