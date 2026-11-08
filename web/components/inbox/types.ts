/** Shapes of the inbox API (backend: ChatInboxService / FollowUpService / CommentService). */

export type Status = "NEEDS_REPLY" | "WAITING" | "UNHAPPY" | "TAKEOVER" | "OPEN" | "CLOSED";
export type Channel = "WEB" | "WHATSAPP" | "FACEBOOK" | "INSTAGRAM";

export type ChatRow = {
  id: string; channel: Channel; customerName?: string | null; customerPhone?: string | null; customerId?: string | null; ordersCount: number;
  lastMessage?: string | null; lastFromCustomer: boolean; lastByOwner: boolean; lastKind?: string | null; updatedAt: string; lastCustomerAt?: string | null; lastReplyAt?: string | null; createdAt: string;
  status: "OPEN" | "CLOSED"; needsHuman: boolean; needsHumanReason?: string | null; takeover: boolean; takeoverAt?: string | null; aiPaused: boolean; aiPausedUntil?: string | null;
  aiActive: boolean; aiChannelOn: boolean; unhappy: boolean; unhappyReason?: string | null; unhappySource?: string | null; unread: boolean; unreadCount: number;
  statuses: Status[]; priority: "HIGH" | "MEDIUM" | "LOW"; lastOrder?: string | null;
};

export type Msg = { id: number; role: "user" | "assistant"; content: string; byOwner: boolean; kind: "TEXT" | "FOLLOWUP"; mediaUrl?: string | null; createdAt: string };
export type ChatEvent = { id: number; kind: string; meta?: string | null; createdAt: string; actor?: string | null };
export type Note = { id: number; body: string; createdAt: string; author?: string | null; authorUserId?: string | null };
export type Window = { canSend: boolean; mode: "WEB" | "OWN_NUMBER" | "CLOUD" | "MESSENGER" | "INSTAGRAM" | "OTHER"; endsAt?: string; reason?: "CLOSED" | "NOT_CONNECTED" | "NOT_SUPPORTED"; openUrl?: string; openKind?: string };
export type FollowUp = { id: string; stepPosition: number; dueAt: string; status: string; reason?: string | null; body: string; sentAt?: string | null };
export type CustomerCard = {
  id: string; customerNumber?: string; name?: string | null; phone?: string | null; email?: string | null; gender?: string | null; notes?: string | null; createdAt: string;
  whatsappNumber?: string | null; broadcastOptOutAt?: string | null; address?: { addressLine1?: string; city?: string; district?: string; governorateCode?: string } | null;
  ordersCount: number; totalSpentMinor: number; lastOrderAt?: string | null; tags: string[];
  orders: { id: string; orderNumber: string; status: string; totalMinor: number; currency: string; createdAt: string }[];
};

export type ChatDetail = ChatRow & {
  messages: Msg[]; hasOlder: boolean; events: ChatEvent[]; notes: Note[]; window: Window; followUps: FollowUp[]; customer: CustomerCard | null; channelName?: string | null;
  delivery?: "SENT" | "SAVED" | "FAILED"; deliveryNote?: string | null;
};

export type Page<T> = { data: T[]; total: number; nextCursor: number | null };

export type Filters = { status: Status[]; ai: ("ACTIVE" | "PAUSED")[]; channel: Channel[]; since: "" | "30m" | "1h" | "today" | "7d"; customer: ("NEW" | "RETURNING" | "WITH_ORDERS" | "NO_ORDERS")[]; q: string; sort: "newest" | "oldest" | "unread" | "priority" };

export const EMPTY: Filters = { status: [], ai: [], channel: [], since: "", customer: [], q: "", sort: "newest" };

export type Counts = { needsHuman: number; needsReply: number; unread: number; unhappy: number; waiting: number; unrepliedComments: number };

export type SavedReply = { id: string; title: string; body: string };
