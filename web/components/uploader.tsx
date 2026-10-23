"use client";

import { useRef, useState } from "react";
import { api, ApiError } from "@/lib/client";
import { ImageCropper } from "./image-cropper";
import { useT } from "./i18n-provider";
import { Button, ErrorText, Spinner } from "./ui";

/** Presign -> PUT bytes to the signed URL (through our proxy) -> returns the file id once the server verified it. */
export async function uploadFile(file: File, category: string, visibility?: string): Promise<string> {
  const p = await api<{ fileId: string; uploadUrl: string }>("files/presign", { body: { filename: file.name, contentType: file.type, size: file.size, category, visibility } });
  let res: Response;
  try {
    res = await fetch(p.uploadUrl.replace("/api/v1/", "/api/bff/"), { method: "PUT", headers: { "Content-Type": file.type }, body: file });
  } catch {
    throw new ApiError(0, "NETWORK", "No connection to the server");
  }
  if (!res.ok) {
    const d = await res.json().catch(() => ({}));
    throw new ApiError(res.status, d.code ?? `HTTP_${res.status}`, d.message ?? "Upload failed", d.fields, d.params);
  }
  return p.fileId;
}

/** Pick a picture, cut it to the wanted shape (drag and zoom), then upload. `aspect` is the starting shape (width / height). */
export function ImageUploader({ category, onUploaded, label, aspect = 1 }: { category: "PRODUCT_IMAGE" | "LOGO"; onUploaded: (fileId: string) => void; label?: string; aspect?: number }) {
  const t = useT();
  const ref = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [picked, setPicked] = useState<File | null>(null);
  const upload = async (f: File) => {
    setPicked(null); setBusy(true); setError(null);
    try { onUploaded(await uploadFile(f, category)); } catch (err) { setError(err); } finally { setBusy(false); }
  };
  return (
    <div>
      <input ref={ref} type="file" accept="image/*" className="hidden" onChange={(e) => {
        const f = e.target.files?.[0];
        if (ref.current) ref.current.value = "";
        if (!f) return;
        setError(null);
        if (!f.type.startsWith("image/")) { setError(new ApiError(400, "FILE_TYPE_NOT_ALLOWED", "This file type is not allowed", undefined, { types: "JPG, PNG" })); return; }
        setPicked(f);
      }} />
      <Button type="button" variant="secondary" size="sm" onClick={() => ref.current?.click()} disabled={busy}>{busy ? <Spinner /> : "📷"} {label ?? t("upload.image")}</Button>
      <ErrorText error={error} />
      {picked && <ImageCropper file={picked} aspect={aspect} onCancel={() => setPicked(null)} onInvalid={() => { setPicked(null); setError(new ApiError(400, "FILE_CORRUPT", "The file is not a picture")); }} onDone={(f) => void upload(f)} />}
    </div>
  );
}
