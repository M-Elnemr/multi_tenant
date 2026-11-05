"use client";

/* eslint-disable @next/next/no-img-element */
import { motion, useReducedMotion, useScroll, useTransform } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { Icon } from "@/components/icons";
import { Counter, useSeen } from "../kit";
import type { ShopCopy } from "./copy";

const E = [0.2, 0.8, 0.2, 1] as const;

/** Hero stage: the AI core with conversations orbiting it and floating cards (order, reply time, a night sale) drifting at different depths on scroll. */
export function HeroStage({ h, d }: { h: ShopCopy["hero"]; d: ShopCopy["demo"] }) {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start start", "end start"] });
  const y1 = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [0, -120]);
  const y2 = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [0, -60]);
  const y3 = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [0, -180]);
  const float = (delay: number) => (reduce ? {} : { animate: { y: [0, -10, 0] }, transition: { duration: 6, repeat: Infinity, ease: "easeInOut" as const, delay } });
  return (
    <div ref={ref} className="relative mx-auto aspect-square w-full max-w-[560px]" aria-hidden>
      {/* rings */}
      {[1, 0.78, 0.56].map((s, i) => (
        <motion.div key={s} className="absolute inset-0 m-auto rounded-full border border-white/[.08]" style={{ width: `${s * 100}%`, height: `${s * 100}%` }}
          initial={reduce ? false : { scale: 0.6, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={{ duration: 1.4, ease: E, delay: 0.2 + i * 0.12 }} />
      ))}
      {/* orbiting chat bubbles */}
      {!reduce && (
        <motion.div className="absolute inset-0" animate={{ rotate: 360 }} transition={{ duration: 60, repeat: Infinity, ease: "linear" }}>
          {[0, 120, 240].map((deg, i) => (
            <div key={deg} className="absolute inset-0" style={{ transform: `rotate(${deg}deg)` }}>
              <span className="absolute left-1/2 top-[11%] block h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full" style={{ background: ["#25d366", "#d300c5", "#0a7cff"][i], boxShadow: `0 0 24px ${["#25d366", "#d300c5", "#0a7cff"][i]}` }} />
            </div>
          ))}
        </motion.div>
      )}
      {/* core */}
      <motion.div className="lp-core absolute inset-0 m-auto h-[38%] w-[38%] rounded-full" initial={reduce ? false : { scale: 0.4, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={{ duration: 1.2, ease: E, delay: 0.4 }}>
        <div className="absolute inset-[18%] grid place-items-center rounded-full bg-[#0b0d12]/70 backdrop-blur-sm"><Icon name="sparkles" className="h-1/3 w-1/3 text-white" /></div>
      </motion.div>

      {/* floating cards */}
      <motion.div style={{ y: y1 }} className="absolute start-0 top-[8%] w-[56%] sm:start-[-4%]">
        <motion.div {...float(0)} className="lp-float-card">
          <p className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold text-white/50"><span className="h-1.5 w-1.5 rounded-full bg-[#25d366]" />WhatsApp</p>
          <p className="rounded-xl rounded-ss-sm bg-white/10 px-3 py-2 text-[12.5px] text-white/90">{d.script.c1}</p>
        </motion.div>
      </motion.div>
      <motion.div style={{ y: y2 }} className="absolute bottom-[14%] end-0 w-[74%] sm:w-[58%] sm:end-[-6%]">
        <motion.div {...float(1.2)} className="lp-float-card">
          <div className="flex items-center gap-3">
            <img src={d.products[1].img} alt="" className="h-12 w-12 rounded-xl bg-white/90 object-contain p-1" />
            <div className="min-w-0 flex-1"><p className="text-[11px] font-semibold text-[#a3e635]">{h.floatOrder}</p><p className="truncate text-sm font-bold text-white">{d.script.orderLine}</p></div>
            <p className="font-display text-sm font-bold text-white tnum">{d.script.total}</p>
          </div>
        </motion.div>
      </motion.div>
      <motion.div style={{ y: y3 }} className="absolute end-[4%] top-[18%]">
        <motion.div {...float(0.6)} className="lp-pill-card"><Icon name="bolt" className="h-3.5 w-3.5 text-[#22d3ee]" />{h.floatReply}</motion.div>
      </motion.div>
      <motion.div style={{ y: y2 }} className="absolute bottom-[4%] start-[6%]">
        <motion.div {...float(2)} className="lp-pill-card"><Icon name="moon" className="h-3.5 w-3.5 text-[#c4b5fd]" />{h.floatNight}</motion.div>
      </motion.div>
    </div>
  );
}

/** A live-looking owner dashboard: today's sales counting up, the bar chart growing, the Copilot answering. */
export function DashPreview({ m, locale }: { m: ShopCopy["manage"]["dash"]; locale: string }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  const bars = [32, 48, 41, 66, 58, 74, 92];
  const [typed, setTyped] = useState(0);
  useEffect(() => {
    if (!seen || reduce) return;
    const id = setInterval(() => setTyped((n) => (n >= m.copilotA.length ? n : n + 2)), 28);
    return () => clearInterval(id);
  }, [seen, reduce, m.copilotA.length]);
  const answer = reduce ? m.copilotA : m.copilotA.slice(0, typed);
  return (
    <div ref={ref} className="lp-window">
      <div className="flex items-center gap-1.5 border-b border-black/[.06] px-4 py-3">
        {["#ff5f57", "#febc2e", "#28c840"].map((c) => <span key={c} className="h-2.5 w-2.5 rounded-full" style={{ background: c }} />)}
        <span className="ms-3 h-5 flex-1 rounded-md bg-black/[.04]" />
      </div>
      <div className="grid gap-3 p-4 sm:grid-cols-[1.3fr_1fr]">
        <div className="rounded-2xl bg-[#0d1117] p-4 text-white">
          <p className="text-xs text-white/50">{m.today}</p>
          <p className="mt-1 font-display text-3xl font-bold tnum"><Counter value={12840} locale={locale} prefix={locale === "ar" ? "" : "EGP "} suffix={locale === "ar" ? " ج" : ""} /></p>
          <p className="mt-1 text-xs text-white/60"><span className="text-[#a3e635]">{(23).toLocaleString(locale === "ar" ? "ar-EG" : "en-US")} {m.orders}</span> · {(14).toLocaleString(locale === "ar" ? "ar-EG" : "en-US")} {m.ai}</p>
          <div className="mt-4 flex h-24 items-end gap-1.5">
            {bars.map((b, i) => (
              <motion.span key={i} className={`flex-1 origin-bottom rounded-t-md ${i === bars.length - 1 ? "bg-[linear-gradient(#a3e635,#22d3ee)]" : "bg-white/15"}`} style={{ height: `${b}%` }}
                initial={reduce ? false : { scaleY: 0 }} animate={seen ? { scaleY: 1 } : undefined} transition={{ duration: 0.8, ease: E, delay: i * 0.07 }} />
            ))}
          </div>
        </div>
        <div className="flex flex-col gap-3">
          <div className="rounded-2xl border border-black/[.06] bg-white p-4">
            <p className="mb-2 text-xs font-semibold text-[#6b7280]">{m.top}</p>
            {[["/demo-colors/85624674.png", 86], ["/demo-colors/b865ad31.png", 64], ["/demo-colors/495912d5.png", 41]].map(([img, w], i) => (
              <div key={i} className="mb-2 flex items-center gap-2 last:mb-0">
                <img src={img as string} alt="" className="h-7 w-7 rounded-md bg-[#f1f2f4] object-contain p-0.5" />
                <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-black/[.05]"><motion.span className="block h-full origin-left rounded-full bg-[#7c5cff] rtl:origin-right" initial={reduce ? false : { scaleX: 0 }} animate={seen ? { scaleX: (w as number) / 100 } : undefined} transition={{ duration: 1, ease: E, delay: 0.3 + i * 0.1 }} /></span>
              </div>
            ))}
          </div>
          <div className="flex-1 rounded-2xl border border-black/[.06] bg-white p-4">
            <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold text-[#7c5cff]"><Icon name="sparkles" className="h-3.5 w-3.5" />Copilot</p>
            <p className="mb-2 ms-auto w-fit rounded-xl rounded-se-sm bg-[#f1f2f4] px-3 py-1.5 text-[12px] text-[#111]">{m.copilotQ}</p>
            <p className="min-h-10 text-[12.5px] leading-relaxed text-[#111]">{answer}{answer.length < m.copilotA.length && <span className="lp-caret" />}</p>
          </div>
        </div>
      </div>
    </div>
  );
}

/** The AI shop builder: a description types itself, tags are extracted, product cards assemble. */
export function CatalogBuilder({ c }: { c: ShopCopy["catalog"] }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  const [k, setK] = useState(0);
  useEffect(() => {
    if (!seen || reduce) return;
    const id = setInterval(() => setK((n) => (n >= c.input.length + 70 ? 0 : n + 1)), 34);
    return () => clearInterval(id);
  }, [seen, reduce, c.input.length]);
  const full = reduce ? c.input.length + 70 : k;
  const text = c.input.slice(0, full);
  const phase = full - c.input.length;  // >0 once typing is done
  const imgs = ["85624674", "b865ad31", "495912d5", "d5d9e1b7", "76080eea", "cbbe40bf"];
  return (
    <div ref={ref} className="lp-glass rounded-3xl p-5 sm:p-7">
      <div className="rounded-2xl border border-white/10 bg-black/30 p-4 text-[14px] leading-relaxed text-white/85">
        {text}{phase < 0 && <span className="lp-caret lp-caret-light" />}
      </div>
      <div className="mt-4 flex min-h-8 flex-wrap gap-2">
        {c.tags.map((t, i) => (
          <motion.span key={t} initial={false} animate={{ opacity: phase > i * 5 ? 1 : 0, scale: phase > i * 5 ? 1 : 0.8 }} transition={{ type: "spring", stiffness: 400, damping: 22 }}
            className="rounded-full border border-[#22d3ee]/30 bg-[#22d3ee]/10 px-3 py-1 text-xs font-semibold text-[#a5f3fc]">{t}</motion.span>
        ))}
      </div>
      <div className="mt-5 grid grid-cols-3 gap-2.5">
        {imgs.map((im, i) => {
          const on = phase > 20 + i * 6;
          return (
            <motion.div key={im} initial={false} animate={{ opacity: on ? 1 : 0.12, y: on ? 0 : 10 }} transition={{ duration: 0.5, ease: E }} className="overflow-hidden rounded-xl bg-white/[.06] ring-1 ring-white/10">
              <img src={`/demo-colors/${im}.png`} alt="" className="aspect-square w-full bg-white/90 object-contain p-2" loading="lazy" />
              <div className="space-y-1 p-2"><span className="block h-1.5 w-4/5 rounded bg-white/25" /><span className="block h-1.5 w-2/5 rounded bg-[#a3e635]/60" /></div>
            </motion.div>
          );
        })}
      </div>
      <p className="mt-4 flex items-center gap-2 text-sm font-semibold text-white/70">
        {phase > 56 ? <Icon name="check" className="h-4 w-4 text-[#a3e635]" /> : <span className="lp-spin h-3.5 w-3.5 rounded-full border-2 border-white/25 border-t-white" />}
        {phase > 56 ? `6 ${c.made}` : c.building}
      </p>
    </div>
  );
}

const CH_COLOR: Record<string, string> = { WHATSAPP: "#25d366", INSTAGRAM: "#d300c5", MESSENGER: "#0a7cff", WEB: "#a3e635" };
const CH_ICON: Record<string, string> = { WHATSAPP: "phone", INSTAGRAM: "camera", MESSENGER: "chat", WEB: "globe" };

/** Channels converging into one inbox: lines draw from each channel to the AI. */
export function ChannelsHub({ list }: { list: ShopCopy["channels"]["list"] }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  return (
    <div ref={ref}>
      <ul className="grid gap-3 sm:grid-cols-2">
        {list.map((ch, i) => (
          <motion.li key={ch.key} initial={reduce ? false : { opacity: 0, y: 18 }} animate={seen ? { opacity: 1, y: 0 } : undefined} transition={{ duration: 0.6, ease: E, delay: i * 0.1 }}
            className="lp-glass group rounded-2xl p-5 transition hover:border-white/25">
            <span className="mb-4 grid h-11 w-11 place-items-center rounded-xl" style={{ background: `${CH_COLOR[ch.key]}22`, color: CH_COLOR[ch.key] }}><Icon name={CH_ICON[ch.key]} className="h-5 w-5" /></span>
            <p className="font-display text-lg font-bold text-white">{ch.name}</p>
            <p className="mt-1 text-sm leading-relaxed text-white/60">{ch.body}</p>
          </motion.li>
        ))}
      </ul>
    </div>
  );
}

/** Insight panel: sales line drawing itself, KPI counters, orders by area. Figures are labelled as examples. */
export function InsightsPanel({ s, locale }: { s: ShopCopy["insights"]; locale: string }) {
  const [ref, seen] = useSeen<HTMLDivElement>();
  const reduce = useReducedMotion();
  const pts = [18, 26, 22, 34, 30, 44, 40, 52, 47, 61, 58, 70, 66, 82];
  const W = 560, H = 160;
  const path = pts.map((p, i) => `${i === 0 ? "M" : "L"}${(i / (pts.length - 1)) * W},${H - (p / 90) * H}`).join(" ");
  return (
    <div ref={ref} className="lp-window p-5 sm:p-7">
      <div className="grid gap-4 sm:grid-cols-3">
        {s.kpis.map((k) => (
          <div key={k.label} className="rounded-2xl bg-[#f6f6f3] p-4">
            <p className="text-xs font-semibold text-[#6b7280]">{k.label}</p>
            <p className="mt-1 font-display text-2xl font-bold text-[#0d1117] tnum"><Counter value={k.value} locale={locale} prefix={"prefix" in k ? k.prefix : ""} suffix={"suffix" in k ? k.suffix : ""} /></p>
          </div>
        ))}
      </div>
      <svg viewBox={`0 0 ${W} ${H + 10}`} className="mt-6 h-40 w-full overflow-visible" aria-hidden>
        <defs><linearGradient id="lp-area" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="#7c5cff" stopOpacity=".28" /><stop offset="1" stopColor="#7c5cff" stopOpacity="0" /></linearGradient></defs>
        <motion.path d={`${path} L${W},${H} L0,${H} Z`} fill="url(#lp-area)" initial={reduce ? false : { opacity: 0 }} animate={seen ? { opacity: 1 } : undefined} transition={{ duration: 1, delay: 0.8 }} />
        <motion.path d={path} fill="none" stroke="#7c5cff" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" initial={reduce ? false : { pathLength: 0 }} animate={seen ? { pathLength: 1 } : undefined} transition={{ duration: 1.6, ease: E }} />
      </svg>
      <div className="mt-5 space-y-2.5">
        {s.areas.map(([a, v], i) => (
          <div key={a} className="flex items-center gap-3 text-sm">
            <span className="w-24 shrink-0 font-medium text-[#374151]">{a}</span>
            <span className="h-2 flex-1 overflow-hidden rounded-full bg-[#f1f2f4]"><motion.span className="block h-full origin-left rounded-full bg-[linear-gradient(90deg,#7c5cff,#22d3ee)] rtl:origin-right" initial={reduce ? false : { scaleX: 0 }} animate={seen ? { scaleX: v / 50 } : undefined} transition={{ duration: 1, ease: E, delay: 0.4 + i * 0.1 }} /></span>
            <span className="w-10 text-end font-semibold text-[#111] tnum">{locale === "ar" ? `${v.toLocaleString("ar-EG")}٪` : `${v}%`}</span>
          </div>
        ))}
      </div>
      <p className="mt-4 text-[11px] text-[#9ca3af]">{s.sample}</p>
    </div>
  );
}
