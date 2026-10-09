"use client";

import { api } from "@/lib/client";
import { mediaUrl } from "@/lib/media";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { ImageUploader } from "../uploader";
import { Badge, Button, Card, ErrorText } from "../ui";

export type ProductMedia = { id: string; url: string; altText?: string; mediaBase?: string | null; mediaExt?: string | null };

/** Product pictures: add, remove, and choose the main one (the first picture is what shoppers see in lists). */
export function ProductImages({ id, media, onChanged }: { id: string; media: ProductMedia[]; onChanged: () => void }) {
  const { t } = useI18n();
  const add = useAction(async (fileId: string) => { await api(`store/products/${id}/media`, { body: { fileId } }); onChanged(); });
  const remove = useAction(async (m: ProductMedia) => { await api(`store/products/${id}/media/${m.id}`, { method: "DELETE" }); onChanged(); });
  const makeMain = useAction(async (m: ProductMedia) => { await api(`store/products/${id}/media/order`, { method: "PUT", body: { ids: [m.id] } }); onChanged(); });
  return (
    <Card className="space-y-3">
      <h2 className="font-medium">{t("images.title")} <span className="text-sm font-normal text-slate-500">({media.length}/10)</span></h2>
      <div className="flex flex-wrap gap-3">
        {media.map((m, i) => (
          <div key={m.id} className="w-28 space-y-1.5">
            <div className="relative aspect-square overflow-hidden rounded-xl border bg-slate-50">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={mediaUrl(m, "thumb") ?? ""} alt={m.altText ?? ""} className="h-full w-full object-cover" />
              {i === 0 && <span className="absolute start-1 top-1"><Badge tone="green">{t("images.main")}</Badge></span>}
            </div>
            <div className="flex gap-1">
              {i > 0 && <Button type="button" size="sm" variant="secondary" className="flex-1 !px-1" loading={makeMain.loading} onClick={() => makeMain.run(m)}>★</Button>}
              <Button type="button" size="sm" variant="ghost" className="flex-1 !px-1" loading={remove.loading} onClick={() => remove.run(m)}>✕</Button>
            </div>
          </div>
        ))}
        {media.length < 10 && <div className="grid w-28 place-items-center rounded-xl border-2 border-dashed p-2"><ImageUploader category="PRODUCT_IMAGE" label={t("images.add")} onUploaded={(fileId) => add.run(fileId)} /></div>}
      </div>
      <ErrorText error={add.error ?? remove.error ?? makeMain.error} />
    </Card>
  );
}
