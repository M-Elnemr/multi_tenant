/**
 * Reply-to-a-message (WhatsApp style), the parts that need no browser: the swipe gesture as a small state machine, what a quoted message shows, who said it, and when an old
 * message has to be loaded before the page can jump to it. The screens (inbox, storefront chat) use these; they are tested on their own (lib/reply.test.ts).
 */

export type Sender = "customer" | "assistant" | "owner" | "shop";

/** What the backend sends for a message that answers another (the stored original, or the copy kept when the original is gone). */
export type Quoted = { id?: number | null; sender?: string; text?: string; hasMedia?: boolean; available?: boolean };

/** The message being replied to, as the composer and the bubbles show it. {@code id} is null when only a copy is left. */
export type ReplyTarget = { id: number | null; sender: Sender; text: string; hasMedia: boolean; available: boolean };

const SENDERS: Sender[] = ["customer", "assistant", "owner", "shop"];

export function asSender(s: unknown): Sender { return SENDERS.includes(s as Sender) ? (s as Sender) : "shop"; }

/** Who wrote a stored message: a customer's, the team's (typed in the inbox) or the assistant's. */
export function senderOf(m: { role: "user" | "assistant"; byOwner?: boolean }): Sender {
  return m.role === "user" ? "customer" : m.byOwner ? "owner" : "assistant";
}

export function targetOf(wire: Quoted | null | undefined): ReplyTarget | null {
  if (!wire) return null;
  const id = typeof wire.id === "number" ? wire.id : null;
  return { id, sender: asSender(wire.sender), text: wire.text ?? "", hasMedia: !!wire.hasMedia, available: wire.available !== false && (id !== null || !!wire.text || !!wire.hasMedia) };
}

/** The target to quote when the user replies to message {@code m} (null for a message with no id yet, e.g. one still being sent). */
export function targetFromMessage(m: { id?: number | null; role: "user" | "assistant"; byOwner?: boolean; content: string; mediaUrl?: string | null }): ReplyTarget | null {
  if (typeof m.id !== "number") return null;
  return { id: m.id, sender: senderOf(m), text: m.content ?? "", hasMedia: !!m.mediaUrl, available: true };
}

export type Labels = { photo: string; attachment: string; story: string };

/** One or two lines of the original: its text cut at a word boundary, or a description when it had no text (a photo, a file). Never empty. */
export function excerpt(t: ReplyTarget, labels: Labels, max = 110): string {
  const text = t.text.replace(/\s+/g, " ").trim();
  if (!text) return t.hasMedia ? labels.photo : labels.attachment;
  const body = text.length <= max ? text : text.slice(0, max).replace(/\s+\S*$/, "") + "…";
  return t.hasMedia ? `${labels.photo} · ${body}` : body;
}

/** The i18n key of the name shown above a quote, from the point of view of whoever is reading ({@code viewer}: the shop team in the inbox, the customer in the storefront chat). */
export function senderLabelKey(s: Sender, viewer: "owner" | "customer"): string {
  if (viewer === "customer") return s === "customer" ? "reply.you" : "reply.assistant";
  return s === "customer" ? "reply.customer" : s === "assistant" ? "reply.assistant" : s === "owner" ? "reply.team" : "reply.shop";
}

// ---- the swipe -----------------------------------------------------------------------------------------------------------------

export const SWIPE = {
  /** Movement before the gesture is decided to be a swipe or a scroll. */
  LOCK_PX: 10,
  /** Distance at which letting go replies. */
  ARM_PX: 56,
  /** The bubble never follows the finger further than this. */
  MAX_PX: 88,
  /** A move is a scroll when it is this much more vertical than horizontal. */
  VERTICAL_RATIO: 1.2,
} as const;

export type SwipeState = { phase: "tracking" | "horizontal" | "cancelled"; startX: number; startY: number; dx: number; armed: boolean };

/** +1 when the reply direction is rightwards (left-to-right text), -1 when leftwards (right-to-left): the bubble moves toward the end of the line, mirrored for Arabic. */
export function swipeSign(dir: "ltr" | "rtl"): 1 | -1 { return dir === "rtl" ? -1 : 1; }

