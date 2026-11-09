"use client";

import Link from "next/link";
import { motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { Icon } from "@/components/icons";
import { POINT_PRICES } from "@/lib/pricing";
import type { ShopCopy } from "./copy";

const E = [0.2, 0.8, 0.2, 1] as const;

/**
 * Shop pricing: the shop itself is free (no subscription), and usage is paid with points (1 point = 1 EGP).
 * Rates come from lib/pricing.ts, which mirrors the backend (V80__points_pricing.sql, app.ai.conversation-price-minor, app.whatsapp.*-price-minor).
 */
export function ShopPricing({ p, locale, register }: { p: ShopCopy["pricing"]; locale: string; register: string }) {
  const reduce = useReducedMotion();
  const [ai, setAi] = useState(200);
  const [ads, setAds] = useState(300);
  const nf = (n: number, d = 0) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { minimumFractionDigits: d, maximumFractionDigits: d });
  const egp = (n: number) => (locale === "ar" ? `${nf(n, n % 1 ? 1 : 0)} جنيه` : `EGP ${nf(n, n % 1 ? 1 : 0)}`);
  const aiCost = ai * POINT_PRICES.aiConversation;
  const adsCost = Math.round(ads * POINT_PRICES.message * 10) / 10;
  const total = aiCost + adsCost;

  return (
    <div className="mt-12 space-y-6">
      <p className="text-center font-display text-xl font-bold text-[#0d1117] sm:text-2xl">{p.headline}</p>
      <div className="grid gap-5 lg:grid-cols-[0.9fr_1.1fr]">
        {/* free shop */}
        <motion.div className="relative overflow-hidden rounded-[1.75rem] bg-[#0b0d12] p-7 text-white shadow-[0_50px_100px_-50px_rgb(13_17_23/.7)]"
          initial={reduce ? false : { opacity: 0, y: 24 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, margin: "-10% 0px" }} transition={{ duration: 0.6, ease: E }}>
          <div className="absolute inset-0 bg-[radial-gradient(60%_50%_at_80%_0%,rgb(124_92_255/.35),transparent_70%),radial-gradient(50%_40%_at_0%_100%,rgb(37_211_102/.18),transparent_70%)]" aria-hidden />
          <div className="relative">
            <p className="text-sm font-semibold text-white/60">{p.freeTitle}</p>
            <p className="mt-2 flex items-baseline gap-3"><span className="lp-gradient-text font-display text-6xl font-extrabold">{p.freePrice}</span><span className="text-sm text-white/50">{p.freeNote}</span></p>
            <ul className="mt-7 space-y-3">
              {p.freeItems.map((x) => <li key={x} className="flex items-start gap-3 text-[15px] text-white/85"><span className="mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full bg-white/10"><Icon name="check" className="h-3 w-3 text-[#a3e635]" /></span>{x}</li>)}
            </ul>
            <div className="mt-7 flex items-start gap-3 rounded-2xl border border-[#a3e635]/30 bg-[#a3e635]/10 p-4">
              <Icon name="award" className="mt-0.5 h-5 w-5 shrink-0 text-[#a3e635]" />
              <div><p className="font-bold">{p.giftTitle}</p><p className="mt-0.5 text-sm text-white/65">{p.giftBody}</p></div>
            </div>
            <Link href={register} className="lp-cta mt-7 w-full justify-center">{p.cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>
          </div>
        </motion.div>

        {/* usage rates */}
        <motion.div className="rounded-[1.75rem] border border-black/[.07] bg-white p-7"
          initial={reduce ? false : { opacity: 0, y: 24 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, margin: "-10% 0px" }} transition={{ duration: 0.6, ease: E, delay: 0.1 }}>
          <p className="font-display text-lg font-bold text-[#0d1117]">{p.payTitle}</p>
          <div className="mt-5 space-y-3">
            {p.rates.map((r, i) => (
              <div key={r.title} className="flex items-center gap-4 rounded-2xl bg-[#f6f6f3] p-4">
                <div className={`grid h-16 w-16 shrink-0 place-items-center rounded-2xl text-center ${i === 0 ? "bg-[linear-gradient(135deg,#7c5cff,#22d3ee)] text-white" : "bg-[#25d366]/15 text-[#0f7a3d]"}`}>
                  <div><p className="font-display text-xl font-extrabold leading-none tnum">{r.value}</p><p className="mt-1 text-[10px] font-semibold opacity-80">{r.unit}</p></div>
                </div>
                <div className="min-w-0"><p className="font-bold text-[#0d1117]">{r.title}</p><p className="mt-0.5 text-sm leading-relaxed text-[#4b5563]">{r.body}</p></div>
              </div>
            ))}
          </div>
          <p className="mt-5 text-sm font-bold text-[#0d1117]">{p.howTitle}</p>
          <ul className="mt-2 space-y-1.5">
            {p.how.map((x) => <li key={x} className="flex items-start gap-2 text-sm text-[#4b5563]"><Icon name="check" className="mt-0.5 h-4 w-4 shrink-0 text-[#7c5cff]" />{x}</li>)}
          </ul>
        </motion.div>
      </div>

      {/* usage calculator */}
      <motion.div className="rounded-[1.75rem] border border-black/[.07] bg-white p-6 sm:p-8"
        initial={reduce ? false : { opacity: 0, y: 24 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, margin: "-10% 0px" }} transition={{ duration: 0.6, ease: E }}>
        <div className="grid gap-8 lg:grid-cols-[1.2fr_0.8fr] lg:items-center">
          <div>
            <p className="font-display text-xl font-bold text-[#0d1117]">{p.calcTitle}</p>
            <p className="mt-1 text-sm text-[#6b7280]">{p.calcSub}</p>
            <Slider label={p.calcAi} value={ai} max={1000} step={10} onChange={setAi} shown={nf(ai)} cost={egp(aiCost)} accent="#7c5cff" />
            <Slider label={p.calcAds} value={ads} max={2000} step={20} onChange={setAds} shown={nf(ads)} cost={egp(adsCost)} accent="#25d366" />
          </div>
          <div className="rounded-2xl bg-[#0b0d12] p-6 text-white">
            <Row label={p.calcSub1} value={p.calcFree} strong="#a3e635" />
            <Row label={`${nf(ai)} × ${egp(POINT_PRICES.aiConversation)}`} value={egp(aiCost)} />
            <Row label={`${nf(ads)} × ${egp(POINT_PRICES.message)}`} value={egp(adsCost)} />
            <div className="mt-4 flex items-end justify-between border-t border-white/10 pt-4">
              <span className="text-sm text-white/60">{p.calcTotal}</span>
              <motion.span key={total} className="font-display text-3xl font-extrabold tnum" initial={reduce ? false : { opacity: 0.4, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.25 }}>{egp(total)}</motion.span>
            </div>
            <p className="mt-3 text-xs leading-relaxed text-white/45">{p.calcNote}</p>
          </div>
        </div>
      </motion.div>
    </div>
  );
}

function Slider({ label, value, max, step, onChange, shown, cost, accent }: { label: string; value: number; max: number; step: number; onChange: (n: number) => void; shown: string; cost: string; accent: string }) {
  const pct = (value / max) * 100;
  return (
    <label className="mt-6 block">
      <span className="flex items-center justify-between gap-3 text-sm"><span className="font-semibold text-[#111827]">{label}</span><span className="font-display font-bold text-[#0d1117] tnum">{shown} <span className="font-sans text-xs font-medium text-[#6b7280]">· {cost}</span></span></span>
      <input type="range" dir="ltr" min={0} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} className="lp-range mt-3 w-full"
        style={{ background: `linear-gradient(90deg, ${accent} ${pct}%, #e5e7eb ${pct}%)`, ["--lp-accent" as string]: accent }} />
    </label>
  );
}

function Row({ label, value, strong }: { label: string; value: string; strong?: string }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5 text-sm">
      <span className="text-white/60 tnum">{label}</span>
      <span className="font-semibold tnum" style={strong ? { color: strong } : undefined}>{value}</span>
    </div>
  );
}
