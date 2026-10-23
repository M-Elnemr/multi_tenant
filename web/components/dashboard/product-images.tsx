"use client";

import { api } from "@/lib/client";
import { mediaUrl } from "@/lib/media";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { ImageUploader } from "../uploader";
import { Badge, Button, Card, ErrorText, Select } from "../ui";
import { useState } from "react";

export type ProductMedia = { id: string; url: string; altText?: string; mediaBase?: string | null; mediaExt?: string | null; optionValue?: string | null };
export type ColorOption = { name: string; attribute?: string; values: string[]; valueMeta?: { value: string; nameAr: string; nameEn: string }[] };

/** The values a picture can belong to: the colour option when the product has one, else its first option. */
export function colorValues(options: ColorOption[] | undefined, locale: string): { value: string; label: string }[] {
  const o = options?.find((x) => x.attribute === "COLOR") ?? options?.[0];
  return (o?.values ?? []).map((v) => { const m = o?.valueMeta?.find((x) => x.value === v); return { value: v, label: m ? (locale === "ar" ? m.nameAr : m.nameEn) : v }; });
}

/** Product pictures: add, remove, and choose the main one (the first picture is what shoppers see in lists). */
export function ProductImages({ id, media, options, onChanged }: { id: string; media: ProductMedia[]; options?: ColorOption[]; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const colors = colorValues(options, locale);
  const [forColor, setForColor] = useState("");   // the colour the next uploaded picture is for ("" = all colours)
  const add = useAction(async (fileId: string) => { await api(`store/products/${id}/media`, { body: { fileId, optionValue: forColor || undefined } }); onChanged(); });
  const recolor = useAction(async (m: ProductMedia, value: string) => { await api(`store/products/${id}/media/${m.id}/color`, { method: "PUT", body: { value } }); onChanged(); });
  const remove = useAction(async (m: ProductMedia) => { await api(`store/products/${id}/media/${m.id}`, { method: "DELETE" }); onChanged(); });
  const makeMain = useAction(async (m: ProductMedia) => { await api(`store/products/${id}/media/order`, { method: "PUT", body: { ids: [m.id] } }); onChanged(); });
  return (
    <Card className="space-y-3">
      <h2 className="font-medium">{t("images.title")} <span className="text-sm font-normal text-slate-500">({media.length}/10)</span></h2>
      {colors.length > 0 && <p className="text-xs text-slate-500">{t("images.colorHint")}</p>}
      <div className="flex flex-wrap gap-3">
        {media.map((m, i) => (
          <div key={m.id} className="w-28 space-y-1.5">
            <div className="relative aspect-square overflow-hidden rounded-xl border bg-slate-50">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={mediaUrl(m, "thumb") ?? ""} alt={m.altText ?? ""} className="h-full w-full object-cover" />
              {i === 0 && <span className="absolute start-1 top-1"><Badge tone="green">{t("images.main")}</Badge></span>}
            </div>
            {colors.length > 0 && (
              <Select value={m.optionValue ?? ""} onChange={(e) => void recolor.run(m, e.target.value)} aria-label={t("images.forColor")} className="!px-1.5 !py-1 !text-xs">
                <option value="">{t("images.allColors")}</option>
                {colors.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
              </Select>
            )}
            <div className="flex gap-1">
              {i > 0 && <Button type="button" size="sm" variant="secondary" className="flex-1 !px-1" loading={makeMain.loading} onClick={() => makeMain.run(m)}>★</Button>}
              <Button type="button" size="sm" variant="ghost" className="flex-1 !px-1" loading={remove.loading} onClick={() => remove.run(m)}>✕</Button>
            </div>
          </div>
        ))}
        {media.length < 10 && (
          <div className="w-28 space-y-1.5">
            <div className="grid aspect-square place-items-center rounded-xl border-2 border-dashed p-2"><ImageUploader category="PRODUCT_IMAGE" label={t("images.add")} onUploaded={(fileId) => add.run(fileId)} /></div>
            {colors.length > 0 && (
              <Select value={forColor} onChange={(e) => setForColor(e.target.value)} aria-label={t("images.forColor")} className="!px-1.5 !py-1 !text-xs">
                <option value="">{t("images.allColors")}</option>
                {colors.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
              </Select>
            )}
          </div>
        )}
      </div>
      <ErrorText error={add.error ?? remove.error ?? makeMain.error ?? recolor.error} />
    </Card>
  );
}
