"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { createContext, useCallback, useContext, useMemo, useRef, useState } from "react";
import { Icon } from "./icons";

/** Short confirmations and errors at the bottom of the dashboard ("Sent", "Could not save"), with an optional action (Undo / Open). */
type Tone = "success" | "error" | "info";
type Toast = { id: number; text: string; tone: Tone; action?: { label: string; run: () => void } };
type Push = (text: string, opts?: { tone?: Tone; action?: Toast["action"]; ms?: number }) => void;

const Ctx = createContext<Push>(() => {});

export function useToast() { return useContext(Ctx); }

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [list, setList] = useState<Toast[]>([]);
  const next = useRef(1);
  const reduce = useReducedMotion();
  const push = useCallback<Push>((text, opts) => {
    const id = next.current++;
    setList((l) => [...l.slice(-2), { id, text, tone: opts?.tone ?? "success", action: opts?.action }]);
    setTimeout(() => setList((l) => l.filter((t) => t.id !== id)), opts?.ms ?? 3800);
  }, []);
  const value = useMemo(() => push, [push]);
  return (
    <Ctx.Provider value={value}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 bottom-[calc(var(--tabbar-h)+1rem)] z-[90] flex flex-col items-center gap-2 px-4 lg:bottom-6" aria-live="polite">
        <AnimatePresence initial={false}>
          {list.map((t) => (
            <motion.div key={t.id} layout initial={reduce ? { opacity: 0 } : { opacity: 0, y: 16, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: 8, scale: 0.97 }} transition={{ duration: 0.22, ease: [0.2, 0.8, 0.2, 1] }}
              className="pointer-events-auto flex max-w-md items-center gap-3 rounded-[14px] bg-[#0d1117] py-2.5 pe-2.5 ps-3.5 text-[13.5px] font-medium text-white shadow-[0_20px_40px_-12px_rgb(0_0_0/.45)]">
              <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-full ${t.tone === "error" ? "bg-red-500/20 text-red-300" : t.tone === "info" ? "bg-sky-400/20 text-sky-300" : "bg-emerald-400/20 text-emerald-300"}`}>
                <Icon name={t.tone === "error" ? "alert" : t.tone === "info" ? "bell" : "check"} className="h-3.5 w-3.5" />
              </span>
              <span className="min-w-0 flex-1">{t.text}</span>
              {t.action && <button type="button" onClick={() => { t.action!.run(); setList((l) => l.filter((x) => x.id !== t.id)); }} className="rounded-[9px] px-2.5 py-1 text-[13px] font-semibold text-violet-300 transition hover:bg-white/10">{t.action.label}</button>}
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </Ctx.Provider>
  );
}
