"use client";

import { AnimatePresence, motion, useReducedMotion, useScroll, useTransform } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { Icon } from "@/components/icons";
import { Counter, useSeen } from "../kit";
import type { ClinicCopy } from "./copy";

const E = [0.22, 0.75, 0.25, 1] as const;
const TEAL = "#0f5e57";
const STATUS_COLOR = ["#94a3b8", "#6c9a8b", "#c08a3e", "#0f5e57", "#3f7d5a"];

/** Ticks every `ms` while visible (calm looping demos); still for reduced motion. */
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

/** Hero: an ECG line drawing itself behind a "today" card (in the room / next / waiting) and a patient's "your turn" notification. */
export function HeroDay({ h, slots, locale }: { h: ClinicCopy["hero"]; slots: ClinicCopy["booking"]["slots"]; locale: string }) {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start start", "end start"] });
  const yA = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [0, -80]);
  const yB = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [0, -150]);
  const [seenRef, seen] = useSeen<HTMLDivElement>();
  const tick = useTick(3200, seen);
  const waiting = 3 + (tick % 3);
  const progress = reduce ? 0.6 : ((tick % 6) + 1) / 7;
  return (
    <div ref={ref} className="relative mx-auto w-full max-w-[540px]" aria-hidden>
      <div ref={seenRef} className="absolute inset-0" />
      <svg viewBox="0 0 600 200" className="absolute inset-x-[-10%] top-1/2 w-[120%] -translate-y-1/2 overflow-visible opacity-60 [mask-image:linear-gradient(90deg,transparent,#000_25%,#000_75%,transparent)]">
        <motion.path d="M0 100 H170 L190 100 L205 60 L222 150 L240 30 L258 140 L272 100 H600" fill="none" stroke="#9cc5b4" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
          initial={reduce ? false : { pathLength: 0 }} animate={{ pathLength: 1 }} transition={{ duration: 2.2, ease: E, delay: 0.3 }} />
      </svg>
      <motion.div style={{ y: yA }} className="relative">
        <motion.div initial={reduce ? false : { opacity: 0, y: 30 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 1, ease: E, delay: 0.2 }} className="lp-paper-card p-5 sm:p-6">
          <div className="flex items-center justify-between">
            <p className="font-semibold text-[#10302d]">{h.today}</p>
            <span className="inline-flex items-center gap-1.5 rounded-full bg-[#e7f0ea] px-2.5 py-1 text-xs font-semibold text-[#2f6b52]"><span className="h-1.5 w-1.5 rounded-full bg-[#3f7d5a] [animation:live-dot_2s_infinite]" />{waiting.toLocaleString(locale === "ar" ? "ar-EG" : "en-US")} {h.waiting}</span>
          </div>
          <div className="mt-4 rounded-2xl bg-[#0f3d3a] p-4 text-[#f3efe6]">
            <p className="text-xs opacity-70">{h.inRoom}</p>
            <div className="mt-1 flex items-center justify-between gap-3"><p className="font-semibold">{slots[0].name}</p><p className="text-xs opacity-70 tnum" dir="ltr">{slots[0].time}</p></div>
            <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-white/15"><motion.div className="h-full origin-left rounded-full bg-[#cfe3d6] rtl:origin-right" animate={{ scaleX: progress }} transition={{ duration: 1.2, ease: E }} /></div>
          </div>
          <ul className="mt-3 space-y-2">
            {slots.slice(1).map((s, i) => (
              <motion.li key={s.name} initial={reduce ? false : { opacity: 0, x: 16 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.6 + i * 0.12, duration: 0.6, ease: E }}
                className="flex items-center gap-3 rounded-xl border border-[#10302d]/[.06] bg-white px-3 py-2.5">
                <span className="w-11 text-xs font-semibold text-[#10302d]/50 tnum" dir="ltr">{s.time}</span>
                <span className="min-w-0 flex-1 truncate text-sm font-semibold text-[#10302d]">{s.name}</span>
                <span className="text-xs text-[#10302d]/50">{s.type}</span>
                {i === 0 && <span className="rounded-full bg-[#f6ead7] px-2 py-0.5 text-[10px] font-bold text-[#9a6a26]">{h.next}</span>}
              </motion.li>
            ))}
          </ul>
        </motion.div>
      </motion.div>
      <motion.div style={{ y: yB }} className="absolute -bottom-8 end-[-4%] w-[62%] sm:end-[-10%]">
        <motion.div initial={reduce ? false : { opacity: 0, y: 30, scale: 0.94 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ duration: 0.9, ease: E, delay: 1.1 }}
          className="flex items-center gap-3 rounded-2xl border border-white/60 bg-white/85 p-3.5 shadow-[0_30px_60px_-25px_rgba(16,48,45,.45)] backdrop-blur">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-[#0f5e57] text-white"><Icon name="bell" className="h-5 w-5" /></span>
          <div className="min-w-0"><p className="text-sm font-bold text-[#10302d]">{h.turn}</p><p className="truncate text-xs text-[#10302d]/55">{slots[1].name} · <span dir="ltr">{slots[1].time}</span></p></div>
        </motion.div>
      </motion.div>
    </div>
  );
}

/** Appointment board: each appointment walks through its statuses, one step at a time, on a loop. */
export function DayBoard({ b }: { b: ClinicCopy["booking"] }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const tick = useTick(1800, seen);
  return (
    <div ref={ref} className="lp-paper-card overflow-hidden">
      <div className="flex flex-wrap gap-1.5 border-b border-[#10302d]/[.06] p-4">
        {b.statuses.map((s, i) => <span key={s} className="inline-flex items-center gap-1.5 rounded-full bg-[#10302d]/[.04] px-2.5 py-1 text-[11px] font-semibold text-[#10302d]/70"><span className="h-1.5 w-1.5 rounded-full" style={{ background: STATUS_COLOR[i] }} />{s}</span>)}
      </div>
      <ul className="divide-y divide-[#10302d]/[.06]">
        {b.slots.map((s, i) => {
          const st = (s.status + tick) % 5;   // each appointment walks booked → done, then the next day starts
          return (
            <li key={s.name} className="flex items-center gap-4 px-4 py-4 sm:px-5">
              <span className="w-12 font-display text-sm font-semibold text-[#10302d]/60 tnum" dir="ltr">{s.time}</span>
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-[#e7f0ea] text-sm font-bold text-[#2f6b52]">{s.name.slice(0, 1)}</span>
              <div className="min-w-0 flex-1"><p className="truncate font-semibold text-[#10302d]">{s.name}</p><p className="text-xs text-[#10302d]/50">{s.type}</p></div>
              <div className="hidden w-32 items-center gap-1 sm:flex" aria-hidden>{[0, 1, 2, 3, 4].map((k) => <motion.span key={k} className="h-1.5 flex-1 rounded-full" animate={{ background: k <= st ? STATUS_COLOR[st] : "rgba(16,48,45,.08)" }} transition={{ duration: 0.5, delay: k * 0.04 }} />)}</div>
              <AnimatePresence mode="wait" initial={false}>
                <motion.span key={st + "-" + i} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }} transition={{ duration: 0.25 }}
                  className="w-24 shrink-0 rounded-full px-2.5 py-1 text-center text-[11px] font-bold text-white" style={{ background: STATUS_COLOR[st] }}>{b.statuses[st]}</motion.span>
              </AnimatePresence>
            </li>
          );
        })}
      </ul>
      <p className="px-5 pb-4 text-[11px] text-[#10302d]/40">{b.sample}</p>
    </div>
  );
}

