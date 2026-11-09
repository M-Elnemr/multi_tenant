import type { IconName } from "../../icons";

export const SEG_KEYS = ["ALL", "NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED"] as const;
export type SegKey = (typeof SEG_KEYS)[number];
export const SEG_ICON: Record<SegKey, IconName> = { ALL: "users", NEW: "sparkles", REPEAT: "return", VIP: "award", AT_RISK: "alert", LAPSED: "clock" };

export const PLATFORMS = ["WHATSAPP", "FACEBOOK", "INSTAGRAM"] as const;
export type Platform = (typeof PLATFORMS)[number];
export const PLAT_COLOR: Record<string, string> = { WHATSAPP: "#25D366", FACEBOOK: "#0084FF", INSTAGRAM: "#E1306C" };

export type PlatCount = { total: number; reachable: number };
export type ByPlatform = Partial<Record<string, PlatCount>>;

/** GET store/segments */
export type SegmentsRes = {
  segments: { key: string; count: number }[];
  total: number;
  byPlatform: ByPlatform;
  ownNumberLinked: boolean;
  balanceMinor: number;
  messagePriceMinor: number;
};

export type ClientRow = { id: string; name?: string | null; phone?: string | null; platform: string; segment: string; orders: number; spentMinor: number; lastOrderAt?: string | null; reachable: boolean; reason?: string | null };
/** GET store/segments/customers */
export type ClientsRes = { data: ClientRow[]; total: number; reachable: number; outsideWindow: number; byPlatform: ByPlatform };

export type BroadcastStatus = "SENDING" | "DONE" | "PAUSED" | "CANCELED";
export type Broadcast = {
  id: string; createdAt: string; segments: string[]; platforms: string[]; message: string; status: BroadcastStatus; pauseReason?: string | null;
  audience: number; sentCount: number; failedCount: number; pendingCount: number; skippedOutsideWindow: number;
  nextSendAt?: string | null; etaMinutes: number; chargedMinor: number; mode?: string | null;
};

export type RecipientStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "STOPPED" | "SKIPPED";
export type Recipient = { id: string; name?: string | null; channel: string; phone?: string | null; status: RecipientStatus; error?: string | null; sentAt?: string | null; costMinor: number };
export type RecipientsRes = { data: Recipient[]; total: number; counts: Partial<Record<RecipientStatus, number>> };

export type SendResult = { id: string; audience: number; reachable: number; skipped: number; outsideWindow: number; etaMinutes: number; costMinor: number };

export const WA_OWN_LINK = "/dashboard/settings?tab=whatsapp#whatsapp-own";
export const csv = (a: readonly string[]) => a.join(",");
