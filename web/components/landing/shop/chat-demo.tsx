"use client";

/* eslint-disable @next/next/no-img-element */
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import { Icon } from "@/components/icons";
import { useSeen } from "../kit";
import type { ShopCopy } from "./copy";

type Channel = "WHATSAPP" | "INSTAGRAM" | "MESSENGER";
type Ev = { who: "c" | "a"; kind: "text" | "voice" | "products" | "order"; text?: string; brain?: number[] };

/** The scripted conversation: customer (c) and AI seller (a). `brain` = which "what the AI is doing" lines light up with that reply. */
function script(d: ShopCopy["demo"]): Ev[] {
  const s = d.script;
  return [
    { who: "c", kind: "text", text: s.c1 },
    { who: "a", kind: "products", text: s.a1, brain: [0, 1] },
    { who: "c", kind: "voice", text: s.voice },
    { who: "a", kind: "text", text: s.a2, brain: [2] },
    { who: "c", kind: "text", text: s.c2 },
    { who: "a", kind: "text", text: s.a3, brain: [3] },
    { who: "c", kind: "text", text: s.c3 },
    { who: "a", kind: "order", text: s.a4, brain: [4] },
  ];
}

const SKIN: Record<Channel, { head: string; bg: string; out: string; outText: string; inn: string; accent: string; name: string }> = {
  WHATSAPP: { head: "bg-[#075e54] text-white", bg: "bg-[#efeae2]", out: "bg-[#d9fdd3]", outText: "text-[#111b21]", inn: "bg-white", accent: "#25d366", name: "wa" },
  INSTAGRAM: { head: "bg-white text-[#111] border-b border-black/5", bg: "bg-white", out: "bg-[linear-gradient(135deg,#7638fa,#d300c5,#ff7a00)]", outText: "text-white", inn: "bg-[#efefef]", accent: "#d300c5", name: "ig" },
  MESSENGER: { head: "bg-white text-[#111] border-b border-black/5", bg: "bg-white", out: "bg-[#0a7cff]", outText: "text-white", inn: "bg-[#f0f0f0]", accent: "#0a7cff", name: "fb" },
};

