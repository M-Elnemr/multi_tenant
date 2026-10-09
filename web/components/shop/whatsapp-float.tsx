"use client";

import { whatsappLink } from "@/lib/whatsapp";

/** Floating "chat with us" button: the shop's most-used sales channel in Egypt. Sits above the phone navigation bar. */
export function WhatsAppFloat({ phone, storeName, label }: { phone?: string; storeName: string; label: string }) {
  if (!phone) return null;
  return (
    <a href={whatsappLink(phone, `${storeName} 👋`)} target="_blank" rel="noopener noreferrer" aria-label={label}
      className="fixed bottom-20 end-4 z-30 flex items-center gap-2 rounded-full bg-emerald-500 p-3.5 text-white shadow-[0_12px_30px_-8px_rgb(16_185_129/0.7)] transition hover:-translate-y-1 hover:bg-emerald-400 md:bottom-6 md:px-5">
      <svg viewBox="0 0 32 32" className="h-6 w-6" fill="currentColor" aria-hidden><path d="M16.04 3C9.4 3 4 8.4 4 15.03c0 2.12.56 4.19 1.62 6.01L4 29l8.14-1.59a12 12 0 0 0 3.9.65C22.68 28.06 28 22.66 28 16.03 28 9.4 22.68 3 16.04 3Zm5.5 19.57c-.3-.15-1.79-.88-2.07-.98-.28-.1-.48-.15-.68.15-.2.3-.78.98-.96 1.18-.18.2-.35.22-.65.07-.3-.15-1.28-.47-2.43-1.5-.9-.8-1.5-1.79-1.68-2.09-.18-.3-.02-.46.13-.61.14-.13.3-.35.45-.52.15-.18.2-.3.3-.5.1-.2.05-.38-.02-.53-.08-.15-.68-1.64-.93-2.25-.25-.59-.5-.51-.68-.52h-.58c-.2 0-.53.08-.8.38-.28.3-1.05 1.03-1.05 2.5 0 1.48 1.08 2.9 1.23 3.1.15.2 2.12 3.2 5.1 4.49.71.3 1.27.49 1.7.62.72.23 1.37.2 1.88.12.57-.08 1.79-.73 2.04-1.44.25-.7.25-1.3.18-1.44-.08-.12-.28-.2-.58-.35Z" /></svg>
      <span className="hidden text-sm font-bold md:inline">{label}</span>
    </a>
  );
}
