"use client";

import { useState } from "react";
import { useT } from "@/components/i18n-provider";
import { Button } from "@/components/ui";

/**
 * Print a prescription straight to the printer: the PDF is fetched with the visitor's session, loaded into a hidden frame and the browser's
 * print dialog opens for it (choose the printer there). If the browser refuses to print from the frame, the PDF opens in a new tab instead.
 */
export function PrintRxButton({ prescriptionId, size = "sm" }: { prescriptionId: string; size?: "sm" | "md" }) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const print = async () => {
    setBusy(true);
    try {
      const res = await fetch(`/api/bff/clinic/prescriptions/${prescriptionId}/pdf?inline=true`, { credentials: "same-origin" });
      if (!res.ok) throw new Error("pdf");
      const url = URL.createObjectURL(await res.blob());
      const frame = document.createElement("iframe");
      frame.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0;";
      frame.src = url;
      frame.onload = () => {
        try {
          frame.contentWindow?.focus();
          frame.contentWindow?.print();
        } catch {
          window.open(url, "_blank");
        }
        setTimeout(() => { frame.remove(); URL.revokeObjectURL(url); }, 120_000);
      };
      document.body.appendChild(frame);
    } finally {
      setBusy(false);
    }
  };
  return <Button type="button" size={size} variant="secondary" loading={busy} onClick={() => void print()}>🖨 {t("rx.print")}</Button>;
}

/** The doctor's paper prescription photo, as a small picture that opens full size. Private file: served only to authorised staff. */
export function RxPhoto({ fileId }: { fileId: string }) {
  const src = `/api/bff/files/${fileId}/content`;
  return (
    <a href={src} target="_blank" rel="noopener noreferrer" className="mt-2 block">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={src} alt="" className="max-h-48 rounded-lg border object-contain" />
    </a>
  );
}
