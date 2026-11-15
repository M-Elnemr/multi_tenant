import assert from "node:assert/strict";
import { test } from "node:test";
import {
  SWIPE, excerpt, mergeMessages, withOlderWindow, needsOlder, senderLabelKey, senderOf, swipeEnds, swipeMove, swipeOffset, swipeProgress, swipeSign, swipeStart, targetFromMessage, targetOf,
  type ReplyTarget,
} from "./reply.ts";

const labels = { photo: "Photo", attachment: "Attachment", story: "Story" };
const T = (over: Partial<ReplyTarget> = {}): ReplyTarget => ({ id: 5, sender: "assistant", text: "hello", hasMedia: false, available: true, ...over });

// ---- the swipe ----------------------------------------------------------------------------------------------------------------

test("a clear swipe in the reply direction arms past the threshold and replies on release (left-to-right)", () => {
  let s = swipeStart(100, 200);
  s = swipeMove(s, 120, 202, 1);
  assert.equal(s.phase, "horizontal");
  assert.equal(swipeEnds(s), false, "not armed yet");
  s = swipeMove(s, 100 + SWIPE.ARM_PX + 4, 204, 1);
  assert.equal(s.armed, true);
  assert.equal(swipeEnds(s), true);
});

test("right-to-left layouts reply with a leftwards swipe and ignore a rightwards one", () => {
  const sign = swipeSign("rtl");
  assert.equal(sign, -1);
  let s = swipeStart(300, 200);
  s = swipeMove(s, 300 - SWIPE.ARM_PX - 2, 201, sign);
  assert.equal(swipeEnds(s), true);
  let wrong = swipeStart(300, 200);
  wrong = swipeMove(wrong, 300 + 80, 201, sign);
  assert.equal(wrong.phase, "cancelled");
  assert.equal(swipeEnds(wrong), false);
});

test("a vertical scroll is never a swipe, even if it drifts sideways later", () => {
  let s = swipeStart(100, 100);
  s = swipeMove(s, 104, 140, 1);   // mostly vertical
  assert.equal(s.phase, "cancelled");
  s = swipeMove(s, 260, 142, 1);   // a long sideways drift afterwards changes nothing
  assert.equal(s.phase, "cancelled");
  assert.equal(swipeEnds(s), false);
  assert.equal(swipeOffset(s), 0);
});

test("a swipe in the wrong direction cancels, and a short swipe does not reply", () => {
  let back = swipeStart(100, 100);
  back = swipeMove(back, 100 - 30, 101, 1);
  assert.equal(back.phase, "cancelled");
  let short = swipeStart(100, 100);
  short = swipeMove(short, 100 + 30, 101, 1);
  assert.equal(short.phase, "horizontal");
  assert.equal(swipeEnds(short), false);
});

test("tiny movements stay undecided so a tap or a text selection is untouched", () => {
  let s = swipeStart(100, 100);
  s = swipeMove(s, 104, 103, 1);
  assert.equal(s.phase, "tracking");
  assert.equal(swipeOffset(s), 0);
  assert.equal(swipeEnds(s), false);
});

test("the bubble follows the finger, resists past the threshold and never passes the maximum", () => {
  let s = swipeStart(0, 0);
  s = swipeMove(s, 30, 0, 1);
  assert.equal(swipeOffset(s), 30);
  s = swipeMove(s, 400, 0, 1);
  assert.ok(swipeOffset(s) > SWIPE.ARM_PX && swipeOffset(s) <= SWIPE.MAX_PX);
  assert.equal(swipeProgress(s), 1);
  let neg = swipeStart(0, 0); neg = swipeMove(neg, 30, 0, 1); neg = swipeMove(neg, -5, 0, 1);   // pulled back past the start
  assert.equal(swipeOffset(neg), 0);
});

// ---- what a quote shows -----------------------------------------------------------------------------------------------------------

test("an excerpt is cut at a word, collapses whitespace and describes a message with no text", () => {
  assert.equal(excerpt(T({ text: "  one \n two   three " }), labels), "one two three");
  const long = "word ".repeat(60);
  const e = excerpt(T({ text: long }), labels, 30);
  assert.ok(e.endsWith("…") && e.length <= 31, e);
  assert.ok(!e.slice(0, -1).endsWith("wor"), "no word cut in half");
  assert.equal(excerpt(T({ text: "", hasMedia: true }), labels), "Photo");
  assert.equal(excerpt(T({ text: "", hasMedia: false }), labels), "Attachment");
  assert.equal(excerpt(T({ text: "caption", hasMedia: true }), labels), "Photo · caption");
  assert.equal(excerpt(T({ text: "مرحبا بيك في المتجر" }), labels), "مرحبا بيك في المتجر");
  assert.ok(excerpt(T({ text: "x".repeat(500) }), labels).length <= 111, "an unbroken long string is still bounded");
});

