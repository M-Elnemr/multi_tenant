"use client";

import { use, useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, toMinor } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Button, Card, ErrorText, Field, Input, Loading, PageHeader, Select, StatusBadge, Textarea } from "@/components/ui";
import { useRouter } from "next/navigation";

type Detail = {
  id: string; name: string; description: string; brand?: string; status: string;
  variants: { id: string; sku: string; priceMinor: number; comboKey: string; status: string; available: number }[];
  media: { id: string; url: string }[];
};

export default function EditProduct({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const router = useRouter();
  const { can } = useMe();
  const { data, loading, error, reload } = useApi<Detail>(`store/products/${id}`);
  const [f, setF] = useState<{ name: string; description: string; status: string } | null>(null);
  const [prices, setPrices] = useState<Record<string, string>>({});
  const form = f ?? (data ? { name: data.name, description: data.description, status: data.status } : null);
  const save = useAction(async () => {
    if (!form) return;
    await api(`store/products/${id}`, { method: "PATCH", body: form });
    for (const [vid, p] of Object.entries(prices)) await api(`store/variants/${vid}`, { method: "PATCH", body: { priceMinor: toMinor(p) } });
    setPrices({});
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
          <Field label={t("products.name")}><Input value={form.name} onChange={(e) => setF({ ...form, name: e.target.value })} required /></Field>
          <Field label={t("products.description")}><Textarea rows={4} value={form.description} onChange={(e) => setF({ ...form, description: e.target.value })} /></Field>
          <Field label={t("admin.status")}><Select value={form.status} onChange={(e) => setF({ ...form, status: e.target.value })}>{["ACTIVE", "DRAFT", "ARCHIVED"].map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}</Select></Field>
        </Card>
        <Card>
          <h2 className="mb-3 font-medium">{t("products.variants")}</h2>
          <table className="w-full text-sm"><tbody>{data.variants.map((v) => (
            <tr key={v.id} className="border-b last:border-0"><td className="py-2">{v.comboKey.replace(/\|/g, " / ").replace(/=/g, ": ") || "-"}<div className="font-mono text-xs text-slate-500">{v.sku}</div></td>
              <td className="py-2"><Input className="max-w-32" dir="ltr" value={prices[v.id] ?? fromMinor(v.priceMinor)} onChange={(e) => setPrices({ ...prices, [v.id]: e.target.value })} /></td>
              <td className="py-2 text-end text-slate-600">{t("products.stock")}: <b>{v.available}</b></td></tr>
          ))}</tbody></table>
          <p className="mt-2 text-xs text-slate-500">{t("products.stockHint")}</p>
        </Card>
        <ErrorText error={save.error ?? archive.error} />
        <div className="flex gap-3"><Button type="submit" loading={save.loading}>{t("common.save")}</Button>{can("product.delete") && <Button type="button" variant="danger" loading={archive.loading} onClick={() => archive.run()}>{t("products.archive")}</Button>}</div>
      </form>
    </>
  );
}
