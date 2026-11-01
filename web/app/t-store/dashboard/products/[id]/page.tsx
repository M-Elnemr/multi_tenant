"use client";

import { use, useMemo, useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, toMinor } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, Loading, PageHeader, Select, StatusBadge, Textarea } from "@/components/ui";
import { useRouter, useSearchParams } from "next/navigation";
import { PublishToExisting } from "@/components/dashboard/publish-to";
import { ProductExtras, type Extras } from "@/components/dashboard/product-extras";
import { ProductImages, type ColorOption, type ProductMedia } from "@/components/dashboard/product-images";
import { TaxonomyPicker, type TaxonomyNode } from "@/components/dashboard/taxonomy-picker";
import { OptionBuilder, toRequest, type OptionDraft } from "@/components/dashboard/option-builder";
import { VariantMatrix, combos, comboKey, draftsFrom, emptyRow, type MatrixRow } from "@/components/dashboard/variant-matrix";
import { useFieldErrors } from "@/lib/use-field-errors";

type Detail = {
  id: string; name: string; description: string; brand?: string; status: string; audience?: string; itemCondition?: string; taxonomy?: { id: number; slug: string; nameAr: string; nameEn: string; level: number }[]; badge?: string; tags?: string[]; specs?: { k: string; v: string }[]; sizeGuide?: string; isFeatured?: boolean;
  variants: { id: string; sku: string; barcode?: string | null; priceMinor: number; compareAtPriceMinor?: number | null; comboKey: string; status: string; available: number; optionValues?: Record<string, string>; stock?: { branchId: string; quantityOnHand: number; quantityReserved: number }[] }[];
  media: ProductMedia[];
  options?: (ColorOption & { name: string; attribute?: string; sizeScale?: string; values: string[] })[];
};