/** A phone with a live, looping customer ⇄ AI seller conversation, next to a panel showing the AI's reasoning steps. Starts when visible; still for reduced motion. */
export function ChatDemo({ d, shopName }: { d: ShopCopy["demo"]; shopName: string }) {
  const reduce = useReducedMotion();
  const evs = useMemo(() => script(d), [d]);
  const [ref, seen] = useSeen<HTMLDivElement>();
  const [channel, setChannel] = useState<Channel>("WHATSAPP");
  const [n, setN] = useState(0);          // events shown
  const [typing, setTyping] = useState(false);
  const [run, setRun] = useState(0);      // bump to replay
  const shown = reduce ? evs.length : n;

  useEffect(() => {
    if (reduce || !seen) return;
    let t: ReturnType<typeof setTimeout>;
    if (n >= evs.length) { t = setTimeout(() => { setN(0); }, 6500); return () => clearTimeout(t); }
    const next = evs[n];
    if (next.who === "a") {
      t = setTimeout(() => setTyping(true), 500);
      const t2 = setTimeout(() => { setTyping(false); setN(n + 1); }, 500 + 1300 + Math.min(900, (next.text?.length ?? 0) * 12));
      return () => { clearTimeout(t); clearTimeout(t2); };
    }
    t = setTimeout(() => setN(n + 1), n === 0 ? 700 : 1500);
    return () => clearTimeout(t);
  }, [n, seen, reduce, run, evs]);

  const lit = new Set(evs.slice(0, shown).flatMap((e) => e.brain ?? []));
  const thinking = typing ? evs[n]?.brain ?? [] : [];
  const sk = SKIN[channel];
  const done = shown >= evs.length;

  return (
    <div ref={ref} className="grid items-center gap-10 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)]">
      {/* phone */}
      <div className="relative mx-auto w-full max-w-[380px]">
        <div className="absolute -inset-10 -z-10 rounded-full bg-[radial-gradient(closest-side,rgba(124,92,255,.35),transparent)] blur-2xl" aria-hidden />
        <div className="mb-4 flex justify-center gap-1.5" role="tablist" aria-label="Channel">
          {(Object.keys(SKIN) as Channel[]).map((c) => (
            <button key={c} role="tab" aria-selected={channel === c} onClick={() => setChannel(c)} className="relative rounded-full px-3.5 py-1.5 text-xs font-semibold text-white/70 transition hover:text-white aria-selected:text-white">
              {channel === c && <motion.span layoutId="demo-channel" className="absolute inset-0 -z-10 rounded-full bg-white/12 ring-1 ring-white/20" transition={{ type: "spring", stiffness: 420, damping: 34 }} />}
              {d.channels[c]}
            </button>
          ))}
        </div>
        <div className="lp-phone">
          <div className={`flex items-center gap-3 px-4 pb-3 pt-9 transition-colors duration-500 ${sk.head}`}>
            <span className="grid h-9 w-9 place-items-center rounded-full bg-[linear-gradient(135deg,#7c5cff,#22d3ee)] text-sm font-bold text-white">{shopName.slice(0, 1)}</span>
            <div className="min-w-0 flex-1 leading-tight">
              <p className="truncate text-sm font-bold">{shopName}</p>
              <p className="text-[11px] opacity-70">{typing ? <span className="lp-typing-label">•••</span> : d.online}</p>
            </div>
            <Icon name="phone" className="h-4 w-4 opacity-70" />
          </div>
          <div className={`relative flex h-[470px] flex-col justify-end gap-2 overflow-hidden px-3 pb-4 pt-3 transition-colors duration-500 ${sk.bg}`} aria-live="polite">
            <AnimatePresence initial={false}>
              {evs.slice(0, shown).map((e, i) => (
                <motion.div key={`${run}-${i}-${channel}`} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 16, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0 }}
                  transition={{ duration: 0.35, ease: [0.2, 0.8, 0.2, 1] }} className={`flex ${e.who === "c" ? "justify-end" : "justify-start"}`}>
                  <Bubble e={e} sk={sk} d={d} />
                </motion.div>
              ))}
              {typing && (
                <motion.div key="typing" layout initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, transition: { duration: 0.1 } }} className="flex justify-start">
                  <span className={`flex gap-1 rounded-2xl rounded-ss-md px-4 py-3 shadow-sm ${sk.inn}`}>{[0, 1, 2].map((k) => <span key={k} className="lp-dot h-1.5 w-1.5 rounded-full bg-slate-400" style={{ animationDelay: `${k * 0.15}s` }} />)}</span>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </div>
        {/* the order toast that lands in the owner's dashboard */}
        <AnimatePresence>
          {done && (
            <motion.div initial={{ opacity: 0, y: 20, scale: 0.9 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: -10 }} transition={{ type: "spring", stiffness: 300, damping: 22, delay: reduce ? 0 : 0.5 }}
              className="absolute inset-x-0 -bottom-7 mx-auto flex w-fit items-center gap-3 rounded-2xl border border-white/15 bg-[#0d1117]/90 px-4 py-3 text-white shadow-2xl backdrop-blur">
              <span className="grid h-9 w-9 place-items-center rounded-xl bg-[#a3e635] text-[#0d1117]"><Icon name="bag" className="h-5 w-5" /></span>
              <div className="leading-tight"><p className="text-xs text-white/60">{d.toast}</p><p className="font-display text-sm font-bold tnum">+ {d.script.total}</p></div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* reasoning */}
      <div className="lp-glass rounded-3xl p-6 sm:p-8">
        <div className="mb-6 flex items-center justify-between gap-3">
          <p className="flex items-center gap-2 text-sm font-semibold text-white/80"><span className="lp-orb h-2.5 w-2.5 rounded-full" />{d.brainTitle}</p>
          <button type="button" onClick={() => { setN(0); setTyping(false); setRun((r) => r + 1); }} className="inline-flex items-center gap-1.5 rounded-full border border-white/15 px-3 py-1.5 text-xs font-semibold text-white/70 transition hover:border-white/40 hover:text-white">
            <Icon name="return" className="h-3.5 w-3.5" />{d.replay}
          </button>
        </div>
        <ol className="space-y-3">
          {d.brain.map((b, i) => {
            const on = lit.has(i), busy = thinking.includes(i);
            return (
              <li key={i} className={`flex items-center gap-3 rounded-2xl border px-4 py-3.5 transition-all duration-500 ${on ? "border-[#a3e635]/30 bg-[#a3e635]/[.07] text-white" : busy ? "border-[#7c5cff]/50 bg-[#7c5cff]/10 text-white" : "border-white/[.07] text-white/35"}`}>
                <span className={`grid h-7 w-7 shrink-0 place-items-center rounded-full text-xs transition-colors duration-500 ${on ? "bg-[#a3e635] text-[#0d1117]" : busy ? "bg-[#7c5cff] text-white" : "bg-white/5"}`}>
                  {on ? <Icon name="check" className="h-4 w-4" /> : busy ? <span className="lp-spin h-3.5 w-3.5 rounded-full border-2 border-white/30 border-t-white" /> : i + 1}
                </span>
                <span className="text-sm font-medium">{b}</span>
              </li>
            );
          })}
        </ol>
      </div>
    </div>
  );
}

