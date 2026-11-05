/** Shown while the owner previews another design with ?tpl= (never to visitors who did not ask for it). */
export function PreviewBar({ label, exit }: { label: string; exit: string }) {
  return (
    <div className="s-preview-bar sticky top-0 z-[60] flex h-9 items-center justify-center gap-3 bg-[#101218] px-4 py-2 text-center text-[13px] font-medium text-white">
      <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 live-dot" />
      <span>{label}</span>
      <a href="?tpl=off" className="rounded-full bg-white/10 px-3 py-1 text-xs font-semibold ring-1 ring-inset ring-white/20 transition hover:bg-white/20">{exit}</a>
    </div>
  );
}
