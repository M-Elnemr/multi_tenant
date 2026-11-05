"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useState } from "react";
import { Icon } from "@/components/icons";
import { Counter, useSeen } from "../kit";
import type { ShopCopy } from "./copy";

const E = [0.2, 0.8, 0.2, 1] as const;
const WA = "#25d366";

/**
 * "Win back old customers": the owner picks a quiet group and sends an offer (left), the old customer receives it on WhatsApp,
 * answers, and the AI seller closes the order (right). Plays once when it scrolls into view; static for reduced motion.
 */
export function WinBack({ w, locale }: { w: ShopCopy["winback"]; locale: string }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  // 0 idle · 1 sending · 2 delivered · 3 customer replied · 4 AI answered · 5 results
  const [played, setPlayed] = useState(0);
  useEffect(() => {
    if (!seen || reduce) return;
    const times = [700, 1700, 3100, 4500, 5600];
    const ids = times.map((ms, i) => setTimeout(() => setPlayed(i + 1), ms));
    return () => ids.forEach(clearTimeout);
  }, [seen, reduce]);
  const step = reduce ? 5 : played;   // reduced motion: everything shown at once
  const num = (n: number) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const msg = (text: string) => text.split(/(\{[^}]+\})/).map((part, i) => (part.startsWith("{") ? <span key={i} className="rounded-md bg-[#25d366]/15 px-1 font-semibold text-[#5ef09a]">{part}</span> : part));

  return (
    <div ref={ref} className="relative grid items-center gap-10 lg:grid-cols-[1fr_auto_0.9fr] lg:gap-6">
      {/* ---- the composer ---- */}
      <div className="relative rounded-[1.75rem] border border-white/10 bg-white/[.04] p-5 shadow-[0_40px_80px_-40px_rgb(0_0_0/.8)] backdrop-blur-xl sm:p-6">
        <div className="flex items-center justify-between">
          <p className="flex items-center gap-2 font-display text-lg font-bold text-white"><span className="grid h-9 w-9 place-items-center rounded-xl bg-[#25d366]/15 text-[#5ef09a]"><Icon name="send" className="h-4.5 w-4.5" /></span>{w.composer}</p>
          <span className="rounded-full bg-white/5 px-2.5 py-1 text-[11px] font-semibold text-white/50">{w.sample}</span>
        </div>
        <p className="mt-5 text-xs font-semibold uppercase tracking-wider text-white/40">{w.audience}</p>
        <div className="mt-2 space-y-2">
          {w.segments.map((s, i) => {
            const on = i === 0;
            return (
              <div key={s.key} className={`flex items-center gap-3 rounded-2xl border px-3.5 py-2.5 text-sm transition ${on ? "border-[#25d366]/50 bg-[#25d366]/10 text-white" : "border-white/8 bg-white/[.02] text-white/55"}`}>
                <span className={`grid h-5 w-5 shrink-0 place-items-center rounded-full border ${on ? "border-[#25d366] bg-[#25d366] text-[#06210f]" : "border-white/25"}`}>{on && <Icon name="check" className="h-3 w-3" />}</span>
                <span className="flex-1 font-medium">{s.label}</span>
                <span className="font-display font-bold tnum">{num(s.n)}</span>
              </div>
            );
          })}
        </div>
        <div className="mt-4 rounded-2xl border border-white/8 bg-[#0b0f14] p-3.5">
          <div className="flex gap-3">
            <div className="relative h-16 w-14 shrink-0 overflow-hidden rounded-xl bg-[linear-gradient(160deg,#2f8f5b,#a7f3d0)]" aria-label={w.photo}>
              <span className="absolute inset-x-2 bottom-1.5 top-3 rounded-t-[40%] bg-[#14532d]/70" />
              <span className="absolute end-1 top-1 rounded-md bg-[#ff4d6d] px-1 text-[9px] font-bold text-white">-15%</span>
            </div>
            <p className="text-[13px] leading-relaxed text-white/80">{msg(w.message)}</p>
          </div>
          <p className="mt-3 flex items-center gap-1.5 text-xs font-semibold text-[#c4b5fd]"><Icon name="sparkles" className="h-3.5 w-3.5" />{w.ai}</p>
        </div>
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
          <span className="text-xs font-medium text-white/50 tnum">{w.cost}</span>
          <motion.span
            className="relative inline-flex items-center gap-2 overflow-hidden rounded-full px-5 py-2.5 text-sm font-bold text-[#06210f]"
            style={{ background: WA }}
            animate={step === 1 && !reduce ? { scale: [1, 0.94, 1.04, 1] } : undefined}
            transition={{ duration: 0.6, ease: E }}
          >
            {step >= 2 ? <Icon name="check" className="h-4 w-4" /> : <Icon name="send" className="h-4 w-4 flip-rtl" />}{w.send}
            {step === 1 && !reduce && <motion.span className="absolute inset-0 bg-white/40" initial={{ x: "-100%" }} animate={{ x: "100%" }} transition={{ duration: 0.7 }} />}
          </motion.span>
        </div>
      </div>

      {/* ---- the path between them ---- */}
      <div className="relative mx-auto flex h-16 w-full items-center justify-center lg:h-auto lg:w-24 lg:self-stretch" aria-hidden>
        <span className="absolute h-full w-px bg-[linear-gradient(transparent,rgb(37_211_102/.5),transparent)] lg:h-px lg:w-full lg:bg-[linear-gradient(90deg,transparent,rgb(37_211_102/.5),transparent)]" />
        <AnimatePresence>
          {step >= 1 && step < 3 && !reduce && (
            <motion.span key="plane" className="absolute grid h-9 w-9 place-items-center rounded-full text-[#06210f] shadow-[0_0_30px_rgb(37_211_102/.6)]" style={{ background: WA }}
              initial={{ opacity: 0, scale: 0.4 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.4 }} transition={{ duration: 0.4 }}>
              <Icon name="send" className="h-4 w-4 flip-rtl" />
            </motion.span>
          )}
        </AnimatePresence>
      </div>

      {/* ---- the old customer's phone ---- */}
      <div className="relative mx-auto w-full max-w-[330px]">
        <div className="lp-phone aspect-[9/14.5] w-full">
          <div className="flex h-full flex-col bg-[#0b141a]">
            <div className="flex items-center gap-2.5 bg-[#1f2c34] px-4 pb-3 pt-9">
              <span className="grid h-8 w-8 place-items-center rounded-full bg-[linear-gradient(135deg,#7c5cff,#22d3ee)] text-xs font-bold text-white">{w.phoneName.slice(0, 1)}</span>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold text-white">{w.phoneName}</p>
                <p className="text-[10px] text-[#8696a0]">WhatsApp</p>
              </div>
            </div>
            <div className="flex flex-1 flex-col justify-start gap-2 overflow-hidden bg-[radial-gradient(circle_at_20%_10%,rgb(37_211_102/.06),transparent_50%)] p-3 pt-4">
              <AnimatePresence>
                {step >= 2 && (
                  <motion.div key="offer" className="max-w-[88%] self-start overflow-hidden rounded-2xl rounded-ss-sm bg-[#1f2c34] text-[12.5px] leading-relaxed text-[#e9edef]"
                    initial={reduce ? false : { opacity: 0, y: 14, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ duration: 0.45, ease: E }}>
                    <div className="relative h-20 bg-[linear-gradient(160deg,#2f8f5b,#a7f3d0)]">
                      <span className="absolute inset-x-10 bottom-0 top-4 rounded-t-[45%] bg-[#14532d]/70" />
                      <span className="absolute end-2 top-2 rounded-md bg-[#ff4d6d] px-1.5 text-[10px] font-bold text-white">-15%</span>
                    </div>
                    <p className="px-3 py-2">{w.received}</p>
                  </motion.div>
                )}
                {step >= 3 && (
                  <motion.p key="cust" className="max-w-[80%] self-end rounded-2xl rounded-se-sm bg-[#005c4b] px-3 py-2 text-[12.5px] leading-relaxed text-[#e9edef]"
                    initial={reduce ? false : { opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4, ease: E }}>{w.replyCustomer}</motion.p>
                )}
                {step >= 4 && (
                  <motion.div key="ai" className="max-w-[88%] self-start rounded-2xl rounded-ss-sm bg-[#1f2c34] px-3 py-2 text-[12.5px] leading-relaxed text-[#e9edef]"
                    initial={reduce ? false : { opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4, ease: E }}>
                    <span className="mb-1 flex items-center gap-1 text-[10px] font-semibold text-[#c4b5fd]"><Icon name="sparkles" className="h-3 w-3" />AI</span>
                    {w.replyAi}
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </div>
        </div>
        {/* results */}
        <motion.div className="mt-5 w-full rounded-2xl border border-white/10 bg-[#11161d]/95 p-3 shadow-[0_30px_60px_-20px_rgb(0_0_0/.9)] backdrop-blur-xl"
          initial={reduce ? false : { opacity: 0, y: 20 }} animate={step >= 5 ? { opacity: 1, y: 0 } : undefined} transition={{ duration: 0.5, ease: E }}>
          <div className="grid grid-cols-3 divide-x divide-white/10 text-center rtl:divide-x-reverse">
            {w.results.map((r) => (
              <div key={r.label} className="px-1.5">
                <p className="font-display text-base font-bold text-white tnum sm:text-lg">
                  {step >= 5 ? <Counter value={r.value} locale={locale} prefix={"money" in r && r.money && locale !== "ar" ? "EGP " : ""} suffix={"money" in r && r.money && locale === "ar" ? " ج.م" : r.suffix} /> : "—"}
                </p>
                <p className="mt-0.5 text-[10px] leading-tight text-white/50">{r.label}</p>
              </div>
            ))}
          </div>
        </motion.div>
      </div>
    </div>
  );
}

/** The five steps of an old customer coming back, drawn as a line that fills as it scrolls into view. */
export function WinBackJourney({ w, locale }: { w: ShopCopy["winback"]; locale: string }) {
  const [ref, seen] = useSeen<HTMLOListElement>();
  const reduce = useReducedMotion();
  return (
    <ol ref={ref} className="relative grid gap-6 md:grid-cols-5 md:gap-3">
      <span className="absolute inset-y-6 start-[1.35rem] w-px bg-white/10 md:inset-x-[10%] md:inset-y-auto md:top-[1.35rem] md:h-px md:w-auto" aria-hidden />
      <motion.span className="absolute start-[1.35rem] top-6 w-px origin-top bg-[linear-gradient(#25d366,#7c5cff)] md:hidden" style={{ bottom: "1.5rem" }} aria-hidden
        initial={reduce ? false : { scaleY: 0 }} animate={seen ? { scaleY: 1 } : undefined} transition={{ duration: 1.8, ease: E }} />
      <motion.span className="absolute inset-x-[10%] top-[1.35rem] hidden h-px origin-left bg-[linear-gradient(90deg,#7c5cff,#25d366)] md:block rtl:origin-right" aria-hidden
        initial={reduce ? false : { scaleX: 0 }} animate={seen ? { scaleX: 1 } : undefined} transition={{ duration: 1.8, ease: E }} />
      {w.journey.map((j, i) => (
        <motion.li key={j.title} className="relative flex gap-4 md:flex-col md:items-center md:text-center"
          initial={reduce ? false : { opacity: 0, y: 16 }} animate={seen ? { opacity: 1, y: 0 } : undefined} transition={{ duration: 0.5, ease: E, delay: 0.25 + i * 0.3 }}>
          <span className={`relative z-[1] grid h-11 w-11 shrink-0 place-items-center rounded-2xl border ${i === 4 ? "border-[#25d366]/60 bg-[#25d366] text-[#06210f] shadow-[0_0_30px_rgb(37_211_102/.45)]" : "border-white/15 bg-[#11161d] text-white"}`}>
            <Icon name={j.icon} className="h-5 w-5" />
            <span className="absolute -end-1.5 -top-1.5 grid h-5 w-5 place-items-center rounded-full bg-white text-[10px] font-bold text-[#0b0d12]">{(i + 1).toLocaleString(locale === "ar" ? "ar-EG" : "en-US")}</span>
          </span>
          <div>
            <p className="font-display font-bold text-white md:mt-3">{j.title}</p>
            <p className="mt-1 text-sm leading-relaxed text-white/55">{j.body}</p>
          </div>
        </motion.li>
      ))}
    </ol>
  );
}

/** New customer vs. returning customer: two columns, the returning one lit. Qualitative only, no invented statistics. */
export function WinBackCompare({ w }: { w: ShopCopy["winback"] }) {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <div className="rounded-[1.5rem] border border-white/8 bg-white/[.02] p-6">
        <p className="flex items-center gap-2 font-display text-lg font-bold text-white/60"><Icon name="search" className="h-5 w-5" />{w.compareNew.title}</p>
        <ul className="mt-4 space-y-3">
          {w.compareNew.items.map((x) => <li key={x} className="flex items-start gap-2.5 text-sm text-white/50"><Icon name="minus" className="mt-0.5 h-4 w-4 shrink-0 text-white/30" />{x}</li>)}
        </ul>
      </div>
      <div className="relative overflow-hidden rounded-[1.5rem] border border-[#25d366]/40 bg-[linear-gradient(150deg,rgb(37_211_102/.14),rgb(124_92_255/.08))] p-6 shadow-[0_30px_80px_-40px_rgb(37_211_102/.6)]">
        <p className="flex items-center gap-2 font-display text-lg font-bold text-white"><Icon name="heart" className="h-5 w-5 text-[#5ef09a]" />{w.compareOld.title}</p>
        <ul className="mt-4 space-y-3">
          {w.compareOld.items.map((x) => <li key={x} className="flex items-start gap-2.5 text-sm text-white/85"><Icon name="check" className="mt-0.5 h-4 w-4 shrink-0 text-[#5ef09a]" />{x}</li>)}
        </ul>
      </div>
    </div>
  );
}
