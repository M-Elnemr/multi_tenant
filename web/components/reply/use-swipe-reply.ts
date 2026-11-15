"use client";

import { useCallback, useRef, useState } from "react";
import { NO_SWIPE_SELECTOR, swipeEnds, swipeMove, swipeOffset, swipeProgress, swipeSign, swipeStart, type SwipeState } from "@/lib/reply";

/**
 * WhatsApp-style swipe to reply for one message bubble. Touch and pen only: with a mouse a drag would fight text selection, so desktop uses the reply button (and «r» on a focused message).
 * The bubble must have {@code touch-action: pan-y} (the returned style sets it): the browser keeps vertical scrolling, and a scroll that starts on the bubble cancels the swipe.
 * It never starts on links, buttons, fields, players or elements marked {@code data-no-swipe} (a horizontal carousel), nor while the user is selecting text.
 */
export function useSwipeReply({ onReply, dir, disabled = false }: { onReply: () => void; dir: "ltr" | "rtl"; disabled?: boolean }) {
  const state = useRef<SwipeState | null>(null);
  const armed = useRef(false);
  const [drag, setDrag] = useState<{ offset: number; progress: number; active: boolean }>({ offset: 0, progress: 0, active: false });
  const sign = swipeSign(dir);

  const reset = useCallback(() => { state.current = null; armed.current = false; setDrag({ offset: 0, progress: 0, active: false }); }, []);

  const onPointerDown = (e: React.PointerEvent<HTMLElement>) => {
    if (disabled || e.pointerType === "mouse" || !e.isPrimary) return;
    if ((e.target as HTMLElement).closest(NO_SWIPE_SELECTOR)) return;
    if (typeof window !== "undefined" && (window.getSelection()?.toString() ?? "") !== "") return;   // selecting text, not swiping
    state.current = swipeStart(e.clientX, e.clientY);
    armed.current = false;
  };
  const onPointerMove = (e: React.PointerEvent<HTMLElement>) => {
    const s = state.current;
    if (!s) return;
    const next = swipeMove(s, e.clientX, e.clientY, sign);
    state.current = next;
    if (next.phase === "cancelled") { reset(); return; }
    if (next.phase === "horizontal") {
      if (s.phase !== "horizontal") { try { e.currentTarget.setPointerCapture(e.pointerId); } catch { /* the pointer is gone */ } }
      if (next.armed && !armed.current) { armed.current = true; try { navigator.vibrate?.(8); } catch { /* no haptics here */ } }
      if (!next.armed) armed.current = false;
      setDrag({ offset: swipeOffset(next), progress: swipeProgress(next), active: true });
    }
  };
  const onPointerUp = () => {
    const s = state.current;
    const replies = !!s && swipeEnds(s);
    reset();
    if (replies) onReply();
  };

  return {
    handlers: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel: reset, onLostPointerCapture: () => { if (state.current?.phase === "horizontal") onPointerUp(); } },
    style: { touchAction: "pan-y", transform: drag.offset ? `translateX(${drag.offset * sign}px)` : undefined, transition: drag.active ? "none" : "transform 160ms ease-out" } as React.CSSProperties,
    progress: drag.progress,
    swiping: drag.active,
  };
}
