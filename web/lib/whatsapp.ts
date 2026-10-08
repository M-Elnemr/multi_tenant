/** WhatsApp click-to-chat: wants the number without "+" or spaces (+201012345678 -> 201012345678). The sender presses send in WhatsApp itself. */
export const whatsappLink = (phone: string, message = "") => `https://wa.me/${phone.replace(/\D/g, "")}${message ? `?text=${encodeURIComponent(message)}` : ""}`;
