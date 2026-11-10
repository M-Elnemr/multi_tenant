"use client";

import { useT } from "@/components/i18n-provider";
import { whatsappLink } from "@/lib/whatsapp";
import { WhatsAppIcon } from "@/components/whatsapp-icon";

/** Small green WhatsApp icon that opens a chat with the number (new tab). Safe inside clickable rows: it never triggers the row. */
export function WhatsAppButton({ phone, message, size = 32 }: { phone?: string | null; message?: string; size?: number }) {
  const t = useT();
  if (!phone) return null;
  return (
    <a
      href={whatsappLink(phone, message)}
      target="_blank"
      rel="noopener noreferrer"
      onClick={(e) => e.stopPropagation()}
      aria-label={t("wa.icon")}
      title={t("wa.icon")}
      className="inline-flex shrink-0 items-center justify-center rounded-full bg-emerald-500 text-white transition hover:bg-emerald-600"
      style={{ width: size, height: size }}
    >
      <WhatsAppIcon className="h-[60%] w-[60%]" />
    </a>
  );
}
