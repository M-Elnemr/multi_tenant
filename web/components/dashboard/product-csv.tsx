"use client";

import { useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { api } from "@/lib/client";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { Menu } from "../overlay";
import { useToast } from "../toast";
import { Alert, ErrorText, Spinner } from "../ui";

type Result = { created: number; errors: { row: number; message: string }[] };

/**
 * Export the catalogue to a spreadsheet, or import products from one (name, brand, category "Men > Shirts", sku, price, compare_at_price, stock, description, status).
 * Gives a compact "Spreadsheet" menu button (for the page header) and a result panel (placed under the header by the page).
 */
export function useProductCsv(onImported: () => void) {
  const { t } = useI18n();
  const toast = useToast();
  const reduce = useReducedMotion();
  const file = useRef<HTMLInputElement>(null);
  const [result, setResult] = useState<Result | null>(null);
  const exp = useAction(async () => {
    const res = await fetch("/api/bff/store/products/export.csv", { credentials: "same-origin" });
    if (!res.ok) throw new Error("export");
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a"); a.href = url; a.download = "elmanassa-products.csv"; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
    toast(t("cat.csvExported"));
  });
  const imp = useAction(async (f: File) => {
    const r = await api<Result>("store/products/import", { body: { csv: await f.text() } });
    setResult(r);
    toast(t("csv.result", { n: r.created, e: r.errors.length }), { tone: r.errors.length ? "info" : "success" });
    onImported();
  });
  const sample = () => {
    const csv = "\\uFEFFname,brand,category,sku,price,compare_at_price,stock,description,status\\r\\nقميص قطن,نور,رجالي > قمصان,SH-1,450,600,10,قميص قطن مريح,ACTIVE\\r\\n";
    const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" })); a.download = "products-template.csv"; a.click();
  };
  const busy = exp.loading || imp.loading;
  const error = exp.error ?? imp.error;
  const button = (
    <>
      <Menu label={t("csv.title")} items={[
        { label: t("csv.export"), icon: "external", onSelect: () => void exp.run() },
        { label: t("csv.import"), icon: "file", onSelect: () => file.current?.click() },
        "divider",
        { label: t("csv.template"), icon: "list", onSelect: sample },
      ]} trigger={({ toggle, open }) => (
        <button type="button" onClick={toggle} aria-expanded={open} className="inline-flex h-10 items-center gap-1.5 rounded-[11px] bg-surface px-3.5 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:text-ink pointer-coarse:min-h-11">
          {busy ? <Spinner className="h-4 w-4" /> : <Icon name="file" className="h-4 w-4" />}
          <span className="hidden sm:inline">{t("csv.title")}</span>
          <Icon name="chevron" className={`h-3.5 w-3.5 transition-transform ${open ? "-rotate-90" : "rotate-90"}`} />
        </button>
      )} />
      <input ref={file} type="file" accept=".csv,text/csv" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) void imp.run(f); e.target.value = ""; }} />
    </>
  );
  const panel = <CsvPanel result={result} error={error} onClose={() => { setResult(null); exp.setError(null); imp.setError(null); }} reduce={!!reduce} />;
  return { button, panel };
}

/** The import result (or an export / import error) under the page header. */
function CsvPanel({ result, error, onClose, reduce }: { result: Result | null; error: unknown; onClose: () => void; reduce: boolean }) {
  const { t } = useI18n();
  const show = !!result || !!error;
  return (
    <AnimatePresence>
      {show && (
        <motion.div initial={reduce ? false : { opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.2 }}
          className="mb-5 rounded-[14px] bg-surface p-4 shadow-card">
          <div className="mb-2 flex items-center justify-between gap-2">
            <p className="flex items-center gap-2 text-[13px] font-semibold text-ink"><Icon name="file" className="h-4 w-4 text-muted" />{t("csv.title")}</p>
            <button type="button" onClick={onClose} aria-label={t("common.close")} className="grid h-8 w-8 place-items-center rounded-[9px] text-muted hover:bg-surface-2 hover:text-ink"><Icon name="close" className="h-4 w-4" /></button>
          </div>
          <ErrorText error={error} />
          {result && (
            <div className="space-y-2 text-sm">
              <Alert tone={result.errors.length ? "amber" : "green"}>{t("csv.result", { n: result.created, e: result.errors.length })}</Alert>
              {result.errors.length > 0 && (
                <ul className="max-h-40 space-y-1 overflow-y-auto rounded-[12px] bg-surface-2 p-3 text-[13px] text-red-700">
                  {result.errors.slice(0, 50).map((e, i) => <li key={i} className="flex gap-2"><span className="shrink-0 font-semibold tnum">{t("csv.row", { n: e.row })}</span><span className="min-w-0">{e.message}</span></li>)}
                </ul>
              )}
            </div>
          )}
        </motion.div>
      )}
    </AnimatePresence>
  );
}
