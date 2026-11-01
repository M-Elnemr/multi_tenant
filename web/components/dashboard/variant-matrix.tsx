"use client";

import { useState } from "react";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { Button, Input } from "../ui";
import type { OptionDraft } from "./option-builder";

/** One option combination's editable row. stock: branchId -> wanted on-hand quantity (text while typing). */
export type MatrixRow = { id?: string; sku: string; barcode: string; price: string; compareAt: string; stock: Record<string, string>; onHand?: Record<string, number>; reserved?: Record<string, number> };
type Branch = { id: string; name: string };

/** Every combination of the options, in option order ({} when there are none: a single row). */
export function combos(options: { name: string; values: string[] }[]): Record<string, string>[] {
  return options.reduce<Record<string, string>[]>((acc, o) => acc.flatMap((c) => o.values.map((v) => ({ ...c, [o.name]: v }))), [{}]);
}

export const comboKey = (options: { name: string }[], c: Record<string, string>) => options.map((o) => `${o.name}=${c[o.name]}`).join("|");

export const emptyRow = (): MatrixRow => ({ sku: "", barcode: "", price: "", compareAt: "", stock: {} });

/** The options of a saved product, back in the builder's shape. */
export function draftsFrom(options: { name: string; attribute?: string; sizeScale?: string; values: string[] }[] | undefined): OptionDraft[] {
  return (options ?? []).map((o) =>
    o.attribute === "COLOR" ? { kind: "COLOR", scale: "", name: "", values: o.values, custom: "" }
      : o.attribute === "SIZE" ? { kind: "SIZE", scale: o.sizeScale || "APPAREL", name: "", values: o.values, custom: "" }
        : { kind: "CUSTOM", scale: "", name: o.name, values: [], custom: o.values.join("، ") });
}

/**
 * The variants table used to add and to edit a product: price, old price, SKU, barcode and the quantity in every branch for each colour / size / ...
 * A tools row fills a whole column at once (same price for all, +5 pieces for all) and makes SKUs. Errors from the server land on their cell.
 */
