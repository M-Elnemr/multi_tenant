"use client";

import { useRef, useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Button, ErrorText } from "../ui";

type Result = { created: number; errors: { row: number; message: string }[] };

/** Export the catalogue to a spreadsheet, or import products from one (name, brand, category "Men > Shirts", sku, price, compare_at_price, stock, description, status). */
export function ProductCsv({ onImported }: { onImported: () => void }) {
  const { t } = useI18n();
  const file = useRef<HTMLInputElement>(null);
  const [result, setResult] = useState<Result | null>(null);
  const exp = useAction(async () => {
    const res = await fetch("/api/bff/store/products/export.csv", { credentials: "same-origin" });
    if (!res.ok) throw new Error("export");
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a"); a.href = url; a.download = "products.csv"; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  });
  const imp = useAction(async (f: File) => { setResult(await api<Result>("store/products/import", { body: { csv: await f.text() } })); onImported(); });
  const sample = () => {
    const csv = "\\uFEFFname,brand,category,sku,price,compare_at_price,stock,description,status\\r\\nقميص قطن,نور,رجالي > قمصان,SH-1,450,600,10,قميص قطن مريح,ACTIVE\\r\\n";
    const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" })); a.download = "products-template.csv"; a.click();
  };
  return (
    <div className="mb-5 space-y-3 rounded-2xl border border-dashed border-slate-300 bg-white p-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="me-2 text-sm font-semibold">📄 {t("csv.title")}</span>
        <Button size="sm" variant="secondary" loading={exp.loading} onClick={() => exp.run()}>{t("csv.export")}</Button>
        <Button size="sm" variant="secondary" loading={imp.loading} onClick={() => file.current?.click()}>{t("csv.import")}</Button>
        <Button size="sm" variant="ghost" onClick={sample}>{t("csv.template")}</Button>
        <input ref={file} type="file" accept=".csv,text/csv" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) void imp.run(f); e.target.value = ""; }} />
      </div>
      <ErrorText error={exp.error ?? imp.error} />
      {result && (
        <div className="space-y-2 text-sm">
          <Alert tone={result.errors.length ? "amber" : "green"}>{t("csv.result", { n: result.created, e: result.errors.length })}</Alert>
          {result.errors.length > 0 && <ul className="max-h-40 list-disc space-y-0.5 overflow-y-auto ps-5 text-red-700">{result.errors.slice(0, 50).map((e, i) => <li key={i}>{t("csv.row", { n: e.row })}: {e.message}</li>)}</ul>}
        </div>
      )}
    </div>
  );
}