/** A patient file: visits timeline, a prescription, a lab request, then the patient's uploaded result arriving. */
export function PatientFile({ p }: { p: ClinicCopy["patients"]["file"] }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  const tick = useTick(4200, seen);
  const showUpload = reduce || tick % 2 === 1;
  const rows: { icon: string; title: string; date: string; tone: string }[] = [
    { icon: "file", title: p.rx, date: "12/10", tone: "#0f5e57" },
    { icon: "flask", title: p.lab, date: "12/10", tone: "#c08a3e" },
    { icon: "stethoscope", title: p.last, date: "28/09", tone: "#6c9a8b" },
  ];
  return (
    <div ref={ref} className="relative">
      <div className="lp-paper-card p-5 sm:p-6">
        <div className="flex items-center gap-4">
          <span className="grid h-14 w-14 place-items-center rounded-2xl bg-[#0f3d3a] font-display text-xl font-bold text-[#f3efe6]">{p.name.slice(0, 1)}</span>
          <div className="min-w-0 flex-1"><p className="font-display text-lg font-bold text-[#10302d]">{p.name}</p><p className="text-sm text-[#10302d]/55">{p.age}</p></div>
          <span className="grid h-12 w-12 place-items-center rounded-xl border border-[#10302d]/10 text-[#10302d]"><Icon name="qr" className="h-7 w-7" /></span>
        </div>
        <p className="mb-3 mt-6 text-xs font-bold uppercase tracking-wider text-[#10302d]/45">{p.visits}</p>
        <ol className="relative space-y-3 ps-6 before:absolute before:inset-y-2 before:start-[7px] before:w-px before:bg-[#10302d]/10">
          {rows.map((r, i) => (
            <motion.li key={r.title} initial={reduce ? false : { opacity: 0, y: 10 }} animate={seen ? { opacity: 1, y: 0 } : undefined} transition={{ delay: i * 0.12, duration: 0.6, ease: E }} className="relative">
              <span className="absolute -start-6 top-3 h-3.5 w-3.5 rounded-full border-2 border-white" style={{ background: r.tone }} />
              <div className="flex items-center gap-3 rounded-xl bg-[#f7f4ee] px-3.5 py-3">
                <Icon name={r.icon} className="h-4 w-4" style={{ color: r.tone }} />
                <span className="flex-1 text-sm font-semibold text-[#10302d]">{r.title}</span>
                <span className="text-xs text-[#10302d]/45 tnum" dir="ltr">{r.date}</span>
              </div>
            </motion.li>
          ))}
        </ol>
      </div>
      <AnimatePresence>
        {showUpload && (
          <motion.div initial={{ opacity: 0, y: 16, scale: 0.95 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: 8 }} transition={{ type: "spring", stiffness: 260, damping: 22 }}
            className="absolute -top-5 end-[-3%] flex items-center gap-3 rounded-2xl bg-[#0f3d3a] px-4 py-3 text-[#f3efe6] shadow-[0_24px_50px_-20px_rgba(16,48,45,.6)] sm:end-[-8%]">
            <Icon name="flask" className="h-5 w-5 text-[#cfe3d6]" /><span className="text-sm font-semibold">{p.uploaded}</span>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

const DESIGNS: { bg: string; ink: string; accent: string; layout: "split" | "center" | "persona" | "grid" }[] = [
  { bg: "#ffffff", ink: "#10302d", accent: "#0f8f83", layout: "split" },
  { bg: "#14110f", ink: "#f2e8d8", accent: "#c9a46a", layout: "center" },
  { bg: "#f4efe7", ink: "#2a2420", accent: "#8a5a44", layout: "persona" },
  { bg: "#f2f8ff", ink: "#0d2a4a", accent: "#2b7de9", layout: "split" },
  { bg: "#fbf3f0", ink: "#3a2a2a", accent: "#c9877a", layout: "center" },
  { bg: "#f6f7f9", ink: "#13243b", accent: "#1f6feb", layout: "grid" },
];

/** Six clinic website designs cycling in a browser frame (abstract previews of each design's composition and palette). */
export function WebsiteShowcase({ w }: { w: ClinicCopy["website"] }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const tick = useTick(2600, seen);
  const [pick, setPick] = useState<number | null>(null);
  const i = pick ?? tick % DESIGNS.length;
  const d = DESIGNS[i];
  return (
    <div ref={ref}>
      <div className="overflow-hidden rounded-[1.4rem] border border-[#10302d]/10 bg-white shadow-[0_50px_100px_-40px_rgba(16,48,45,.45)]">
        <div className="flex items-center gap-1.5 border-b border-black/5 bg-[#f7f4ee] px-4 py-3">
          {["#ff5f57", "#febc2e", "#28c840"].map((c) => <span key={c} className="h-2.5 w-2.5 rounded-full" style={{ background: c }} />)}
          <span className="ms-3 flex-1 truncate rounded-md bg-white px-3 py-1 text-xs text-slate-500" dir="ltr">dr-yourname.elmanassa.shop</span>
        </div>
        <AnimatePresence mode="wait">
          <motion.div key={i} initial={{ opacity: 0, scale: 1.02 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.5, ease: E }} className="aspect-[16/10] p-5 sm:p-7" style={{ background: d.bg, color: d.ink }}>
            <div className="flex items-center justify-between"><span className="h-3 w-20 rounded-full" style={{ background: d.ink, opacity: 0.85 }} /><span className="flex gap-2">{[0, 1, 2].map((k) => <span key={k} className="h-2 w-10 rounded-full" style={{ background: d.ink, opacity: 0.2 }} />)}</span><span className="h-7 w-20 rounded-full" style={{ background: d.accent }} /></div>
            {d.layout === "split" && (
              <div className="mt-6 grid h-[70%] grid-cols-2 items-center gap-6">
                <div className="space-y-3"><span className="block h-5 w-11/12 rounded-md" style={{ background: d.ink }} /><span className="block h-5 w-3/4 rounded-md" style={{ background: d.ink }} /><span className="block h-2.5 w-full rounded" style={{ background: d.ink, opacity: 0.2 }} /><span className="block h-2.5 w-4/5 rounded" style={{ background: d.ink, opacity: 0.2 }} /><span className="mt-2 block h-9 w-32 rounded-full" style={{ background: d.accent }} /></div>
                <div className="h-full rounded-2xl" style={{ background: `linear-gradient(160deg, ${d.accent}55, ${d.accent}15)` }}><div className="m-auto mt-[18%] h-1/2 w-1/2 rounded-full" style={{ background: `${d.accent}55` }} /></div>
              </div>
            )}
            {d.layout === "center" && (
              <div className="mt-8 flex h-[65%] flex-col items-center justify-center gap-3 text-center"><span className="h-2 w-16 rounded-full" style={{ background: d.accent }} /><span className="block h-6 w-2/3 rounded-md" style={{ background: d.ink }} /><span className="block h-6 w-1/2 rounded-md" style={{ background: d.ink }} /><span className="block h-2.5 w-1/2 rounded" style={{ background: d.ink, opacity: 0.25 }} /><span className="mt-2 block h-9 w-36 rounded-full border-2" style={{ borderColor: d.accent }} /></div>
            )}
            {d.layout === "persona" && (
              <div className="mt-6 grid h-[70%] grid-cols-[0.8fr_1fr] items-end gap-6"><div className="h-full rounded-t-full" style={{ background: `linear-gradient(${d.accent}66, ${d.accent}22)` }} /><div className="space-y-3 pb-4"><span className="block h-2 w-14 rounded-full" style={{ background: d.accent }} /><span className="block h-7 w-11/12 rounded-md" style={{ background: d.ink }} /><span className="block h-2.5 w-full rounded" style={{ background: d.ink, opacity: 0.2 }} /><span className="block h-2.5 w-3/4 rounded" style={{ background: d.ink, opacity: 0.2 }} /></div></div>
            )}
            {d.layout === "grid" && (
              <div className="mt-6 space-y-4"><span className="block h-6 w-1/2 rounded-md" style={{ background: d.ink }} /><div className="grid grid-cols-4 gap-3">{Array.from({ length: 8 }).map((_, k) => <div key={k} className="space-y-2 rounded-xl p-2.5" style={{ background: `${d.accent}12` }}><span className="block aspect-square rounded-full" style={{ background: `${d.accent}40` }} /><span className="block h-2 rounded" style={{ background: d.ink, opacity: 0.4 }} /></div>)}</div></div>
            )}
          </motion.div>
        </AnimatePresence>
      </div>
      <div className="mt-5 flex flex-wrap justify-center gap-2">
        {w.designs.map((n, k) => (
          <button key={n} type="button" onClick={() => setPick(k)} aria-pressed={k === i} className="relative rounded-full px-3.5 py-1.5 text-sm font-semibold text-[#10302d]/60 transition hover:text-[#10302d] aria-pressed:text-white">
            {k === i && <motion.span layoutId="clinic-design" className="absolute inset-0 -z-10 rounded-full bg-[#0f3d3a]" transition={{ type: "spring", stiffness: 380, damping: 32 }} />}
            {n}
          </button>
        ))}
      </div>
    </div>
  );
}

/** Month insight card: counters and a calm bar chart. */
export function ClinicInsights({ s, locale }: { s: ClinicCopy["insights"]; locale: string }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  const bars = [52, 61, 58, 70, 66, 78, 74, 85, 80, 92, 88, 96];
  return (
    <div ref={ref} className="lp-paper-card p-5 sm:p-7">
      <div className="grid gap-3 sm:grid-cols-3">
        {s.kpis.map((k) => (
          <div key={k.label} className="rounded-2xl bg-[#f7f4ee] p-4">
            <p className="text-xs font-semibold text-[#10302d]/55">{k.label}</p>
            <p className="mt-1 font-display text-3xl font-semibold text-[#10302d] tnum"><Counter value={k.value} locale={locale} suffix={"suffix" in k ? k.suffix : ""} /></p>
          </div>
        ))}
      </div>
      <div className="mt-6 flex h-36 items-end gap-2" aria-hidden>
        {bars.map((b, i) => <motion.span key={i} className="flex-1 origin-bottom rounded-t-lg" style={{ height: `${b}%`, background: i === bars.length - 1 ? TEAL : "#cfe3d6" }} initial={reduce ? false : { scaleY: 0 }} animate={seen ? { scaleY: 1 } : undefined} transition={{ duration: 0.9, ease: E, delay: i * 0.05 }} />)}
      </div>
      <p className="mt-4 text-[11px] text-[#10302d]/40">{s.sample}</p>
    </div>
  );
}
