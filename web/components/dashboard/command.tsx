"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Icon, hasIcon } from "../icons";
import { useT } from "../i18n-provider";
import { matches as textMatches, words } from "@/lib/pos/search";
import { ease } from "@/lib/motion";

export type CommandItem = { href: string; label: string; group?: string; icon?: string; hint?: string };

/** ⌘K / Ctrl-K palette over the menu: type part of a screen's name (Arabic-aware), arrows to move, Enter to open. */
export function CommandPalette({ open, onClose, items }: { open: boolean; onClose: () => void; items: CommandItem[] }) {
  const reduce = useReducedMotion();
  return (
    <AnimatePresence>
      {open && (
        <motion.div className="fixed inset-0 z-[70] flex items-start justify-center bg-[rgb(10_12_16/.36)] px-3 pt-[12vh] backdrop-blur-[2px]" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: reduce ? 0 : 0.15 }} onClick={onClose}>
          <Palette onClose={onClose} items={items} reduce={!!reduce} />
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/** Mounted fresh each time the palette opens, so the query and selection start empty. */
function Palette({ onClose, items, reduce }: { onClose: () => void; items: CommandItem[]; reduce: boolean }) {
  const t = useT();
  const router = useRouter();
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const ws = words(q);
  const shown = useMemo(() => items.filter((n) => ws.length === 0 || textMatches(ws, n.label, n.group ?? null, n.hint ?? null)).slice(0, 40), [items, ws]);

  useEffect(() => { listRef.current?.querySelector(`[data-i="${sel}"]`)?.scrollIntoView({ block: "nearest" }); }, [sel]);

  const go = (n?: CommandItem) => { if (!n) return; onClose(); router.push(n.href); };
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(shown.length - 1, s + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(0, s - 1)); }
    else if (e.key === "Enter") { e.preventDefault(); go(shown[sel]); }
    else if (e.key === "Escape") onClose();
  };

  return (
          <motion.div role="dialog" aria-modal="true" aria-label={t("nav.search")} onClick={(e) => e.stopPropagation()}
            initial={reduce ? false : { opacity: 0, y: -8, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: -6, scale: 0.98 }} transition={{ duration: 0.2, ease: ease.out }}
            className="w-full max-w-xl overflow-hidden rounded-[16px] bg-surface shadow-[var(--shadow-float)]">
            <div className="flex items-center gap-3 px-4 hairline-b">
              <Icon name="search" className="h-[18px] w-[18px] shrink-0 text-faint" />
              <input ref={input} autoFocus value={q} onChange={(e) => { setQ(e.target.value); setSel(0); }} onKeyDown={onKey} placeholder={t("cmd.placeholder")} aria-label={t("nav.search")}
                className="h-14 min-w-0 flex-1 bg-transparent text-[15px] text-ink outline-none placeholder:text-faint focus-visible:outline-none" />
              <span className="kbd">esc</span>
            </div>
            <ul ref={listRef} className="max-h-[56vh] overflow-y-auto p-2" role="listbox">
              {shown.length === 0 && <li className="px-3 py-10 text-center text-sm text-muted">{t("nav.noMatch")}</li>}
              {shown.map((n, i) => {
                const head = n.group && n.group !== shown[i - 1]?.group ? <li key={`g-${i}`} className="px-3 pb-1 pt-3 text-[11px] font-semibold text-faint first:pt-1">{n.group}</li> : null;
                const on = i === sel;
                return [head, (
                  <li key={n.href} data-i={i} role="option" aria-selected={on}>
                    <button onMouseMove={() => setSel(i)} onClick={() => go(n)} className={`flex w-full items-center gap-3 rounded-[10px] px-3 py-2.5 text-start text-sm transition-colors ${on ? "bg-surface-2 text-ink shadow-[0_0_0_1px_var(--line)]" : "text-ink-2"}`}>
                      <span className={`grid h-7 w-7 shrink-0 place-items-center rounded-[8px] ${on ? "bg-brand text-white" : "bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] text-muted"}`}>{hasIcon(n.icon) ? <Icon name={n.icon} className="h-4 w-4" /> : <Icon name="dot" className="h-4 w-4" />}</span>
                      <span className="min-w-0 flex-1 truncate font-medium">{n.label}</span>
                      {on && <Icon name="arrow" className="h-4 w-4 text-faint flip-rtl" />}
                    </button>
                  </li>
                )];
              })}
            </ul>
            <div className="flex items-center gap-4 bg-surface-2 px-4 py-2 text-[11px] text-faint" style={{ boxShadow: "inset 0 1px 0 var(--line)" }}>
              <span className="flex items-center gap-1"><span className="kbd">↑</span><span className="kbd">↓</span>{t("cmd.move")}</span>
              <span className="flex items-center gap-1"><span className="kbd">↵</span>{t("cmd.open")}</span>
            </div>
          </motion.div>
  );
}