function Bubble({ e, sk, d }: { e: Ev; sk: (typeof SKIN)[Channel]; d: ShopCopy["demo"] }) {
  const mine = e.who === "c";
  const base = `max-w-[82%] rounded-2xl px-3.5 py-2.5 text-[13.5px] leading-relaxed shadow-sm ${mine ? `${sk.out} ${sk.outText} rounded-se-md` : `${sk.inn} text-[#111b21] rounded-ss-md`}`;
  if (e.kind === "voice") return (
    <div className={`${base} flex w-56 items-center gap-2.5`}>
      <span className="grid h-8 w-8 place-items-center rounded-full bg-black/10"><Icon name="play" className="h-3.5 w-3.5" /></span>
      <span className="flex h-6 flex-1 items-center gap-[3px]" aria-hidden>{[5, 9, 14, 8, 16, 11, 6, 13, 9, 15, 7, 10, 5, 12, 8].map((h, i) => <span key={i} className="w-[3px] rounded-full bg-current opacity-60" style={{ height: h }} />)}</span>
      <span className="text-[11px] opacity-70 tnum">{e.text}</span>
    </div>
  );
  if (e.kind === "products") return (
    <div className="max-w-[92%] space-y-2">
      <p className={base}>{e.text}</p>
      <div className="flex gap-2">
        {d.products.map((p, i) => (
          <motion.div key={p.name} initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.15 + i * 0.12 }} className={`w-[31%] overflow-hidden rounded-xl bg-white shadow-sm ring-1 ${i === 1 ? "ring-2 ring-[#7c5cff]" : "ring-black/5"}`}>
            <img src={p.img} alt="" className="aspect-square w-full bg-[#eef0f4] object-contain p-1.5" loading="lazy" />
            <div className="px-1.5 pb-1.5 pt-1"><p className="truncate text-[10px] font-semibold text-[#111]">{p.name}</p><p className="text-[10px] font-bold text-[#7c5cff] tnum">{p.price}</p></div>
          </motion.div>
        ))}
      </div>
    </div>
  );
  if (e.kind === "order") return (
    <div className="max-w-[86%] space-y-2">
      <p className={base}>{e.text}</p>
      <div className="overflow-hidden rounded-2xl bg-white shadow-md ring-1 ring-black/5">
        <div className="flex items-center gap-2 bg-[#0d1117] px-3.5 py-2 text-white"><Icon name="check" className="h-4 w-4 text-[#a3e635]" /><span className="text-xs font-bold">{d.script.order}</span></div>
        <div className="flex items-center gap-3 p-3">
          <img src={d.products[1].img} alt="" className="h-12 w-12 rounded-lg bg-[#eef0f4] object-contain p-1" />
          <div className="min-w-0 text-[12px] leading-snug text-[#111]"><p className="font-semibold">{d.script.orderLine}</p><p className="text-[#666]">{d.script.orderPay}</p></div>
        </div>
      </div>
    </div>
  );
  return <p className={base}>{e.text}</p>;
}
