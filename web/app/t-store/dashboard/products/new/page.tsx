"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { toMinor } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { ImageUploader } from "@/components/uploader";
import { Button, Card, ErrorText, Field, Input, PageHeader, Select, Textarea } from "@/components/ui";
import { PublishToNew, publishNew, usePublishAccounts, type DraftRow, type PublishState } from "@/components/dashboard/publish-to";
import { OptionBuilder, toRequest, type OptionDraft } from "@/components/dashboard/option-builder";
import { TaxonomyPicker, type TaxonomyNode } from "@/components/dashboard/taxonomy-picker";
import { VariantMatrix, combos, comboKey, emptyRow, type MatrixRow } from "@/components/dashboard/variant-matrix";
import { useFieldErrors } from "@/lib/use-field-errors";

type Branch = { id: string; name: string };

export default function NewProduct() {
  const t = useT();
  const router = useRouter();
  const branches = useApi<Branch[]>("store/branches");
  const [f, setF] = useState({ name: "", description: "", brand: "", status: "ACTIVE", audience: "", condition: "NEW" });
  const [picked, setPicked] = useState<TaxonomyNode | null>(null);
  const [options, setOptions] = useState<OptionDraft[]>([]);
  const [rows, setRows] = useState<Record<string, MatrixRow>>({});
  const fe = useFieldErrors();
  const [images, setImages] = useState<{ id: string; color: string }[]>([]);   // color: the option value this picture is for ("" = all)
  const [publish, setPublish] = useState<PublishState>({});
  const channels = usePublishAccounts();

  const parsed = useMemo(() => options.map(toRequest).filter((o) => o.name && o.values.length), [options]);
  const matrix = useMemo(() => combos(parsed), [parsed]);
  // the values a picture can be for: the colour option when there is one, else the first option
  const colorChoices = useMemo(() => (parsed.find((o) => /color|colour|لون/i.test(o.name)) ?? parsed[0])?.values ?? [], [parsed]);
  const key = (c: Record<string, string>) => comboKey(parsed, c);
  const branchList = branches.data ?? [];
  const branchId = branchList[0]?.id ?? "";
  const row = (c: Record<string, string>): MatrixRow => rows[key(c)] ?? emptyRow();
  const stockOf = (r: MatrixRow) => String(branchList.reduce((s, b) => s + (Number(r.stock[b.id]) || 0), 0));

  const draftRows: DraftRow[] = matrix.map((c) => { const r = row(c); return { key: key(c), label: Object.values(c).join(" / "), sku: r.sku, price: r.price, stock: stockOf(r) }; });
  const save = useAction(async () => {
    const local: Record<string, string | false> = {
      name: !f.name.trim() && t("srv.Product name is required"),
      taxonomyId: f.status === "ACTIVE" && !picked && t("srv.Choose a category from the list"),
      audience: !!picked?.appliesAudience && f.status === "ACTIVE" && !f.audience && t("srv.Choose who this product is for (men, women, kids...)"),
    };
    matrix.forEach((c, i) => {
      const r = row(c);
      if (!r.sku.trim()) local[`variants[${i}].sku`] = t("srv.SKU is required");
      if (r.price.trim() === "" || isNaN(Number(r.price))) local[`variants[${i}].priceMinor`] = t("matrix.priceRequired");
      if (r.compareAt.trim() && Number(r.compareAt) <= Number(r.price)) local[`variants[${i}].compareAtPriceMinor`] = t("srv.The old price must be higher than the price");
    });
    if (!fe.check(local)) return;
    const variants = matrix.map((c) => { const r = row(c); return { sku: r.sku.trim(), barcode: r.barcode.trim() || undefined, priceMinor: toMinor(r.price), compareAtPriceMinor: r.compareAt.trim() ? toMinor(r.compareAt) : undefined, optionValues: c, stock: branchList.map((b) => ({ branchId: b.id, quantity: Number(r.stock[b.id]) || 0 })).filter((s) => s.quantity > 0 || s.branchId === branchId) }; });
    let p: { id: string; variants: { id: string; sku: string }[] };
    try {
      p = await api<{ id: string; variants: { id: string; sku: string }[] }>("store/products", { body: { name: f.name, description: f.description, taxonomyId: picked?.id, audience: f.audience || undefined, condition: f.condition, brand: f.brand || undefined, status: f.status, options: parsed, variants, media: images.map((im) => ({ fileId: im.id, optionValue: colorChoices.includes(im.color) ? im.color : undefined })) } });
    } catch (e) { fe.fromError(e); throw e; }
    let failed = false;
    try {
      const res = await publishNew(p.id, p.variants ?? [], draftRows, publish, channels.accounts);
      failed = res.some((r) => !r.ok);
    } catch { failed = true; }   // the product is saved either way; the product page shows where it is published
    router.replace(`/dashboard/products/${p.id}${failed ? "?publish=failed" : ""}`);
  });

  return (
    <>
      <PageHeader title={t("products.add")} />
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-5">
        <Card className="space-y-4">
          <Field name="name" error={fe.err("name")} label={t("products.name")}><Input value={f.name} onChange={(e) => { setF({ ...f, name: e.target.value }); fe.clear("name"); }} /></Field>
          <Field label={t("products.description")}><Textarea rows={4} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
          <Field name="taxonomyId" error={fe.err("taxonomyId")} label={t("products.category")} hint={t("taxonomy.hint")}><TaxonomyPicker value={picked} onChange={(n) => { setPicked(n); fe.clear("taxonomyId"); if (!n.appliesAudience) setF((x) => ({ ...x, audience: "" })); }} /></Field>
          <div className="grid gap-4 sm:grid-cols-4">
            {picked?.appliesAudience && <Field name="audience" error={fe.err("audience")} label={t("filter.for")}><Select value={f.audience} onChange={(e) => setF({ ...f, audience: e.target.value })}><option value="">—</option>{["MEN", "WOMEN", "BOYS", "GIRLS", "BABY", "ALL"].map((a) => <option key={a} value={a}>{t(`audience.${a}`)}</option>)}</Select></Field>}
            <Field label={t("filter.condition")}><Select value={f.condition} onChange={(e) => setF({ ...f, condition: e.target.value })}>{["NEW", "USED", "REFURBISHED"].map((c) => <option key={c} value={c}>{t(`condition.${c}`)}</option>)}</Select></Field>
            <Field label={t("products.brand")}><Input value={f.brand} onChange={(e) => setF({ ...f, brand: e.target.value })} /></Field>
            <Field label={t("admin.status")}><Select value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><option value="ACTIVE">{t("status.ACTIVE")}</option><option value="DRAFT">{t("status.DRAFT")}</option></Select></Field>
          </div>
          <div>
            <p className="mb-2 text-sm font-medium">{t("products.images")}</p>
            <div className="flex flex-wrap items-center gap-3">
              {images.map((im) => (
                <div key={im.id} className="w-24 space-y-1">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={`/api/bff/files/${im.id}/content?variant=thumb`} alt="" className="h-16 w-16 rounded-lg object-cover" />
                  {colorChoices.length > 0 && (
                    <Select value={im.color} onChange={(e) => setImages((x) => x.map((y) => (y.id === im.id ? { ...y, color: e.target.value } : y)))} aria-label={t("images.forColor")} className="!px-1.5 !py-1 !text-xs">
                      <option value="">{t("images.allColors")}</option>
                      {colorChoices.map((c) => <option key={c} value={c}>{c}</option>)}
                    </Select>
                  )}
                </div>
              ))}
              <ImageUploader category="PRODUCT_IMAGE" onUploaded={(id) => setImages((x) => [...x, { id, color: "" }])} />
            </div>
          </div>
        </Card>
        <Card className="space-y-3" >
          <h2 className="font-medium" data-field="options">{t("products.options")}</h2>
          {fe.errUnder("options") && <p className="text-sm font-medium text-red-600">{fe.errUnder("options")}</p>}
          <p className="text-xs text-slate-500">{t("options.hint")}</p>
          <OptionBuilder options={options} onChange={setOptions} scales={picked?.sizeScales ?? []} />
        </Card>
        <Card className="space-y-3">
          <div data-field="variants"><h2 className="font-medium">{t("products.variants")}</h2><p className="text-xs text-slate-500">{t("matrix.hint")}</p></div>
          {fe.err("variants") && <p className="text-sm font-medium text-red-600">{fe.err("variants")}</p>}
          <VariantMatrix options={parsed} rows={rows} onRows={setRows} branches={branchList} err={fe.err} errUnder={fe.errUnder} baseSku={f.name ? f.name.replace(/[^A-Za-z0-9]/g, "").slice(0, 8) : ""} />
        </Card>
        {draftRows.length > 0 && <PublishToNew rows={draftRows} value={publish} onChange={setPublish} />}
        {fe.any ? <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{t("form.fixMarked")}</div> : <ErrorText error={save.error} />}
        <Button type="submit" loading={save.loading} disabled={!branchId}>{t("common.save")}</Button>
      </form>
    </>
  );
}
