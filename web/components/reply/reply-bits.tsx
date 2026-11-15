"use client";

import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { excerpt, senderLabelKey, type ReplyTarget } from "@/lib/reply";

type Viewer = "owner" | "customer";

function useLabels() {
  const { t } = useI18n();
  return { t, labels: { photo: t("reply.photo"), attachment: t("reply.attachment"), story: t("reply.story") } };
}

/** The quoted original inside a message bubble. A button that jumps to the original when it can; plain text when only a copy is left. */
export function ReplyQuote({ target, viewer, onJump, tone = "light" }: { target: ReplyTarget; viewer: Viewer; onJump?: (id: number) => void; tone?: "light" | "dark" | "brand" }) {
  const { t, labels } = useLabels();
  const canJump = !!onJump && target.id !== null && target.available;
  const color = tone === "dark" ? "bg-white/10 text-surface border-white/50" : tone === "brand" ? "bg-black/10 text-white border-white/70" : "bg-[color-mix(in_srgb,var(--ink,#0f172a)_6%,transparent)] text-ink border-violet-400";
  const body = (
    <>
      <span className="block truncate text-[11.5px] font-semibold opacity-90">{t(senderLabelKey(target.sender, viewer))}</span>
      <span className={`block line-clamp-2 break-words text-[12.5px] leading-snug ${target.available ? "opacity-80" : "italic opacity-60"}`} dir="auto">
        {target.available ? excerpt(target, labels) : target.text ? excerpt(target, labels) : t("reply.unavailable")}
      </span>
    </>
  );
  const cls = `mb-1.5 block w-full rounded-[10px] border-s-4 px-2.5 py-1.5 text-start ${color}`;
  return canJump
    ? <button type="button" onClick={(e) => { e.stopPropagation(); onJump!(target.id as number); }} aria-label={t("reply.jump")} className={`${cls} cursor-pointer transition hover:brightness-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-violet-500`}>{body}</button>
    : <div className={cls}>{body}</div>;
}

/** The strip above the composer while replying: who, what, and a cancel button (Escape in the text box cancels too, handled by the composer). */
export function ReplyPreview({ target, viewer, onCancel, onJump }: { target: ReplyTarget; viewer: Viewer; onCancel: () => void; onJump?: (id: number) => void }) {
  const { t, labels } = useLabels();
  const canJump = !!onJump && target.id !== null;
  return (
    <div role="group" aria-label={t("reply.replyingTo", { who: t(senderLabelKey(target.sender, viewer)) })} className="mx-3 mt-2 flex items-stretch gap-2 rounded-[12px] border-s-4 border-violet-400 bg-[color-mix(in_srgb,var(--ink,#0f172a)_5%,transparent)] py-1.5 pe-1.5 ps-2.5">
      <button type="button" disabled={!canJump} onClick={() => canJump && onJump!(target.id as number)} className="min-w-0 flex-1 text-start disabled:cursor-default">
        <span className="flex items-center gap-1 text-[11.5px] font-semibold text-violet-700"><Icon name="return" className="h-3 w-3" />{t("reply.replyingTo", { who: t(senderLabelKey(target.sender, viewer)) })}</span>
        <span className="block line-clamp-2 break-words text-[12.5px] leading-snug text-muted" dir="auto">{excerpt(target, labels)}</span>
      </button>
      <button type="button" onClick={onCancel} aria-label={t("reply.cancel")} title={t("reply.cancel")} className="grid h-7 w-7 shrink-0 place-items-center self-start rounded-full text-muted hover:bg-black/5 hover:text-ink"><Icon name="close" className="h-4 w-4" /></button>
    </div>
  );
}

/** The always-available reply control of a bubble (touch users also swipe): visible on hover and keyboard focus, so a desktop user needs no touch hardware. */
export function ReplyButton({ onClick, className = "" }: { onClick: () => void; className?: string }) {
  const { t } = useI18n();
  return (
    <button type="button" onClick={(e) => { e.stopPropagation(); onClick(); }} aria-label={t("reply.action")} title={t("reply.action")}
      className={`grid h-7 w-7 shrink-0 place-items-center rounded-full bg-surface/90 text-muted opacity-0 shadow-sm ring-1 ring-line transition hover:text-ink focus-visible:opacity-100 group-hover/msg:opacity-100 group-focus-within/msg:opacity-100 ${className}`}>
      <Icon name="return" className="h-3.5 w-3.5" />
    </button>
  );
}

/** The icon revealed behind a bubble while it is dragged; it grows and turns solid when letting go will reply. */
export function SwipeIcon({ progress, side }: { progress: number; side: "start" | "end" }) {
  if (progress <= 0) return null;
  return (
    <span aria-hidden className={`pointer-events-none absolute top-1/2 grid h-8 w-8 -translate-y-1/2 place-items-center rounded-full ${progress >= 1 ? "bg-violet-600 text-white" : "bg-black/10 text-muted"} ${side === "start" ? "start-1" : "end-1"}`}
      style={{ opacity: Math.min(1, progress * 1.4), transform: `translateY(-50%) scale(${0.6 + progress * 0.4})` }}>
      <Icon name="return" className="h-4 w-4" />
    </span>
  );
}
