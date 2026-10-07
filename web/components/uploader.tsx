"use client";

import { useRef, useState } from "react";
import { api } from "@/lib/client";
import { useT } from "./i18n-provider";
import { Button, ErrorText, Spinner } from "./ui";

/** Presign -> PUT bytes to the signed URL (through our proxy) -> returns the file id once the server verified it. */
export async function uploadFile(file: File, category: string, visibility?: string): Promise<string> {
  const p = await api<{ fileId: string; uploadUrl: string }>("files/presign", { body: { filename: file.name, contentType: file.type, size: file.size, category, visibility } });
  const res = await fetch(p.uploadUrl.replace("/api/v1/", "/api/bff/"), { method: "PUT", headers: { "Content-Type": file.type }, body: file });
  if (!res.ok) {
    const d = await res.json().catch(() => ({}));
    throw Object.assign(new Error(d.message ?? "Upload failed"), { code: d.code, status: res.status });
  }
  return p.fileId;
}

export function ImageUploader({ category, onUploaded, label }: { category: "PRODUCT_IMAGE" | "LOGO"; onUploaded: (fileId: string) => void; label?: string }) {
  const t = useT();
  const ref = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  return (
    <div>
      <input ref={ref} type="file" accept="image/png,image/jpeg" className="hidden" onChange={async (e) => {
        const f = e.target.files?.[0];
        if (!f) return;
        setBusy(true); setError(null);
        try { onUploaded(await uploadFile(f, category)); } catch (err) { setError(err); } finally { setBusy(false); if (ref.current) ref.current.value = ""; }
      }} />
      <Button type="button" variant="secondary" size="sm" onClick={() => ref.current?.click()} disabled={busy}>{busy ? <Spinner /> : "📷"} {label ?? t("upload.image")}</Button>
      <ErrorText error={error} />
    </div>
  );
}