export function VariantMatrix({ options, rows, onRows, branches, errUnder, err, edit, baseSku }: {
  options: { name: string; values: string[] }[]; rows: Record<string, MatrixRow>; onRows: (r: Record<string, MatrixRow>) => void; branches: Branch[];
  errUnder: (p: string) => string | undefined; err: (p: string) => string | undefined; edit?: boolean; baseSku?: string;
}) {
  const { t } = useI18n();
  const matrix = combos(options);
  const keyOf = (c: Record<string, string>) => comboKey(options, c);
  const rowOf = (c: Record<string, string>) => rows[keyOf(c)] ?? emptyRow();
  const [bulk, setBulk] = useState({ price: "", add: "", sku: baseSku ?? "" });
  const setRow = (c: Record<string, string>, patch: Partial<MatrixRow>) => onRows({ ...rows, [keyOf(c)]: { ...rowOf(c), ...patch } });
  const all = (fn: (r: MatrixRow, c: Record<string, string>) => MatrixRow) => { const n = { ...rows }; for (const c of matrix) n[keyOf(c)] = fn(rowOf(c), c); onRows(n); };
  const total = (r: MatrixRow) => branches.reduce((s, b) => s + (Number(r.stock[b.id]) || 0), 0);
  const grand = matrix.reduce((s, c) => s + total(rowOf(c)), 0);
  const slug = (v: string) => v.replace(/\s+/g, "").slice(0, 6).toUpperCase();

  return (
    <div className="space-y-3">
      {matrix.length > 1 && (
        <div className="flex flex-wrap items-end gap-2 rounded-xl bg-slate-50 p-3">
          <label className="space-y-1"><span className="block text-xs font-semibold text-slate-500">{t("matrix.priceAll")}</span>
            <div className="flex gap-1"><Input className="!w-28" dir="ltr" inputMode="decimal" value={bulk.price} onChange={(e) => setBulk({ ...bulk, price: e.target.value })} />
              <Button type="button" size="sm" variant="secondary" disabled={!bulk.price} onClick={() => all((r) => ({ ...r, price: bulk.price }))}>{t("matrix.apply")}</Button></div></label>
          <label className="space-y-1"><span className="block text-xs font-semibold text-slate-500">{t("matrix.addAll")}</span>
            <div className="flex gap-1"><Input className="!w-24" dir="ltr" inputMode="numeric" placeholder="+5" value={bulk.add} onChange={(e) => setBulk({ ...bulk, add: e.target.value })} />
              <Button type="button" size="sm" variant="secondary" disabled={!Number(bulk.add) || !branches[0]} onClick={() => all((r) => ({ ...r, stock: { ...r.stock, [branches[0].id]: String(Math.max(0, (Number(r.stock[branches[0].id]) || 0) + Number(bulk.add))) } }))}>{t("matrix.apply")}</Button></div></label>
          <label className="space-y-1"><span className="block text-xs font-semibold text-slate-500">{t("matrix.skuAll")}</span>
            <div className="flex gap-1"><Input className="!w-28" dir="ltr" placeholder="TSHIRT" value={bulk.sku} onChange={(e) => setBulk({ ...bulk, sku: e.target.value })} />
              <Button type="button" size="sm" variant="secondary" disabled={!bulk.sku.trim()} onClick={() => all((r, c) => ({ ...r, sku: r.sku || [bulk.sku.trim().toUpperCase(), ...options.map((o) => slug(c[o.name]))].join("-") }))}>{t("matrix.makeSku")}</Button></div></label>
          <span className="ms-auto rounded-full bg-white px-3 py-1.5 text-xs font-bold text-slate-600 shadow-sm">{t("matrix.totalPieces", { n: grand })}</span>
        </div>
      )}
      <div className="overflow-x-auto rounded-xl border border-slate-100">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-slate-50/80 text-xs text-slate-500">
            <tr>
              {options.length > 0 && <th className="p-2 text-start">{t("products.combination")}</th>}
              <th className="p-2 text-start">{t("products.price")}</th>
              <th className="p-2 text-start">{t("matrix.oldPrice")}</th>
              <th className="p-2 text-start">SKU</th>
              <th className="p-2 text-start">{t("products.barcode")}</th>
              {branches.map((b) => <th key={b.id} className="p-2 text-start">{branches.length > 1 ? b.name : t("products.stock")}</th>)}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {matrix.map((c, i) => {
              const r = rowOf(c);
              const at = `variants[${i}]`;
              const rowErr = errUnder(at);
              const cell = (p: string) => (err(`${at}.${p}`) ? "cell-error" : "");
              return (
                <tr key={keyOf(c)} data-field={at} className={rowErr ? "bg-red-50/60" : ""}>
                  {options.length > 0 && (
                    <td className="p-2 align-top">
                      <div className="flex flex-wrap gap-1">{options.map((o) => <span key={o.name} className="rounded-md bg-slate-100 px-2 py-0.5 text-xs font-semibold text-slate-700">{c[o.name]}</span>)}</div>
                      {!r.id && edit && <span className="mt-1 inline-block rounded bg-emerald-50 px-1.5 text-[10px] font-bold text-emerald-700">{t("matrix.new")}</span>}
                      {rowErr && <p className="mt-1 flex items-start gap-1 text-xs font-medium text-red-600"><Icon name="alert" className="mt-px h-3 w-3 shrink-0" />{rowErr}</p>}
                    </td>
                  )}
                  <td className={`p-1.5 ${cell("priceMinor")}`}><Input name={`${at}.priceMinor`} className="!w-28" dir="ltr" inputMode="decimal" value={r.price} onChange={(e) => setRow(c, { price: e.target.value })} /></td>
                  <td className={`p-1.5 ${cell("compareAtPriceMinor")}`}><Input name={`${at}.compareAtPriceMinor`} className="!w-28" dir="ltr" inputMode="decimal" placeholder="—" value={r.compareAt} onChange={(e) => setRow(c, { compareAt: e.target.value })} /></td>
                  <td className={`p-1.5 ${cell("sku")}`}><Input name={`${at}.sku`} className="!w-36" dir="ltr" value={r.sku} onChange={(e) => setRow(c, { sku: e.target.value })} /></td>
                  <td className={`p-1.5 ${cell("barcode")}`}><Input name={`${at}.barcode`} className="!w-36" dir="ltr" inputMode="numeric" placeholder={t("products.barcodeHint")} value={r.barcode} onChange={(e) => setRow(c, { barcode: e.target.value })} /></td>
                  {branches.map((b) => {
                    const was = r.onHand?.[b.id];
                    const now = Number(r.stock[b.id]) || 0;
                    const step = (d: number) => setRow(c, { stock: { ...r.stock, [b.id]: String(Math.max(0, now + d)) } });
                    return (
                      <td key={b.id} className={`p-1.5 ${cell("stock")}`}>
                        <div className="flex items-center gap-1">
                          <button type="button" onClick={() => step(-1)} className="grid h-9 w-8 place-items-center rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50" aria-label="-1">−</button>
                          <Input name={`${at}.stock`} className="!w-16 text-center" dir="ltr" inputMode="numeric" value={r.stock[b.id] ?? ""} placeholder="0" onChange={(e) => setRow(c, { stock: { ...r.stock, [b.id]: e.target.value.replace(/[^0-9]/g, "") } })} />
                          <button type="button" onClick={() => step(1)} className="grid h-9 w-8 place-items-center rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50" aria-label="+1">+</button>
                        </div>
                        {edit && was !== undefined && now !== was && <p className={`mt-0.5 text-[11px] font-bold ${now > was ? "text-emerald-600" : "text-amber-600"}`}>{now > was ? "+" : ""}{now - was}</p>}
                        {edit && (r.reserved?.[b.id] ?? 0) > 0 && <p className="mt-0.5 text-[11px] text-slate-500">{t("matrix.reserved", { n: r.reserved![b.id] })}</p>}
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
