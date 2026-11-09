/**
 * Platform prices shown on the website and in the apps (one place for the web side).
 * The backend charges them (points pricing migrations, app.ai.conversation-price-minor, message and comment price settings).
 * Keep both sides in sync when a price changes.
 */
export const POINT_PRICES = {
  /** EGP (= points) per AI conversation block: every block of 10 AI replies (1-10 = 1 EGP, 11-20 = 2 EGP ...). */
  aiConversation: 1,
  /** AI replies in one billed block. */
  conversationReplies: 10,
  /** EGP per outbound message (WhatsApp, Messenger, Instagram): confirmations, offers, ads, cart reminders. */
  message: 0.1,
  /** EGP per AI comment reply and per AI private message to a commenter. Stamp replies are free. */
  comment: 0.1,
  /** Free points every new shop gets once. */
  welcomePoints: 100,
} as const;

/** Clinics and lawyers: the first month is free, then this much a month for website hosting. */
export const HOSTING = { monthlyEgp: 250, freeDays: 30 } as const;
