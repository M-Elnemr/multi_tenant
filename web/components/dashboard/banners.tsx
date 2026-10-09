"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { ImageUploader } from "../uploader";
import { Badge, Button, Card, ErrorText, Field, Input } from "../ui";

type Banner = { id: string; imageFileId: string; title: string; subtitle: string; linkUrl: string; isActive: boolean };

/** Homepage banners (up to 8): a wide picture, an optional headline and a link such as /products?onSale=true. */
export function BannersCard() {
  const { t } = useI18n();
  const list = useApi<Banner[]>("store/banners");
  const [draft, setDraft] = useState({ imageFileId: "", title: "", subtitle: "", linkUrl: "" });
  const add = useAction(async () => { await api("store/banners", { body: draft }); setDraft({ imageFileId: "", title: "", subtitle: "", linkUrl: "" }); await list.reload(); });
  const toggle = useAction(async (b: Banner) => { await api(`store/banners/${b.id}`, { method: "PATCH", body: { isActive: !b.isActive } }); await list.reload(); });
  const del = useAction(async (b: Banner) => { await api(`store/banners/${b.id}`, { method: "DELETE" }); await list.reload(); });
  return (
    <Card className="space-y-4">
      <div><h2 className="font-semibold">🖼️ {t("banners.title")}</h2><p className="text-sm text-slate-500">{t("banners.hint")}</p></div>
      <ul className="space-y-3">
        {(list.data ?? []).map((b) => (
          <li key={b.id} className="flex items-center gap-3 rounded-xl border p-2.5">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={`/api/bff/files/${b.imageFileId}/content?variant=thumb`} alt="" className="h-14 w-24 rounded-lg object-cover" />
            <div className="min-w-0 flex-1 text-sm"><p className="truncate font-semibold">{b.title || t("banners.noTitle")} {!b.isActive && <Badge tone="slate">{t("common.hidden")}</Badge>}</p><p dir="ltr" className="truncate text-xs text-slate-500">{b.linkUrl}</p></div>
            <Button size="sm" variant="ghost" onClick={() => toggle.run(b)}>{b.isActive ? t("common.hide") : t("common.show")}</Button>
            <Button size="sm" variant="ghost" onClick={() => del.run(b)}>{t("common.delete")}</Button>
          </li>
        ))}
      </ul>
      <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="space-y-3 rounded-xl bg-slate-50 p-4">
        <div className="flex items-center gap-3">
          {draft.imageFileId && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={`/api/bff/files/${draft.imageFileId}/content?variant=thumb`} alt="" className="h-14 w-24 rounded-lg object-cover" />
          )}
          <ImageUploader category="PRODUCT_IMAGE" label={t("banners.upload")} onUploaded={(id) => setDraft({ ...draft, imageFileId: id })} />
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label={t("banners.headline")}><Input value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.target.value })} maxLength={120} /></Field>
          <Field label={t("banners.subtitle")}><Input value={draft.subtitle} onChange={(e) => setDraft({ ...draft, subtitle: e.target.value })} maxLength={200} /></Field>
          <Field label={t("banners.link")}><Input dir="ltr" value={draft.linkUrl} onChange={(e) => setDraft({ ...draft, linkUrl: e.target.value })} placeholder="/products?onSale=true" /></Field>
        </div>
        <ErrorText error={add.error ?? toggle.error ?? del.error} />
        <Button type="submit" size="sm" loading={add.loading} disabled={!draft.imageFileId}>{t("common.add")}</Button>
      </form>
    </Card>
  );
}