export function swipeStart(x: number, y: number): SwipeState { return { phase: "tracking", startX: x, startY: y, dx: 0, armed: false }; }

/** Moves the gesture to the finger's new position. A mostly vertical move (a scroll) or one the wrong way cancels it for good; it never comes back. */
export function swipeMove(s: SwipeState, x: number, y: number, sign: 1 | -1): SwipeState {
  if (s.phase === "cancelled") return s;
  const rawDx = x - s.startX, dy = y - s.startY, dx = rawDx * sign;
  if (s.phase === "tracking") {
    if (Math.abs(dy) >= SWIPE.LOCK_PX && Math.abs(dy) > Math.abs(rawDx) * SWIPE.VERTICAL_RATIO) return { ...s, phase: "cancelled", dx: 0, armed: false };
    if (dx <= -SWIPE.LOCK_PX) return { ...s, phase: "cancelled", dx: 0, armed: false };   // the other way: not a reply swipe
    if (dx < SWIPE.LOCK_PX) return { ...s, dx: Math.max(0, dx) };
    s = { ...s, phase: "horizontal" };
  }
  const d = Math.max(0, dx);
  return { ...s, dx: d, armed: d >= SWIPE.ARM_PX };
}

/** How far the bubble is drawn from its place: follows the finger up to the arming distance, then resists. */
export function swipeOffset(s: SwipeState): number {
  if (s.phase !== "horizontal") return 0;
  if (s.dx <= SWIPE.ARM_PX) return s.dx;
  return Math.min(SWIPE.MAX_PX, SWIPE.ARM_PX + (s.dx - SWIPE.ARM_PX) * 0.35);
}

/** Between 0 and 1: how close the swipe is to arming (the reply icon grows with it). */
export function swipeProgress(s: SwipeState): number { return s.phase === "horizontal" ? Math.min(1, s.dx / SWIPE.ARM_PX) : 0; }

/** True when letting go now should start a reply. */
export function swipeEnds(s: SwipeState): boolean { return s.phase === "horizontal" && s.armed; }

/** Elements a swipe must never start on: they have their own interaction (links, buttons, fields, players, a horizontal carousel). */
export const NO_SWIPE_SELECTOR = "a,button,input,textarea,select,audio,video,[data-no-swipe],[contenteditable='true']";

// ---- jumping to the original ---------------------------------------------------------------------------------------------------

/** True when message {@code id} is older than everything loaded and more pages exist: it has to be loaded before the page can scroll to it. */
export function needsOlder(loadedIds: number[], id: number, hasOlder: boolean): boolean {
  if (!hasOlder || loadedIds.length === 0) return false;
  return !loadedIds.includes(id) && id < Math.min(...loadedIds);
}

/** Merges pages of messages by id, oldest first, without duplicates (a refresh of the newest page keeps the older pages already loaded). */
export function mergeMessages<T extends { id: number }>(...pages: T[][]): T[] {
  const byId = new Map<number, T>();
  for (const p of pages) for (const m of p) byId.set(m.id, m);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}

/**
 * The messages to show when the customer jumped to an old message and the page around it was fetched ({@code window}) while the newest page is {@code loaded}: the window's messages that are
 * older than everything loaded come first (never a duplicate of a loaded one), and {@code gapBefore} is the index of the first loaded message when the two may not touch (ids are shared by
 * every chat, so a difference between ids says nothing about messages in between: a separator is shown instead of a claim). Without a window, nothing changes.
 */
export function withOlderWindow<T extends { id?: number | null }>(window: T[], loaded: T[]): { items: T[]; gapBefore: number } {
  const ids = loaded.map((m) => m.id).filter((n): n is number => typeof n === "number");
  const have = new Set(ids);
  const minLoaded = ids.length ? Math.min(...ids) : Infinity;
  const before = window.filter((m) => typeof m.id === "number" && !have.has(m.id) && (m.id as number) < minLoaded);
  if (before.length === 0) return { items: loaded, gapBefore: -1 };
  const touches = window.some((m) => typeof m.id === "number" && have.has(m.id));   // the window overlaps the loaded page: nothing is missing in between
  return { items: [...before, ...loaded], gapBefore: touches ? -1 : before.length };
}