test("who said it is named from the reader's point of view", () => {
  assert.equal(senderOf({ role: "user" }), "customer");
  assert.equal(senderOf({ role: "assistant", byOwner: true }), "owner");
  assert.equal(senderOf({ role: "assistant" }), "assistant");
  assert.equal(senderLabelKey("customer", "owner"), "reply.customer");
  assert.equal(senderLabelKey("owner", "owner"), "reply.team");
  assert.equal(senderLabelKey("customer", "customer"), "reply.you");
  assert.equal(senderLabelKey("owner", "customer"), "reply.assistant");
});

test("a wire quote becomes a target; an unknown sender is the shop; a copy with no id is still shown but cannot be jumped to", () => {
  const live = targetOf({ id: 9, sender: "customer", text: "hi", hasMedia: false, available: true });
  assert.deepEqual(live, { id: 9, sender: "customer", text: "hi", hasMedia: false, available: true });
  assert.equal(targetOf({ sender: "weird", text: "x" })?.sender, "shop");
  const copy = targetOf({ id: null, sender: "assistant", text: "was here", available: false });
  assert.equal(copy?.id, null);
  assert.equal(copy?.available, false);
  assert.equal(targetOf(null), null);
  assert.equal(targetOf({ id: null, sender: "shop" })?.available, false, "nothing to show: marked unavailable");
});

test("a message still being sent (no id yet) cannot be replied to; a stored one can, with its sender and media", () => {
  assert.equal(targetFromMessage({ role: "user", content: "x" }), null);
  assert.deepEqual(targetFromMessage({ id: 3, role: "assistant", byOwner: true, content: "c", mediaUrl: "/m.jpg" }), { id: 3, sender: "owner", text: "c", hasMedia: true, available: true });
});

// ---- jumping and paging -------------------------------------------------------------------------------------------------------------

test("only a message older than everything loaded, with more pages, needs loading before a jump", () => {
  assert.equal(needsOlder([50, 51, 52], 10, true), true);
  assert.equal(needsOlder([50, 51, 52], 51, true), false, "already loaded");
  assert.equal(needsOlder([50, 51, 52], 10, false), false, "no older page exists");
  assert.equal(needsOlder([50, 51, 52], 99, true), false, "newer than what is loaded is not an older page");
  assert.equal(needsOlder([], 10, true), false);
});

test("pages merge oldest first without duplicates, so a refresh keeps the older pages already loaded", () => {
  const merged = mergeMessages([{ id: 1, v: "old" }, { id: 2, v: "old" }], [{ id: 2, v: "new" }, { id: 3, v: "new" }]);
  assert.deepEqual(merged.map((m) => m.id), [1, 2, 3]);
  assert.equal(merged[1].v, "new");
});

test("a jumped-to window goes before the loaded page, never duplicates a loaded message, and says when it may not touch the page", () => {
  const m = (id: number) => ({ id, content: "m" + id });
  // far away: a separator before the loaded page
  const far = withOlderWindow([m(10), m(11), m(12)], [m(100), m(101)]);
  assert.deepEqual(far.items.map((x) => x.id), [10, 11, 12, 100, 101]);
  assert.equal(far.gapBefore, 3);
  // the window reaches the loaded page: no separator, no duplicate
  const near = withOlderWindow([m(98), m(99), m(100)], [m(100), m(101)]);
  assert.deepEqual(near.items.map((x) => x.id), [98, 99, 100, 101]);
  assert.equal(near.gapBefore, -1);
  // nothing older (everything is already loaded) or no window: unchanged
  assert.equal(withOlderWindow([m(100)], [m(100), m(101)]).items.length, 2);
  assert.equal(withOlderWindow([], [m(1)]).gapBefore, -1);
  // optimistic messages without an id are kept as they are
  const mixed = withOlderWindow([m(5)], [{ content: "sending…" }, m(100)] as { id?: number; content: string }[]);
  assert.equal(mixed.items.length, 3);
  assert.equal(mixed.items[1].content, "sending…");
});
