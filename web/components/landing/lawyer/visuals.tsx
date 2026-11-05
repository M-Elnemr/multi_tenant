"use client";

import { AnimatePresence, motion, useReducedMotion, useScroll, useTransform } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { Icon } from "@/components/icons";
import { Counter, useSeen } from "../kit";
import type { LawyerCopy } from "./copy";

const E = [0.25, 0.7, 0.2, 1] as const;   // slow, stately
const NAVY = "#14213d", BRASS = "#b08d57";

function useTick(ms: number, seen: boolean) {
  const reduce = useReducedMotion();
  const [n, setN] = useState(0);
  useEffect(() => {
    if (!seen || reduce) return;
    const id = setInterval(() => setN((x) => x + 1), ms);
    return () => clearInterval(id);
  }, [ms, seen, reduce]);
  return n;
}

/** Hero: the scales of justice drawn in brass line work, a case file card, and a "hearing in 1 hour" reminder sliding in. */
export function HeroCase({ h }: { h: LawyerCopy["hero"] }) {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start start", "end start"] });
  const yA = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [0, -70]);
  const yB = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [0, -160]);
  const draw = (d: number) => (reduce ? {} : { initial: { pathLength: 0, opacity: 0 }, animate: { pathLength: 1, opacity: 1 }, transition: { duration: 1.8, ease: E, delay: d } });
  return (
    <div ref={ref} className="relative mx-auto w-full max-w-[520px]" aria-hidden>
      <svg viewBox="0 0 400 300" className="absolute inset-x-0 -top-10 mx-auto w-[92%] opacity-70">
        <g fill="none" stroke={BRASS} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <motion.path d="M200 30 V270 M140 270 H260 M90 70 H310" {...draw(0.2)} />
          <motion.path d="M200 30 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0" {...draw(0.1)} />
          <motion.path d="M90 70 L55 150 M90 70 L125 150 M50 150 Q90 185 130 150 Z" {...draw(0.7)} />
          <motion.path d="M310 70 L275 150 M310 70 L345 150 M270 150 Q310 185 350 150 Z" {...draw(0.9)} />
        </g>
      </svg>
      <motion.div style={{ y: yA }} className="relative pt-28">
        <motion.div initial={reduce ? false : { opacity: 0, y: 30 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 1.1, ease: E, delay: 0.5 }} className="lp-folder">
          <div className="lp-folder-tab">{h.caseLabel}</div>
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <p className="lp-classic text-xl font-semibold leading-snug text-[#14213d]">{h.caseTitle}</p>
              <p className="mt-1.5 text-sm text-[#14213d]/60">{h.court}</p>
            </div>
            <span className="shrink-0 rounded-full border border-[#b08d57]/40 px-2.5 py-1 text-[11px] font-bold text-[#8a6a3a]">{h.status}</span>
          </div>
          <div className="mt-5 flex items-center gap-3 rounded-xl bg-[#14213d] px-4 py-3.5 text-[#f5efe0]">
            <Icon name="gavel" className="h-5 w-5 text-[#d9b97f]" />
            <div className="flex-1"><p className="text-xs opacity-60">{h.next}</p><p className="font-semibold">{h.nextWhen}</p></div>
          </div>
          <div className="mt-4 grid grid-cols-5 gap-1.5">{[1, 1, 1, 0, 0].map((on, i) => <motion.span key={i} className="h-1 rounded-full" style={{ background: on ? BRASS : "rgba(20,33,61,.1)" }} initial={reduce ? false : { scaleX: 0 }} animate={{ scaleX: 1 }} transition={{ delay: 1 + i * 0.1, duration: 0.5 }} />)}</div>
        </motion.div>
      </motion.div>
      <motion.div style={{ y: yB }} className="absolute -bottom-6 start-[-6%] w-[64%]">
        <motion.div initial={reduce ? false : { opacity: 0, x: -24 }} animate={{ opacity: 1, x: 0 }} transition={{ duration: 0.9, ease: E, delay: 1.4 }}
          className="flex items-center gap-3 rounded-2xl border border-[#b08d57]/30 bg-[#fbf8f1] p-3.5 shadow-[0_30px_60px_-25px_rgba(20,33,61,.5)]">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-[#b08d57] text-white"><Icon name="bell" className="h-5 w-5" /></span>
          <div className="min-w-0"><p className="text-sm font-bold text-[#14213d]">{h.remind}</p><p className="truncate text-xs text-[#14213d]/55">{h.remindBody}</p></div>
        </motion.div>
      </motion.div>
    </div>
  );
}

