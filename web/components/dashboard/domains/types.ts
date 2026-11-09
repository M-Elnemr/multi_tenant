/**
 * Shapes for the domain PURCHASE flow (search → buy → registration/DNS/SSL → live), per the shared contract at
 * scratchpad/domains-contract.md. The existing connect-a-domain flow's types stay in `../domains.tsx` untouched.
 */

/** The purchase/registration state machine (`commerce.domain_orders.status`). */
export const ORDER_STATUSES = ["PENDING_PAYMENT", "PAID", "REGISTERING", "DNS_CONFIGURING", "VERIFYING", "LIVE", "FAILED", "REFUNDED", "CANCELLED"] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

/** The forward path of a successful order, in order. SSL has no status of its own (Caddy issues it on-demand on first
 *  visit once the domain is `verified`); the stepper shows it as a sixth step anyway, tied to the domain's own `sslStatus`. */
export const FORWARD_STATUSES = ["PAID", "REGISTERING", "DNS_CONFIGURING", "VERIFYING", "LIVE"] as const;
export const TERMINAL_STATUSES = ["FAILED", "REFUNDED", "CANCELLED"] as const;

export type SearchResult = { domain: string; tld: string; available: boolean; premium: boolean; priceMinor: number; renewalPriceMinor: number; currency: string; quoteId: string; expiresAt: string };
export type SearchRes = { query: string; results: SearchResult[]; registrarEnabled: boolean };

export type RegistrantContact = { id: string; name: string; org?: string | null; email: string; phone: string; country: string; address: string; createdAt: string };
export type RegistrantContactInput = { name: string; org?: string; email: string; phone: string; country: string; address: string };

export type OrderEvent = { id: string; at: string; status: string; note?: string | null };

export type OrderListItem = {
  id: string; domain: string; status: OrderStatus; priceMinor: number; years: number; createdAt: string;
  host?: string | null; tenantDomainId?: string | null;
  /** Not in the current contract response — read defensively; the UI degrades gracefully when absent (see worker report). */
  currency?: string; expiresAt?: string | null; registrantContactId?: string | null;
  /** Opt-in auto-renew flag (added after the first contract draft, no confirmed field name yet — read defensively, default false). */
  autoRenew?: boolean;
};
export type OrderDetail = OrderListItem & { events: OrderEvent[] };

/** Whether a status is one of the known forward steps (not every status the backend can ever send is in this short
 *  list — an order can also report an unrecognized/"still confirming with the registrar" status; callers must not
 *  assume otherwise, see the "Additional rules" the user added to the plan). */
export const isForward = (s: string): boolean => (FORWARD_STATUSES as readonly string[]).includes(s);

export const DOMAIN_ORDER_ERROR_CODES = ["NO_CREDIT", "QUOTE_EXPIRED", "DOMAIN_UNAVAILABLE", "ALREADY_REGISTERED", "REGISTRAR_NOT_CONFIGURED", "ORDER_IN_PROGRESS", "INVALID_CONTACT", "NOT_RETRYABLE", "PROVIDER_DOWN", "RATE_LIMITED"] as const;

/** Where the existing `GET tenant/domains` top-up link for the shared points wallet already lives. */
export const WALLET_TOPUP_LINK = "/dashboard/ai?tab=whatsapp";

export const isTerminal = (s: OrderStatus): boolean => (TERMINAL_STATUSES as readonly string[]).includes(s);
