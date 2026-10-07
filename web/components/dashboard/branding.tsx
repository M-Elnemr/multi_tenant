"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useT } from "../i18n-provider";
import { ImageUploader } from "../uploader";
import { Alert, Button, Card, ErrorText, Field, Input, Select } from "../ui";

type B = { primaryColor: string; secondaryColor: string; logoFileId?: string; locale: string };

/** Colours + logo + default language. Branding only changes design tokens; it can never inject code. */
export default function BrandingCard() {
  const t = useT();
  const { data, reload } = useApi<B>("tenant/branding");
  const [edited, setF] = useState<B | null>(null);
  const f = edited ?? data;
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => { if (!f) return; await api("tenant/branding", { method: "PATCH", body: { primaryColor: f.primaryColor, secondaryColor: f.secondaryColor, logoFileId: f.logoFileId, locale: f.locale } }); setSaved(true); await reload(); });
  if (!f) return null;
  return (
    <Card className="space-y-4">
      <h2 className="font-medium">{t("settings.branding")}</h2>
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label={t("settings.primary")}><div className="flex gap-2"><input type="color" value={f.primaryColor} onChange={(e) => setF({ ...f, primaryColor: e.target.value })} className="h-10 w-12 rounded border" /><Input value={f.primaryColor} onChange={(e) => setF({ ...f, primaryColor: e.target.value })} dir="ltr" /></div></Field>
        <Field label={t("settings.secondary")}><div className="flex gap-2"><input type="color" value={f.secondaryColor} onChange={(e) => setF({ ...f, secondaryColor: e.target.value })} className="h-10 w-12 rounded border" /><Input value={f.secondaryColor} onChange={(e) => setF({ ...f, secondaryColor: e.target.value })} dir="ltr" /></div></Field>
        <Field label={t("settings.language")}><Select value={f.locale} onChange={(e) => setF({ ...f, locale: e.target.value })}><option value="ar">العربية</option><option value="en">English</option></Select></Field>
      </div>
      <div className="flex items-center gap-3">
        {f.logoFileId && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={`/api/bff/files/${f.logoFileId}/content?variant=thumb`} alt="" className="h-12 w-12 rounded object-cover" />
        )}
        <ImageUploader category="LOGO" label={t("settings.logo")} onUploaded={(id) => setF({ ...f, logoFileId: id })} />
      </div>
      <ErrorText error={save.error} />
      {saved && <Alert tone="green">{t("common.saved")}</Alert>}
      <Button loading={save.loading} onClick={() => { setSaved(false); void save.run(); }}>{t("common.save")}</Button>
    </Card>
  );
}