/** A case timeline that advances step by step, with the fee ledger beside it. */
export function CaseTimeline({ c, locale }: { c: LawyerCopy["cases"]; locale: string }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  const tick = useTick(1600, seen);
  const at = reduce ? 3 : Math.min(c.steps.length - 1, tick % (c.steps.length + 2));
  const fmt = (n: number) => (locale === "ar" ? `${n.toLocaleString("ar-EG")} ج` : `EGP ${n.toLocaleString("en-US")}`);
  return (
    <div ref={ref} className="lp-ivory-card grid gap-6 p-6 sm:grid-cols-[1.2fr_1fr] sm:p-8">
      <ol className="relative space-y-5 ps-8 before:absolute before:inset-y-1 before:start-[11px] before:w-px before:bg-[#14213d]/12">
        {c.steps.map((s, i) => {
          const done = i < at, cur = i === at;
          return (
            <li key={s} className="relative">
              <motion.span className="absolute -start-8 top-0.5 grid h-6 w-6 place-items-center rounded-full border-2" animate={{ background: done ? NAVY : cur ? BRASS : "#fbf8f1", borderColor: done ? NAVY : cur ? BRASS : "rgba(20,33,61,.2)" }} transition={{ duration: 0.5 }}>
                {done && <Icon name="check" className="h-3 w-3 text-[#f5efe0]" />}
              </motion.span>
              <p className={`font-semibold transition-colors duration-500 ${done || cur ? "text-[#14213d]" : "text-[#14213d]/35"}`}>{s}</p>
            </li>
          );
        })}
      </ol>
      <div className="flex flex-col gap-3">
        <div className="rounded-2xl bg-[#14213d] p-5 text-[#f5efe0]">
          <p className="text-xs opacity-60">{c.fees}</p>
          <p className="lp-classic mt-1 text-3xl font-semibold tnum"><Counter value={c.feeTotal} locale={locale} prefix={locale === "ar" ? "" : "EGP "} suffix={locale === "ar" ? " ج" : ""} /></p>
          <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-white/15"><motion.div className="h-full origin-left rounded-full bg-[#d9b97f] rtl:origin-right" initial={reduce ? false : { scaleX: 0 }} animate={seen ? { scaleX: c.feePaid / c.feeTotal } : undefined} transition={{ duration: 1.4, ease: E }} /></div>
          <div className="mt-3 flex justify-between text-xs"><span className="opacity-70">{c.paid}: <b className="tnum">{fmt(c.feePaid)}</b></span><span className="opacity-70">{c.due}: <b className="tnum">{fmt(c.feeTotal - c.feePaid)}</b></span></div>
        </div>
        <div className="rounded-2xl border border-[#14213d]/10 p-4">
          <p className="mb-2 text-xs font-bold text-[#14213d]/50">{c.docs}</p>
          <div className="flex gap-2">{[0, 1, 2].map((k) => <span key={k} className="grid h-14 flex-1 place-items-center rounded-lg bg-[#f1ead9] text-[#8a6a3a]"><Icon name="file" className="h-5 w-5" /></span>)}</div>
        </div>
        <p className="text-[11px] text-[#14213d]/40">{c.sample}</p>
      </div>
    </div>
  );
}

/** Tomorrow's agenda, items settling in, with the evening digest notification. */
export function Agenda({ a }: { a: LawyerCopy["agenda"] }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  const color: Record<string, string> = { hearing: BRASS, session: NAVY, note: "#7a8699" };
  return (
    <div ref={ref} className="relative">
      <div className="lp-ivory-card overflow-hidden">
        <div className="flex items-center justify-between border-b border-[#14213d]/[.07] px-6 py-4"><p className="lp-classic text-lg font-semibold text-[#14213d]">{a.day}</p><Icon name="calendar" className="h-5 w-5 text-[#b08d57]" /></div>
        <ul className="divide-y divide-[#14213d]/[.06]">
          {a.items.map((it, i) => (
            <motion.li key={it.time} initial={reduce ? false : { opacity: 0, x: 20 }} animate={seen ? { opacity: 1, x: 0 } : undefined} transition={{ duration: 0.7, ease: E, delay: i * 0.12 }} className="flex items-center gap-4 px-6 py-4">
              <span className="w-12 text-sm font-semibold text-[#14213d]/55 tnum" dir="ltr">{it.time}</span>
              <span className="h-10 w-1 rounded-full" style={{ background: color[it.kind] }} />
              <div className="min-w-0 flex-1"><p className="truncate font-semibold text-[#14213d]">{it.title}</p><p className="truncate text-sm text-[#14213d]/55">{it.sub}</p></div>
              <span className="hidden rounded-full px-2.5 py-1 text-[11px] font-bold sm:inline" style={{ color: color[it.kind], background: `${color[it.kind]}18` }}>{a.kinds[it.kind as keyof typeof a.kinds]}</span>
            </motion.li>
          ))}
        </ul>
      </div>
      <motion.div initial={reduce ? false : { opacity: 0, y: 20 }} animate={seen ? { opacity: 1, y: 0 } : undefined} transition={{ duration: 0.8, ease: E, delay: 0.8 }}
        className="absolute -bottom-7 end-[-4%] flex items-center gap-3 rounded-2xl bg-[#14213d] ring-1 ring-[#d9b97f]/30 px-4 py-3 text-[#f5efe0] shadow-[0_24px_50px_-20px_rgba(20,33,61,.7)]">
        <Icon name="moon" className="h-5 w-5 text-[#d9b97f]" /><div><p className="text-sm font-bold">{a.digest}</p><p className="text-xs opacity-65">{a.digestBody}</p></div>
      </motion.div>
    </div>
  );
}

const DESIGNS = [
  { bg: "#f8f5ee", ink: "#14213d", accent: "#b08d57", serif: true },
  { bg: "#ffffff", ink: "#0f172a", accent: "#2f4a7a", serif: false },
  { bg: "#1b1410", ink: "#efe2c6", accent: "#c9a46a", serif: true },
];

/** The three office designs, and a session request being approved. */
export function OfficeSite({ w }: { w: LawyerCopy["website"] }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const tick = useTick(3000, seen);
  const reduce = useReducedMotion();
  const [pick, setPick] = useState<number | null>(null);
  const i = pick ?? tick % DESIGNS.length;
  const d = DESIGNS[i];
  const approved = reduce || tick % 2 === 1;
  return (
    <div ref={ref} className="relative">
      <div className="overflow-hidden rounded-[1.3rem] border border-[#14213d]/10 shadow-[0_50px_100px_-40px_rgba(20,33,61,.5)]">
        <div className="flex items-center gap-1.5 border-b border-black/5 bg-[#efe9dc] px-4 py-3">
          {["#ff5f57", "#febc2e", "#28c840"].map((c) => <span key={c} className="h-2.5 w-2.5 rounded-full" style={{ background: c }} />)}
          <span className="ms-3 flex-1 truncate rounded-md bg-white/80 px-3 py-1 text-xs text-slate-500" dir="ltr">your-office.elmanassa.shop</span>
        </div>
        <AnimatePresence mode="wait">
          <motion.div key={i} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.6, ease: E }} className="aspect-[16/10] p-6 sm:p-8" style={{ background: d.bg, color: d.ink }}>
            <div className="flex items-center justify-between"><span className="flex items-center gap-2"><Icon name="scale" className="h-5 w-5" style={{ color: d.accent }} /><span className="h-2.5 w-24 rounded-full" style={{ background: d.ink, opacity: 0.8 }} /></span><span className="h-7 w-24 rounded-sm" style={{ background: d.accent }} /></div>
            <div className="mt-[8%] max-w-[70%] space-y-3">
              <span className="block h-1 w-12" style={{ background: d.accent }} />
              <span className={`block h-6 w-11/12 ${d.serif ? "rounded-none" : "rounded-md"}`} style={{ background: d.ink }} />
              <span className={`block h-6 w-2/3 ${d.serif ? "rounded-none" : "rounded-md"}`} style={{ background: d.ink }} />
              <span className="block h-2.5 w-full rounded" style={{ background: d.ink, opacity: 0.2 }} />
              <span className="block h-2.5 w-4/5 rounded" style={{ background: d.ink, opacity: 0.2 }} />
            </div>
            <div className="mt-[6%] grid grid-cols-3 gap-3">{[0, 1, 2].map((k) => <span key={k} className="h-12 border" style={{ borderColor: `${d.accent}66` }} />)}</div>
          </motion.div>
        </AnimatePresence>
      </div>
      <div className="mt-5 flex justify-center gap-2">
        {w.designs.map((n, k) => (
          <button key={n} type="button" onClick={() => setPick(k)} aria-pressed={k === i} className="relative px-4 py-1.5 text-sm font-semibold text-[#14213d]/60 transition hover:text-[#14213d] aria-pressed:text-[#f5efe0]">
            {k === i && <motion.span layoutId="law-design" className="absolute inset-0 -z-10 bg-[#14213d]" transition={{ type: "spring", stiffness: 380, damping: 32 }} />}{n}
          </button>
        ))}
      </div>
      <motion.div initial={reduce ? false : { opacity: 0, y: 20 }} animate={seen ? { opacity: 1, y: 0 } : undefined} transition={{ duration: 0.8, ease: E, delay: 0.5 }}
        className="absolute -top-12 end-[-3%] w-[62%] rounded-2xl border border-[#b08d57]/30 bg-[#fbf8f1] p-4 shadow-[0_30px_60px_-25px_rgba(20,33,61,.5)] sm:w-[48%]">
        <p className="text-xs font-bold text-[#8a6a3a]">{w.request}</p>
        <p className="mt-0.5 font-semibold text-[#14213d]">{w.requestBody}</p>
        <AnimatePresence mode="wait" initial={false}>
          {approved ? <motion.p key="ok" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} className="mt-3 flex items-center gap-1.5 text-sm font-semibold text-[#2f6b52]"><Icon name="check" className="h-4 w-4" />{w.approved}</motion.p>
            : <motion.div key="btn" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="mt-3 flex gap-2"><span className="flex-1 rounded-lg bg-[#14213d] py-2 text-center text-xs font-bold text-[#f5efe0]">{w.approve}</span><span className="flex-1 rounded-lg border border-[#14213d]/15 py-2 text-center text-xs font-bold text-[#14213d]">{w.decline}</span></motion.div>}
        </AnimatePresence>
      </motion.div>
    </div>
  );
}
