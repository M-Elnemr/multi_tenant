"use client";

import { useMemo, useState } from "react";
import { money } from "@/lib/format";
import { quickCash, toMinor } from "@/lib/pos/money";
import type { Payment } from "@/lib/pos/db";
import { useI18n } from "../i18n-provider";
import { Button, Input } from "../ui";

type Row = { method: string; amount: string };

/**
 * Taking the money: one method (cash with the change worked out, card or transfer) or several (part cash, part card). Cash can be more than what is due (the change);
 * card and transfer only up to what is left.
 */
export function PayPanel({ totalMinor, currency, methods, onPay, onCancel, busy }: {
  totalMinor: number; currency: string; methods: string[]; busy: boolean;
  onPay: (payments: Payment[], tenderedMinor: number, changeMinor: number) => void; onCancel: () => void;
}) {
  const { t, locale } = useI18n();
  const m = (v: number) => money(v, currency, locale);
  const [split, setSplit] = useState(false);
  const [method, setMethod] = useState(methods.includes("CASH") ? "CASH" : methods[0]);
  const [tendered, setTendered] = useState("");
  const [rows, setRows] = useState<Row[]>([{ method: "CASH", amount: "" }, { method: methods.find((x) => x !== "CASH") ?? "CARD_POS", amount: "" }]);

  const single = useMemo(() => {
    const given = method === "CASH" ? (toMinor(tendered) || totalMinor) : totalMinor;
    return { given, change: method === "CASH" ? Math.max(0, given - totalMinor) : 0, ok: method !== "CASH" || given >= totalMinor };
  }, [method, tendered, totalMinor]);

  const multi = useMemo(() => {
    const nonCash = rows.filter((r) => r.method !== "CASH").reduce((n, r) => n + toMinor(r.amount), 0);
    const cashGiven = rows.filter((r) => r.method === "CASH").reduce((n, r) => n + toMinor(r.amount), 0);
    const cashDue = Math.max(0, totalMinor - nonCash);
    const change = Math.max(0, cashGiven - cashDue);
    const ok = nonCash <= totalMinor && nonCash + cashGiven >= totalMinor && (cashGiven > 0 || nonCash === totalMinor);
    return { nonCash, cashGiven, cashDue, change, ok, left: Math.max(0, totalMinor - nonCash - cashGiven) };
  }, [rows, totalMinor]);

  const confirm = () => {
    if (!split) {
      if (!single.ok) return;
      onPay([{ method, amountMinor: totalMinor }], method === "CASH" ? single.given : 0, single.change);
      return;
    }
    if (!multi.ok) return;
    const pays: Payment[] = [];
    for (const r of rows) {
      const a = toMinor(r.amount);
      if (a <= 0) continue;
      if (r.method === "CASH") continue;
      pays.push({ method: r.method, amountMinor: a });
    }
    if (multi.cashDue > 0) pays.push({ method: "CASH", amountMinor: multi.cashDue });   // the cash line is what cash paid of the total; the rest of what was handed over is the change
    onPay(pays, multi.cashGiven, multi.change);
  };

  return (
    <div className="space-y-4">
      <div className="rounded-2xl bg-slate-900 p-4 text-center text-white">
        <div className="text-sm opacity-70">{t("term.due")}</div>
        <div className="text-4xl font-extrabold tabular-nums">{m(totalMinor)}</div>
      </div>
      <div className="flex gap-2">
        <button type="button" onClick={() => setSplit(false)} className={`flex-1 rounded-xl border px-3 py-2 text-sm font-semibold ${!split ? "border-brand bg-brand-soft text-brand-dark" : "border-slate-200"}`}>{t("term.oneMethod")}</button>
        <button type="button" onClick={() => setSplit(true)} className={`flex-1 rounded-xl border px-3 py-2 text-sm font-semibold ${split ? "border-brand bg-brand-soft text-brand-dark" : "border-slate-200"}`}>{t("term.split")}</button>
      </div>
      {!split ? (
        <>
          <div className="grid grid-cols-3 gap-2">
            {methods.map((x) => (
              <button key={x} type="button" onClick={() => setMethod(x)} className={`rounded-xl border px-2 py-3 text-sm font-bold ${method === x ? "border-brand bg-brand text-white" : "border-slate-200 bg-white"}`}>{t(`pay.${x}`)}</button>
            ))}
          </div>
          {method === "CASH" && (
            <div className="space-y-2">
              <label className="block text-sm font-semibold text-slate-700">{t("term.tendered")}</label>
              <Input autoFocus dir="ltr" inputMode="decimal" value={tendered} onChange={(e) => setTendered(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") confirm(); }} placeholder={(totalMinor / 100).toFixed(2)} className="text-2xl font-bold" />
              <div className="flex flex-wrap gap-2">
                {quickCash(totalMinor, currency).map((v) => (
                  <button key={v} type="button" onClick={() => setTendered((v / 100).toString())} className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-semibold tabular-nums hover:border-brand">{m(v)}</button>
                ))}
              </div>
              <div className={`flex items-center justify-between rounded-xl p-3 text-lg font-bold ${single.ok ? "bg-emerald-50 text-emerald-800" : "bg-red-50 text-red-700"}`}>
                <span>{single.ok ? t("term.change") : t("term.notEnough")}</span><span className="tabular-nums">{single.ok ? m(single.change) : m(totalMinor - single.given)}</span>
              </div>
            </div>
          )}
        </>
      ) : (
        <div className="space-y-2">
          {rows.map((r, i) => (
            <div key={i} className="flex gap-2">
              <select value={r.method} onChange={(e) => setRows(rows.map((x, k) => (k === i ? { ...x, method: e.target.value } : x)))} className="w-36 rounded-xl border border-slate-200 bg-white px-2 text-sm">
                {methods.map((x) => <option key={x} value={x}>{t(`pay.${x}`)}</option>)}
              </select>
              <Input dir="ltr" inputMode="decimal" value={r.amount} onChange={(e) => setRows(rows.map((x, k) => (k === i ? { ...x, amount: e.target.value } : x)))} placeholder="0.00" />
              {rows.length > 2 && <button type="button" className="px-2 text-slate-400" onClick={() => setRows(rows.filter((_, k) => k !== i))}>✕</button>}
            </div>
          ))}
          <button type="button" className="text-sm font-semibold text-brand" onClick={() => setRows([...rows, { method: methods[0], amount: "" }])}>+ {t("term.addPayment")}</button>
          <div className="grid grid-cols-2 gap-2 text-sm">
            <div className="rounded-xl bg-slate-50 p-2">{t("term.left")}: <b className="tabular-nums">{m(multi.left)}</b></div>
            <div className="rounded-xl bg-emerald-50 p-2 text-emerald-800">{t("term.change")}: <b className="tabular-nums">{m(multi.change)}</b></div>
          </div>
          {multi.nonCash > totalMinor && <p className="text-sm text-red-600">{t("term.cardTooMuch")}</p>}
        </div>
      )}
      <div className="flex gap-2">
        <Button variant="secondary" className="flex-1" onClick={onCancel}>{t("common.cancel")}</Button>
        <Button className="flex-[2] py-3 text-base" loading={busy} disabled={split ? !multi.ok : !single.ok} onClick={confirm}>{t("term.confirmPay")}</Button>
      </div>
    </div>
  );
}
