"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useState } from "react";
import { Icon } from "@/components/icons";
import type { ServiceCopy } from "./copy";

const SLOTS = ["12:00", "13:30", "16:00"];
const dig = (s: string, locale: string) => (locale === "ar" ? s.replace(/\d/g, (d) => "٠١٢٣٤٥٦٧٨٩"[Number(d)]) : s);

/** The owner's day as a timeline: confirmed, one waiting for an answer, then a booking the assistant makes while a customer is chatting. */
export function HeroDay({ h }: { h: ServiceCopy["hero"] }) {
  const reduce = useReducedMotion();
  const [timed, setTimed] = useState(false);
  useEffect(() => {
    const id = setTimeout(() => setTimed(true), 1900);
    return () => clearTimeout(id);
  }, []);
  const added = timed || !!reduce;
  return (
    <motion.div initial={reduce ? false : { opacity: 0, y: 30 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.9, delay: 0.25 }} className="relative mx-auto w-full max-w-md">
      <div className="lp-svc-glow" aria-hidden />
      <div className="lp-svc-ticket relative rounded-[1.9rem] border border-[#1b1233]/10 bg-white p-5 shadow-[0_50px_90px_-35px_rgb(27_18_51/.5)] sm:p-6">
        <div className="flex items-center justify-between gap-3">
          <p className="font-display text-xl font-extrabold text-[#1b1233]">{h.dayTitle}</p>
          <span className="rounded-full bg-[#fde6ec] px-3 py-1 text-[11px] font-bold text-[#c93a6c]">{h.dayLive}</span>
        </div>
        <ol className="lp-svc-line relative mt-5 space-y-3">
          {h.day.map((x, i) => (
            <motion.li key={x.time} initial={reduce ? false : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.6, delay: 0.7 + i * 0.18 }} className="relative flex items-center gap-3 ps-8">
              <span className={`lp-svc-dot ${x.kind === "wait" ? "bg-amber-400" : "bg-emerald-500"}`} aria-hidden />
              <span className="w-12 shrink-0 text-sm font-bold tnum text-[#1b1233]" dir="ltr">{x.time}</span>
              <span className={`min-w-0 flex-1 rounded-xl border-s-4 px-3 py-2 ${x.kind === "wait" ? "border-amber-400 bg-amber-50" : "border-emerald-400 bg-emerald-50"}`}>
                <span className="block truncate text-sm font-bold text-[#1b1233]">{x.name}</span>
                <span className="block truncate text-xs text-[#1b1233]/60">{x.who}</span>
              </span>
            </motion.li>
          ))}
          <li className="relative flex min-h-[3.6rem] items-center gap-3 ps-8">
            <span className={`lp-svc-dot ${added ? "bg-[#e0457b]" : "bg-[#1b1233]/20"}`} aria-hidden />
            <span className="w-12 shrink-0 text-sm font-bold tnum text-[#1b1233]/40" dir="ltr">{h.newTime}</span>
            <AnimatePresence>
              {added && (
                <motion.span initial={reduce ? false : { opacity: 0, scale: 0.9, y: 8 }} animate={{ opacity: 1, scale: 1, y: 0 }} transition={{ type: "spring", stiffness: 300, damping: 22 }} className="lp-svc-pulse min-w-0 flex-1 rounded-xl border-s-4 border-[#e0457b] bg-[#fff1f4] px-3 py-2">
                  <span className="block truncate text-sm font-bold text-[#1b1233]">{h.newName}</span>
                  <span className="flex items-center gap-1 truncate text-xs font-semibold text-[#c93a6c]"><Icon name="sparkles" className="h-3.5 w-3.5 shrink-0" />{h.newWho}</span>
                </motion.span>
              )}
            </AnimatePresence>
          </li>
        </ol>
      </div>
      <motion.div initial={reduce ? false : { opacity: 0, y: 16, scale: 0.95 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ duration: 0.7, delay: 1.0 }} className="relative z-10 -mt-4 ms-auto w-[88%] max-w-xs rounded-2xl border border-[#1b1233]/10 bg-[#1b1233] p-4 text-white shadow-[0_30px_60px_-25px_rgb(27_18_51/.7)] sm:-mt-6">
        <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wide text-[#ff9bbb]"><Icon name="chat" className="h-3.5 w-3.5" />{h.bubbleFrom}</p>
        <p className="mt-1.5 rounded-xl rounded-ss-sm bg-white/10 px-3 py-2 text-sm">{h.bubbleText}</p>
        <p className="mt-1.5 rounded-xl rounded-se-sm bg-[#e0457b] px-3 py-2 text-sm">{h.bubbleReply}</p>
      </motion.div>
    </motion.div>
  );
}

