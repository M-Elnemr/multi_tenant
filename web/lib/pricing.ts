/**
 * Platform prices shown on the website and in the apps (one place for the web side).
 * The backend charges them: V80__points_pricing.sql (plans), app.ai.conversation-price-minor (100) and app.whatsapp.*-price-minor (50).
 * Keep both sides in sync when a price changes.
 */
export const POINT_PRICES = {
  /** EGP (= points) per AI conversation: each block of up to 20 messages between the customer and the AI. */
  aiConversation: 1,
  /** EGP per WhatsApp message: order confirmations / updates and advertising messages to existing customers. */
  whatsappMessage: 0.5,
  /** Free points every new shop gets once. */
  welcomePoints: 100,
  /** Messages in one AI conversation. */
  conversationMessages: 20,
} as const;

/** Clinics and lawyers: the first month is free, then this much a month for website hosting. */
export const HOSTING = { monthlyEgp: 250, freeDays: 30 } as const;
