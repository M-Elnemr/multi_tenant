"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { Icon } from "@/components/icons";
import type { ServiceCopy } from "./copy";

const SLOTS = ["12:00", "13:30", "16:00"];

/** A phone with the booking page: the day, and only free times. */
export function HeroPhone({ h, locale }: { h: ServiceCopy["hero"]; locale: string }) {
  const reduce = useReducedMotion();
  const [pick, setPick] = useState(1);
  const dig = (s: string) => (locale === "ar" ? s.replace(/\d/g, (d) => "٠١٢٣٤٥٦٧٨٩"[Number(d)]) : s);
  return (
    <motion.div initial={reduce ? false : { opacity: 0, y: 30, rotate: -2 }} animate={{ opacity: 1, y: 0, rotate: 0 }} transition={{ duration: 0.9, delay: 0.3 }} className="relative mx-auto w-[18.5rem]">
      <div className="lp-svc-glow" aria-hidden />
      <div className="relative rounded-[2.4rem] border-[7px] border-[#1b1233] bg-white p-4 shadow-[0_50px_90px_-30px_rgb(27_18_51/.6)]">
        <div className="mx-auto mb-3 h-1.5 w-16 rounded-full bg-[#1b1233]/15" />
        <p className="font-display text-lg font-extrabold text-[#1b1233]">{h.phoneTitle}</p>
        <div className="mt-3 rounded-2xl bg-[#fde6ec] p-3"><p className="text-sm font-bold text-[#1b1233]">{h.phoneService}</p><p className="text-xs text-[#1b1233]/60">45 {locale === "ar" ? "دقيقة" : "min"}</p></div>
        <p className="mt-4 text-xs font-bold text-[#1b1233]/60">{h.phoneDay}</p>
        <div className="mt-2 grid grid-cols-3 gap-2">
          {SLOTS.map((s, i) => <button key={s} type="button" aria-pressed={pick === i} onClick={() => setPick(i)} className={`min-h-11 rounded-full border text-sm font-bold tnum transition ${pick === i ? "border-[#e0457b] bg-[#e0457b] text-white" : "border-[#1b1233]/15 text-[#1b1233]"}`}>{dig(s)}</button>)}
        </div>
        <p className="mt-3 flex items-center gap-1.5 text-[11px] text-[#1b1233]/55"><Icon name="check" className="h-3.5 w-3.5 text-[#e0457b]" />{h.phoneNote}</p>
        <button type="button" className="mt-4 w-full rounded-full bg-[#1b1233] py-3 text-sm font-bold text-white">{h.phoneConfirm}</button>
      </div>
    </motion.div>
  );
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
        {d.items.map((x) => (
          <li key={x.time} className={`flex items-center gap-3 rounded-xl border-s-4 p-3 ${x.status === d.items[1].status ? "border-amber-400 bg-amber-50" : "border-emerald-400 bg-emerald-50"}`}>
            <span className="w-14 shrink-0 text-sm font-bold tnum text-[#1b1233]" dir="ltr">{x.time}</span>
            <span className="min-w-0 flex-1"><span className="block truncate text-sm font-bold text-[#1b1233]">{x.name}</span><span className="block truncate text-xs text-[#1b1233]/60">{x.who}</span></span>
            <span className="shrink-0 rounded-full bg-white px-2.5 py-1 text-[11px] font-bold text-[#1b1233]/70">{x.status}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