/** The customer's side: a phone with the booking page, only free times. */
export function BookPhone({ b, locale }: { b: ServiceCopy["book"]; locale: string }) {
  const [pick, setPick] = useState(1);
  return (
    <div className="relative mx-auto w-[18.5rem] max-w-full">
      <div className="lp-svc-glow" aria-hidden />
      <div className="relative rounded-[2.4rem] border-[7px] border-[#1b1233] bg-white p-4 shadow-[0_50px_90px_-30px_rgb(27_18_51/.6)]">
        <div className="mx-auto mb-3 h-1.5 w-16 rounded-full bg-[#1b1233]/15" />
        <p className="font-display text-lg font-extrabold text-[#1b1233]">{b.phoneTitle}</p>
        <div className="mt-3 rounded-2xl bg-[#fde6ec] p-3"><p className="text-sm font-bold text-[#1b1233]">{b.phoneService}</p><p className="text-xs text-[#1b1233]/60">{dig("45", locale)} {b.phoneMin}</p></div>
        <p className="mt-4 text-xs font-bold text-[#1b1233]/60">{b.phoneDay}</p>
        <div className="mt-2 grid grid-cols-3 gap-2">
          {SLOTS.map((s, i) => <button key={s} type="button" aria-pressed={pick === i} onClick={() => setPick(i)} className={`min-h-11 rounded-full border text-sm font-bold tnum transition ${pick === i ? "border-[#e0457b] bg-[#e0457b] text-white" : "border-[#1b1233]/15 text-[#1b1233]"}`}>{dig(s, locale)}</button>)}
        </div>
        <p className="mt-3 flex items-center gap-1.5 text-[11px] text-[#1b1233]/55"><Icon name="check" className="h-3.5 w-3.5 text-[#e0457b]" />{b.phoneNote}</p>
        <button type="button" className="mt-4 w-full rounded-full bg-[#1b1233] py-3 text-sm font-bold text-white">{b.phoneConfirm}</button>
      </div>
    </div>
  );
}

/** A small illustration under three of the feature cards (catalog, free times, staff). Examples, not data. */
export function FeatureVisual({ k, f, locale }: { k: string; f: ServiceCopy["features"]; locale: string }) {
  if (k === "catalog") return (
    <ul className="mt-5 space-y-1.5" aria-hidden>
      {f.mockServices.map(([n, m]) => <li key={n} className="flex items-center justify-between gap-2 rounded-xl bg-[#fff1f4] px-3 py-2 text-sm font-semibold text-[#1b1233]"><span className="truncate">{n}</span><span className="shrink-0 text-xs text-[#1b1233]/55 tnum">{m} {f.mockMin}</span></li>)}
      <li className="rounded-xl border border-dashed border-[#e0457b]/40 px-3 py-1.5 text-xs font-semibold text-[#c93a6c]">{f.mockExtra} {f.mockMin}</li>
    </ul>
  );
  if (k === "slots") return (
    <div className="mt-5 flex flex-wrap gap-2" aria-hidden>
      {["12:00", "13:30", "16:00", "17:30"].map((s, i) => <span key={s} className={`rounded-full border px-3 py-1.5 text-sm font-bold tnum ${i === 1 ? "border-[#e0457b] bg-[#e0457b] text-white" : "border-[#1b1233]/15 text-[#1b1233]"}`} dir="ltr">{dig(s, locale)}</span>)}
    </div>
  );
  if (k === "staff") return (
    <ul className="mt-5 space-y-1.5" aria-hidden>
      {f.mockStaff.map(([n, d], i) => <li key={n} className="flex items-center gap-3 rounded-xl bg-[#f1eff5] px-3 py-2"><span className={`grid h-8 w-8 place-items-center rounded-full text-xs font-extrabold text-white ${i ? "bg-[#7c5cff]" : "bg-[#e0457b]"}`}>{n.slice(0, 1)}</span><span className="text-sm font-bold text-[#1b1233]">{n}</span><span className="ms-auto text-xs text-[#1b1233]/55">{d}</span></li>)}
    </ul>
  );
  return null;
}

