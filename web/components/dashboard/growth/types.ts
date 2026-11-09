import type { IconName } from "../../icons";

export const SEG_KEYS = ["ALL", "NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED"] as const;
export type SegKey = (typeof SEG_KEYS)[number];
export const SEG_ICON: Record<SegKey, IconName> = { ALL: "users", NEW: "sparkles", REPEAT: "return", VIP: "award", AT_RISK: "alert", LAPSED: "clock" };

export const PLATFORMS = ["WHATSAPP", "FACEBOOK", "INSTAGRAM"] as const;
export type Platform = (typeof PLATFORMS)[number];
export const PLAT_COLOR: Record<string, string> = { WHATSAPP: "#25D366", FACEBOOK: "#0084FF", INSTAGRAM: "#E1306C" };

export type PlatCount = { total: number; reachable: number; reachableNow?: number; reachableOld?: number; needsConnect?: boolean };
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

export type ClientRow = { id: string; name?: string | null; phone?: string | null; platform: string; segment: string; orders: number; spentMinor: number; lastOrderAt?: string | null; reachable: boolean; reason?: string | null; route?: string };
/** GET store/segments/customers */
export type ClientsRes = { data: ClientRow[]; total: number; reachable: number; outsideWindow: number; byPlatform: ByPlatform };

export type BroadcastStatus = "SENDING" | "DONE" | "PAUSED" | "CANCELED";
export type Broadcast = {
  id: string; kind?: "CUSTOMERS" | "PROSPECTS"; createdAt: string; segments: string[]; platforms: string[]; message: string; status: BroadcastStatus; pauseReason?: string | null;
  audience: number; sentCount: number; failedCount: number; pendingCount: number; skippedOutsideWindow: number;
  nextSendAt?: string | null; etaMinutes: number; chargedMinor: number; mode?: string | null;
};

export type RecipientStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "STOPPED" | "SKIPPED";
export type Recipient = { id: string; name?: string | null; channel: string; phone?: string | null; status: RecipientStatus; error?: string | null; sentAt?: string | null; costMinor: number };
export type RecipientsRes = { data: Recipient[]; total: number; counts: Partial<Record<RecipientStatus, number>> };

export type SendResult = { id: string; audience: number; reachable: number; skipped: number; outsideWindow: number; etaMinutes: number; costMinor: number; manualStyle?: number; manualEtaDays?: number };

/** One platform of GET store/unofficial-meta (never any credential). */
export type Connection = {
  platform: string; supported: boolean; status: "ACTIVE" | "NEEDS_LOGIN" | "BLOCKED" | "DISABLED" | "NONE" | string;
  account?: string | null; sentToday: number; dailyCap: number; blockedUntil?: string | null; lastError?: string | null;
};
export type Connections = Partial<Record<"INSTAGRAM" | "FACEBOOK", Connection>>;

/** Source of a prospect row and the platforms that can hold prospects. */
export const PROSPECT_PLATFORMS = ["INSTAGRAM", "FACEBOOK"] as const;
export const PROSPECT_SOURCES = ["FOLLOWER", "COMMENTER"] as const;
export type Prospect = {
  id: string; platform: string; username?: string | null; displayName?: string | null; source: string; postRef?: string | null;
  lastComment?: string | null; isPrivate?: boolean; status: string; firstSeenAt?: string | null;
};
export type ProspectsRes = { total: number; counts: Record<string, Record<string, number>>; items: Prospect[] };
export type SyncStatus = { status: "IDLE" | "RUNNING" | "DONE" | "FAILED"; fetched: number; total: number; error?: string | null; startedAt?: string | null; finishedAt?: string | null; lastSyncedAt?: string | null };
export type Quote = { reachable: number; costMinor: number; etaMinutes: number; balanceMinor: number; enoughCredit: boolean };

export const CONNECT_LINK = "#connect-accounts";
export const WA_OWN_LINK = "/dashboard/settings?tab=whatsapp#whatsapp-own";
export const csv = (a: readonly string[]) => a.join(",");
