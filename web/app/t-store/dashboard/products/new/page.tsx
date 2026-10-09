"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { toMinor } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { ImageUploader } from "@/components/uploader";
import { Button, Card, ErrorText, Field, Input, PageHeader, Select, Textarea } from "@/components/ui";
import { OptionBuilder, toRequest, type OptionDraft } from "@/components/dashboard/option-builder";
import { TaxonomyPicker, type TaxonomyNode } from "@/components/dashboard/taxonomy-picker";

type Branch = { id: string; name: string };
type Row = { sku: string; price: string; stock: string };

function combos(options: { name: string; values: string[] }[]): Record<string, string>[] {
  return options.reduce<Record<string, string>[]>((acc, o) => acc.flatMap((c) => o.values.map((v) => ({ ...c, [o.name]: v }))), [{}]);
}

export default function NewProduct() {
  const t = useT();
  const router = useRouter();
  const branches = useApi<Branch[]>("store/branches");
  const [f, setF] = useState({ name: "", description: "", brand: "", status: "ACTIVE", branchId: "", audience: "", condition: "NEW" });
  const [picked, setPicked] = useState<TaxonomyNode | null>(null);
  const [options, setOptions] = useState<OptionDraft[]>([]);
  const [rows, setRows] = useState<Record<string, Row>>({});
  const [images, setImages] = useState<string[]>([]);

  const parsed = useMemo(() => options.map(toRequest).filter((o) => o.name && o.values.length), [options]);
  const matrix = useMemo(() => combos(parsed), [parsed]);
  const key = (c: Record<string, string>) => parsed.map((o) => c[o.name]).join("|");
  const branchId = f.branchId || branches.data?.[0]?.id || "";
  const row = (c: Record<string, string>): Row => rows[key(c)] ?? { sku: "", price: "", stock: "0" };

  const save = useAction(async () => {
    const variants = matrix.map((c) => { const r = row(c); return { sku: r.sku.trim(), priceMinor: toMinor(r.price), optionValues: c, stock: [{ branchId, quantity: Number(r.stock) || 0 }] }; });
    const p = await api<{ id: string }>("store/products", { body: { name: f.name, description: f.description, taxonomyId: picked?.id, audience: f.audience || undefined, condition: f.condition, brand: f.brand || undefined, status: f.status, options: parsed, variants, media: images.map((fileId) => ({ fileId })) } });
    router.replace(`/dashboard/products/${p.id}`);
  });

  return (
    <>
      <PageHeader title={t("products.add")} />
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-5">
        <Card className="space-y-4">
          <Field label={t("products.name")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>
          <Field label={t("products.description")}><Textarea rows={4} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
          <Field label={t("products.category")} hint={t("taxonomy.hint")}><TaxonomyPicker value={picked} onChange={(n) => { setPicked(n); if (!n.appliesAudience) setF((x) => ({ ...x, audience: "" })); }} /></Field>
          <div className="grid gap-4 sm:grid-cols-4">
            {picked?.appliesAudience && <Field label={t("filter.for")}><Select value={f.audience} onChange={(e) => setF({ ...f, audience: e.target.value })} required><option value="">—</option>{["MEN", "WOMEN", "BOYS", "GIRLS", "BABY", "ALL"].map((a) => <option key={a} value={a}>{t(`audience.${a}`)}</option>)}</Select></Field>}
            <Field label={t("filter.condition")}><Select value={f.condition} onChange={(e) => setF({ ...f, condition: e.target.value })}>{["NEW", "USED", "REFURBISHED"].map((c) => <option key={c} value={c}>{t(`condition.${c}`)}</option>)}</Select></Field>
            <Field label={t("products.brand")}><Input value={f.brand} onChange={(e) => setF({ ...f, brand: e.target.value })} /></Field>
            <Field label={t("admin.status")}><Select value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><option value="ACTIVE">{t("status.ACTIVE")}</option><option value="DRAFT">{t("status.DRAFT")}</option></Select></Field>
          </div>
          <div>
            <p className="mb-2 text-sm font-medium">{t("products.images")}</p>
            <div className="flex flex-wrap items-center gap-3">
              {images.map((id) => (
                // eslint-disable-next-line @next/next/no-img-element
                <img key={id} src={`/api/bff/files/${id}/content?variant=thumb`} alt="" className="h-16 w-16 rounded-lg object-cover" />
              ))}
              <ImageUploader category="PRODUCT_IMAGE" onUploaded={(id) => setImages((x) => [...x, id])} />
            </div>
          </div>
        </Card>
        <Card className="space-y-3">
          <h2 className="font-medium">{t("products.options")}</h2>
          <p className="text-xs text-slate-500">{t("options.hint")}</p>
          <OptionBuilder options={options} onChange={setOptions} scales={picked?.sizeScales ?? []} />
        </Card>
        <Card className="space-y-3">
          <div className="flex items-center justify-between"><h2 className="font-medium">{t("products.variants")}</h2>
            {(branches.data?.length ?? 0) > 1 && <Select className="max-w-48" value={branchId} onChange={(e) => setF({ ...f, branchId: e.target.value })}>{branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select>}
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm"><thead><tr className="text-start text-xs text-slate-500"><th className="p-1 text-start">{parsed.length ? t("products.combination") : ""}</th><th className="p-1 text-start">SKU</th><th className="p-1 text-start">{t("products.price")}</th><th className="p-1 text-start">{t("products.stock")}</th></tr></thead>
              <tbody>{matrix.map((c) => {
                const r = row(c);
                const set = (patch: Partial<Row>) => setRows({ ...rows, [key(c)]: { ...r, ...patch } });
                return (
                  <tr key={key(c)}>
                    <td className="p-1 text-slate-600">{Object.values(c).join(" / ")}</td>
                    <td className="p-1"><Input value={r.sku} onChange={(e) => set({ sku: e.target.value })} required dir="ltr" /></td>
                    <td className="p-1"><Input value={r.price} onChange={(e) => set({ price: e.target.value })} required inputMode="decimal" dir="ltr" /></td>
                    <td className="p-1"><Input value={r.stock} onChange={(e) => set({ stock: e.target.value })} inputMode="numeric" dir="ltr" /></td>
                  </tr>
                );
              })}</tbody></table>
          </div>
        </Card>
        <ErrorText error={save.error} />
        <Button type="submit" loading={save.loading} disabled={!branchId || (f.status === "ACTIVE" && !picked)}>{t("common.save")}</Button>
      </form>
    </>
  );
}