/** A chat per kind of business, so an owner sees their own trade. */
export function ChatDemo({ c }: { c: ServiceCopy["chat"] }) {
  const [tab, setTab] = useState(c.tabs[0].key);
  return (
    <div className="mx-auto max-w-xl">
      <div className="mb-4 flex flex-wrap justify-center gap-2" role="tablist">
        {c.tabs.map((x) => <button key={x.key} type="button" role="tab" aria-selected={tab === x.key} onClick={() => setTab(x.key)} className={`min-h-11 rounded-full border px-4 text-sm font-bold transition ${tab === x.key ? "border-[#e0457b] bg-[#e0457b] text-white" : "border-[#1b1233]/15 bg-white text-[#1b1233]"}`}>{x.label}</button>)}
      </div>
      <div className="rounded-[1.75rem] border border-[#1b1233]/10 bg-white p-4 shadow-[0_40px_80px_-40px_rgb(27_18_51/.4)] sm:p-6">
        <AnimatePresence mode="wait">
          <motion.ul key={tab} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.25 }} className="space-y-3">
            {(c.convos[tab] ?? []).map(([who, text], i) => (
              <li key={i} className={`flex ${who === "c" ? "justify-start" : "justify-end"}`}>
                <span className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-[15px] leading-relaxed ${who === "c" ? "rounded-ss-md bg-[#f1eff5] text-[#1b1233]" : "rounded-se-md bg-[#1b1233] text-white"}`}>{text}</span>
              </li>
            ))}
          </motion.ul>
        </AnimatePresence>
      </div>
    </div>
  );
}

/** The owner's day: today's appointments, one waiting for an answer. */
export function DiaryMock({ d }: { d: ServiceCopy["calendar"] }) {
  return (
    <div className="rounded-[1.75rem] border border-[#1b1233]/10 bg-white p-5 shadow-[0_40px_80px_-40px_rgb(27_18_51/.35)] sm:p-6">
      <div className="grid grid-cols-3 gap-2">{d.stats.map((s) => <div key={s.label} className="rounded-2xl bg-[#fff1f4] p-3 text-center"><p className="font-display text-2xl font-extrabold text-[#e0457b]">{s.value}</p><p className="mt-0.5 text-[11px] font-semibold text-[#1b1233]/60">{s.label}</p></div>)}</div>
      <p className="mb-2 mt-5 text-sm font-bold text-[#1b1233]">{d.today}</p>
      <ul className="space-y-2">
        {d.items.map((x, i) => (
          <li key={x.time} className={`flex items-center gap-3 rounded-xl border-s-4 p-3 ${i === 1 ? "border-amber-400 bg-amber-50" : "border-emerald-400 bg-emerald-50"}`}>
            <span className="w-14 shrink-0 text-sm font-bold tnum text-[#1b1233]" dir="ltr">{x.time}</span>
            <span className="min-w-0 flex-1"><span className="block truncate text-sm font-bold text-[#1b1233]">{x.name}</span><span className="block truncate text-xs text-[#1b1233]/60">{x.who}</span></span>
            <span className="shrink-0 rounded-full bg-white px-2.5 py-1 text-[11px] font-bold text-[#1b1233]/70">{x.status}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
