"use client";

import { useT } from "@/components/i18n-provider";
import { whatsappLink } from "@/lib/whatsapp";

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
      <svg viewBox="0 0 32 32" width={size * 0.6} height={size * 0.6} fill="currentColor" aria-hidden="true">
        <path d="M16.04 3C9.4 3 4 8.4 4 15.03c0 2.12.56 4.19 1.62 6.01L4 29l8.14-1.59a12 12 0 0 0 3.9.65h.01C22.68 28.06 28 22.66 28 16.03 28 9.4 22.68 3 16.04 3Zm0 22.04h-.01a9.97 9.97 0 0 1-3.4-.6l-.24-.1-4.83.94.99-4.7-.16-.25a9.9 9.9 0 0 1-1.52-5.3c0-5.5 4.5-9.97 10.04-9.97 5.54 0 10.03 4.47 10.03 9.97 0 5.5-4.5 9.99-10.04 9.99Zm5.5-7.47c-.3-.15-1.79-.88-2.07-.98-.28-.1-.48-.15-.68.15-.2.3-.78.98-.96 1.18-.18.2-.35.22-.65.07-.3-.15-1.28-.47-2.43-1.5-.9-.8-1.5-1.79-1.68-2.09-.18-.3-.02-.46.13-.61.14-.13.3-.35.45-.52.15-.18.2-.3.3-.5.1-.2.05-.38-.02-.53-.08-.15-.68-1.64-.93-2.25-.25-.59-.5-.51-.68-.52h-.58c-.2 0-.53.08-.8.38-.28.3-1.05 1.03-1.05 2.5 0 1.48 1.08 2.9 1.23 3.1.15.2 2.12 3.2 5.1 4.49.71.3 1.27.49 1.7.62.72.23 1.37.2 1.88.12.57-.08 1.79-.73 2.04-1.44.25-.7.25-1.3.18-1.44-.08-.12-.28-.2-.58-.35Z" />
      </svg>
    </a>
  );
}
