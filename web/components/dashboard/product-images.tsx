"use client";

import { api } from "@/lib/client";
import { mediaUrl } from "@/lib/media";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { useToast } from "../toast";
import { ImageUploader } from "../uploader";
import { ErrorText, Select } from "../ui";
import { useState } from "react";
import { SideCard } from "./product-editor-bits";

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
  const toast = useToast();
  const colors = colorValues(options, locale);
  const [forColor, setForColor] = useState("");   // the colour the next uploaded picture is for ("" = all colours)
  const add = useAction(async (fileId: string) => { await api(`store/products/${id}/media`, { body: { fileId, optionValue: forColor || undefined } }); toast(t("cat.imageAdded")); onChanged(); });
  const recolor = useAction(async (m: ProductMedia, value: string) => { await api(`store/products/${id}/media/${m.id}/color`, { method: "PUT", body: { value } }); onChanged(); });
  const remove = useAction(async (m: ProductMedia) => { await api(`store/products/${id}/media/${m.id}`, { method: "DELETE" }); toast(t("cat.imageRemoved")); onChanged(); });
  const makeMain = useAction(async (m: ProductMedia) => { await api(`store/products/${id}/media/order`, { method: "PUT", body: { ids: [m.id] } }); onChanged(); });
  return (
    <SideCard title={`${t("images.title")} (${media.length}/10)`}>
      {colors.length > 0 && <p className="mb-3 text-[12px] text-muted">{t("images.colorHint")}</p>}
      <div className="grid grid-cols-2 gap-2.5">
        {media.map((m, i) => (
          <div key={m.id} className="space-y-1.5">
            <div className="relative aspect-square overflow-hidden rounded-[12px] bg-surface-2 ring-1 ring-line">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={mediaUrl(m, "thumb") ?? ""} alt={m.altText ?? ""} className="h-full w-full object-cover" />
              {i === 0 && <span className="absolute start-1.5 top-1.5 rounded-full bg-emerald-500 px-2 py-0.5 text-[10.5px] font-bold text-white">{t("images.main")}</span>}
              <div className="absolute inset-x-1.5 bottom-1.5 flex justify-end gap-1">
                {i > 0 && <button type="button" disabled={makeMain.loading} onClick={() => void makeMain.run(m)} aria-label={t("images.main")} className="grid h-8 w-8 place-items-center rounded-full bg-surface/95 text-ink shadow ring-1 ring-line transition hover:scale-105 pointer-coarse:h-10 pointer-coarse:w-10"><Icon name="star" className="h-4 w-4" /></button>}
                <button type="button" disabled={remove.loading} onClick={() => void remove.run(m)} aria-label={t("common.remove")} className="grid h-8 w-8 place-items-center rounded-full bg-surface/95 text-red-600 shadow ring-1 ring-line transition hover:scale-105 pointer-coarse:h-10 pointer-coarse:w-10"><Icon name="close" className="h-4 w-4" /></button>
              </div>
            </div>
            {colors.length > 0 && (
              <Select value={m.optionValue ?? ""} onChange={(e) => void recolor.run(m, e.target.value)} aria-label={t("images.forColor")} className="!px-1.5 !py-1 !text-xs">
                <option value="">{t("images.allColors")}</option>
                {colors.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
              </Select>
            )}
          </div>
        ))}
      </div>
      {media.length < 10 && (
        <div className={`space-y-2 rounded-[12px] border border-dashed border-line-strong p-3 text-center ${media.length ? "mt-3" : ""}`}>
          <ImageUploader category="PRODUCT_IMAGE" label={t("images.add")} onUploaded={(fileId) => void add.run(fileId)} />
          {colors.length > 0 && (
            <Select value={forColor} onChange={(e) => setForColor(e.target.value)} aria-label={t("images.forColor")} className="!px-1.5 !py-1 !text-xs">
              <option value="">{t("images.allColors")}</option>
              {colors.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
            </Select>
          )}
        </div>
      )}
      <ErrorText error={add.error ?? remove.error ?? makeMain.error ?? recolor.error} />
    </SideCard>
  );
}
