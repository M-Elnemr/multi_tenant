"use client";

import { whatsappLink } from "@/lib/whatsapp";
import { WhatsAppIcon } from "@/components/whatsapp-icon";

/**
 * Floating "chat with us on WhatsApp" button: the shop's most-used sales channel in Egypt. The WhatsApp logo and the word WhatsApp are always shown (on phones too), so a visitor knows
 * the tap opens a WhatsApp message and not this site's own chat. Sits above the phone navigation bar.
 */
export function WhatsAppFloat({ phone, storeName, label }: { phone?: string; storeName: string; label: string }) {
  if (!phone) return null;
  return (
    <a href={whatsappLink(phone, `${storeName} 👋`)} target="_blank" rel="noopener noreferrer" aria-label={label}
      className="float-b fixed end-4 z-30 flex items-center gap-2 rounded-full bg-[#25D366] py-2.5 pe-4 ps-3 text-white shadow-[0_12px_30px_-8px_rgb(37_211_102/0.7)] transition hover:-translate-y-1 hover:brightness-105 md:py-3 md:pe-5 md:ps-4">
      <WhatsAppIcon className="h-7 w-7 shrink-0" />
      <span className="text-sm font-bold leading-tight">{label}</span>
    </a>
  );
}