export default function EditProduct({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const router = useRouter();
  const publishFailed = useSearchParams().get("publish") === "failed";
  const { can } = useMe();
  const { data, loading, error, reload } = useApi<Detail>(`store/products/${id}`);
  const [picked, setPicked] = useState<TaxonomyNode | null>(null);
  const [f, setF] = useState<{ name: string; description: string; status: string; brand: string; audience: string; condition: string } | null>(null);
  const branches = useApi<{ id: string; name: string }[]>("store/branches");
  const fe = useFieldErrors();
  const [opts, setOpts] = useState<OptionDraft[] | null>(null);
  const [rows, setRows] = useState<Record<string, MatrixRow> | null>(null);
  const [saved, setSaved] = useState(false);
  const form = f ?? (data ? { name: data.name, description: data.description, status: data.status, brand: data.brand ?? "", audience: data.audience ?? "", condition: data.itemCondition ?? "NEW" } : null);
  // the saved options/variants turned into the editor's state (until the owner starts editing)
  const savedOpts = useMemo(() => draftsFrom(data?.options), [data]);
  const options = opts ?? savedOpts;
  const parsed = useMemo(() => options.map(toRequest).filter((o) => o.name && o.values.length), [options]);
  const savedRows = useMemo(() => {
    const out: Record<string, MatrixRow> = {};
    const names = (data?.options ?? []).map((o) => ({ name: o.name }));
    for (const v of data?.variants ?? []) {
      if (v.status !== "ACTIVE") continue;
      const stock: Record<string, string> = {}, onHand: Record<string, number> = {}, reserved: Record<string, number> = {};
      for (const s of v.stock ?? []) { stock[s.branchId] = String(s.quantityOnHand); onHand[s.branchId] = s.quantityOnHand; reserved[s.branchId] = s.quantityReserved; }
      out[comboKey(names, v.optionValues ?? {})] = { id: v.id, sku: v.sku, barcode: v.barcode ?? "", price: fromMinor(v.priceMinor), compareAt: v.compareAtPriceMinor ? fromMinor(v.compareAtPriceMinor) : "", stock, onHand, reserved };
    }
    return out;
  }, [data]);
  const matrixRows = rows ?? savedRows;
  const matrix = combos(parsed);
  const removed = (data?.variants ?? []).filter((v) => v.status === "ACTIVE" && !matrix.some((c) => matrixRows[comboKey(parsed, c)]?.id === v.id)).length;
  const save = useAction(async () => {
    if (!form) return;
    setSaved(false);
    const rowOf = (c: Record<string, string>) => matrixRows[comboKey(parsed, c)] ?? emptyRow();
    const local: Record<string, string | false> = { name: !form.name.trim() && t("srv.Product name is required") };
    matrix.forEach((c, i) => {
      const r = rowOf(c);
      if (!r.sku.trim()) local[`variants[${i}].sku`] = t("srv.SKU is required");
      if (r.price.trim() === "" || isNaN(Number(r.price))) local[`variants[${i}].priceMinor`] = t("matrix.priceRequired");
      if (r.compareAt.trim() && Number(r.compareAt) <= Number(r.price)) local[`variants[${i}].compareAtPriceMinor`] = t("srv.The old price must be higher than the price");
    });
    if (!fe.check(local)) return;
    try {
      await api(`store/products/${id}`, { method: "PATCH", body: { ...form, taxonomyId: picked?.id, audience: form.audience || undefined } });
      await api(`store/products/${id}/variants`, { method: "PUT", body: {
        options: parsed,
        variants: matrix.map((c) => { const r = rowOf(c); return { id: r.id, sku: r.sku.trim(), barcode: r.barcode.trim(), priceMinor: toMinor(r.price), compareAtPriceMinor: r.compareAt.trim() ? toMinor(r.compareAt) : null, optionValues: c,
          stock: (branches.data ?? []).filter((b) => r.stock[b.id] !== undefined && r.stock[b.id] !== "").map((b) => ({ branchId: b.id, quantity: Number(r.stock[b.id]) || 0 })) }; }),
      } });
    } catch (e) { fe.fromError(e); throw e; }
    setOpts(null); setRows(null); setF(null); setSaved(true);
    await reload();
  });
  const archive = useAction(async () => { await api(`store/products/${id}`, { method: "DELETE" }); router.replace("/dashboard/products"); });
  if (loading && !data) return <Loading />;
  if (!data || !form) return <ErrorText error={error} />;
  return (
    <>
      <PageHeader title={data.name} actions={<StatusBadge status={data.status} />} />
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-5">
        <Card className="space-y-4">
          <Field name="name" error={fe.err("name")} label={t("products.name")}><Input value={form.name} onChange={(e) => { setF({ ...form, name: e.target.value }); fe.clear("name"); }} /></Field>
          <Field name="taxonomyId" error={fe.err("taxonomyId")} label={t("products.category")} hint={t("taxonomy.hint")}>
            <TaxonomyPicker value={picked ?? (data.taxonomy?.length ? { id: data.taxonomy[data.taxonomy.length - 1].id, slug: data.taxonomy[data.taxonomy.length - 1].slug, name: "", breadcrumb: data.taxonomy.map((x) => (locale === "ar" ? x.nameAr : x.nameEn)).join(" › "), level: data.taxonomy.length, hasChildren: false, appliesAudience: true, sizeScales: [] } : null)} onChange={setPicked} />
          </Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t("extras.brand")}><Input value={form.brand} onChange={(e) => setF({ ...form, brand: e.target.value })} /></Field>
            <Field name="audience" error={fe.err("audience")} label={t("filter.for")}><Select value={form.audience} onChange={(e) => setF({ ...form, audience: e.target.value })}><option value="">—</option>{["MEN", "WOMEN", "BOYS", "GIRLS", "BABY", "ALL"].map((a) => <option key={a} value={a}>{t(`audience.${a}`)}</option>)}</Select></Field>
            <Field label={t("filter.condition")}><Select value={form.condition} onChange={(e) => setF({ ...form, condition: e.target.value })}>{["NEW", "USED", "REFURBISHED"].map((c) => <option key={c} value={c}>{t(`condition.${c}`)}</option>)}</Select></Field>
          </div>
          <Field label={t("products.description")}><Textarea rows={4} value={form.description} onChange={(e) => setF({ ...form, description: e.target.value })} /></Field>
          <Field label={t("admin.status")}><Select value={form.status} onChange={(e) => setF({ ...form, status: e.target.value })}>{["ACTIVE", "DRAFT", "ARCHIVED"].map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}</Select></Field>
        </Card>
        <Card className="space-y-3">
          <div data-field="options"><h2 className="font-semibold text-ink">{t("matrix.optionsTitle")}</h2><p className="text-xs text-slate-500">{t("matrix.editHint")}</p></div>
          {fe.errUnder("options") && <p className="text-sm font-medium text-red-600">{fe.errUnder("options")}</p>}
          <OptionBuilder options={options} onChange={(o) => { setOpts(o); if (!rows) setRows(savedRows); }} scales={data.options?.find((o) => o.attribute === "SIZE")?.sizeScale ? [data.options.find((o) => o.attribute === "SIZE")!.sizeScale!] : []} />
        </Card>
        <Card className="space-y-3">
          <div data-field="variants" className="flex flex-wrap items-center justify-between gap-2"><h2 className="font-semibold text-ink">{t("matrix.stockTitle")}</h2>
            {removed > 0 && <span className="rounded-full bg-amber-50 px-3 py-1 text-xs font-semibold text-amber-800">{t("matrix.willHide", { n: removed })}</span>}</div>
          {fe.err("variants") && <p className="text-sm font-medium text-red-600">{fe.err("variants")}</p>}
          <VariantMatrix edit options={parsed} rows={matrixRows} onRows={setRows} branches={branches.data ?? []} err={fe.err} errUnder={fe.errUnder} />
          <p className="text-xs text-slate-500">{t("matrix.stockNote")}</p>
        </Card>
        <ProductImages id={id} media={data.media} options={data.options} onChanged={reload} />
        <ProductExtras key={data.id + (data.badge ?? "")} id={id} initial={data as Extras} onSaved={reload} />
        {fe.any ? <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{t("form.fixMarked")}</div> : <ErrorText error={save.error ?? archive.error} />}
        {saved && !save.loading && <Alert tone="green">{t("matrix.saved")}</Alert>}
        <div className="sticky bottom-3 z-10 flex gap-3 rounded-2xl bg-white/90 p-2 shadow-lift backdrop-blur"><Button type="submit" loading={save.loading}>{t("common.save")}</Button>{can("product.delete") && <Button type="button" variant="danger" loading={archive.loading} onClick={() => archive.run()}>{t("products.archive")}</Button>}</div>
      </form>
      {can("integration.read") && data.status !== "ARCHIVED" && (
        <div className="mt-5 space-y-3">
          {publishFailed && <Alert tone="amber">{t("pub.someFailed")}</Alert>}
          <PublishToExisting productId={id} />
        </div>
      )}
    </>
  );
}
