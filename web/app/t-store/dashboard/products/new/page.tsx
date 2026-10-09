"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { toMinor } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { ImageUploader } from "@/components/uploader";
import { Button, Card, ErrorText, Field, Input, PageHeader, Select, Textarea } from "@/components/ui";

type Branch = { id: string; name: string };
type Category = { id: string; name: string };
type Opt = { name: string; values: string };
type Row = { sku: string; price: string; stock: string };

function combos(options: { name: string; values: string[] }[]): Record<string, string>[] {
  return options.reduce<Record<string, string>[]>((acc, o) => acc.flatMap((c) => o.values.map((v) => ({ ...c, [o.name]: v }))), [{}]);
}

/** Create a product with options (size, colour...). The matrix of variants is generated for you: just fill in SKU, price and stock. */
import { buildTree, type ShopCategory } from "@/components/shop/types";

/** Categories in tree order with their depth, so the picker shows the hierarchy. */
function treeOptions(flat: { id: string; name: string; parentId?: string | null }[]) {
  const walk = (nodes: ReturnType<typeof buildTree>, depth: number): { id: string; name: string; depth: number }[] => nodes.flatMap((n) => [{ id: n.id, name: n.name, depth }, ...walk(n.children, depth + 1)]);
  return walk(buildTree(flat as ShopCategory[]), 0);
}

export default function NewProduct() {
  const t = useT();
  const router = useRouter();
  const branches = useApi<Branch[]>("store/branches");
  const categories = useApi<Category[]>("store/categories");
  const [f, setF] = useState({ name: "", description: "", categoryId: "", brand: "", status: "ACTIVE", branchId: "" });
  const [options, setOptions] = useState<Opt[]>([]);
  const [rows, setRows] = useState<Record<string, Row>>({});
  const [images, setImages] = useState<string[]>([]);

  const parsed = useMemo(() => options.map((o) => ({ name: o.name.trim(), values: o.values.split(",").map((v) => v.trim()).filter(Boolean) })).filter((o) => o.name && o.values.length), [options]);
  const matrix = useMemo(() => combos(parsed), [parsed]);
  const key = (c: Record<string, string>) => parsed.map((o) => c[o.name]).join("|");
  const branchId = f.branchId || branches.data?.[0]?.id || "";
  const row = (c: Record<string, string>): Row => rows[key(c)] ?? { sku: "", price: "", stock: "0" };

  const save = useAction(async () => {
    const variants = matrix.map((c) => { const r = row(c); return { sku: r.sku.trim(), priceMinor: toMinor(r.price), optionValues: c, stock: [{ branchId, quantity: Number(r.stock) || 0 }] }; });
    const p = await api<{ id: string }>("store/products", { body: { name: f.name, description: f.description, categoryId: f.categoryId || undefined, brand: f.brand || undefined, status: f.status, options: parsed, variants, media: images.map((fileId) => ({ fileId })) } });
    router.replace(`/dashboard/products/${p.id}`);
  });

  return (
    <>
      <PageHeader title={t("products.add")} />
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-5">
        <Card className="space-y-4">
          <Field label={t("products.name")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>
          <Field label={t("products.description")}><Textarea rows={4} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t("products.category")}><Select value={f.categoryId} onChange={(e) => setF({ ...f, categoryId: e.target.value })}><option value="">-</option>{treeOptions(categories.data ?? []).map((c) => <option key={c.id} value={c.id}>{"— ".repeat(c.depth)}{c.name}</option>)}</Select></Field>
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
          <div className="flex items-center justify-between"><h2 className="font-medium">{t("products.options")}</h2><Button type="button" variant="secondary" size="sm" onClick={() => setOptions([...options, { name: "", values: "" }])}>{t("products.addOption")}</Button></div>
          <p className="text-xs text-slate-500">{t("products.optionsHint")}</p>
          {options.map((o, i) => (
            <div key={i} className="grid gap-3 sm:grid-cols-[1fr_2fr_auto]">
              <Input placeholder={t("products.optionName")} value={o.name} onChange={(e) => setOptions(options.map((x, k) => (k === i ? { ...x, name: e.target.value } : x)))} />
              <Input placeholder={t("products.optionValues")} value={o.values} onChange={(e) => setOptions(options.map((x, k) => (k === i ? { ...x, values: e.target.value } : x)))} />
              <Button type="button" variant="ghost" onClick={() => setOptions(options.filter((_, k) => k !== i))}>✕</Button>
            </div>
          ))}
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
        <Button type="submit" loading={save.loading} disabled={!branchId}>{t("common.save")}</Button>
      </form>
    </>
  );
}
