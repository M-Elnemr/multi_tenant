"use client";

import Link from "next/link";
import { motion, useReducedMotion } from "motion/react";
import { Icon } from "@/components/icons";
import type { ClinicCopy } from "./clinic/copy";

/** The copy both hosting verticals share (clinic and lawyer): first month free, then a monthly hosting fee. */
export type HostingCopy = ClinicCopy["pricing"];

const E = [0.2, 0.8, 0.2, 1] as const;

/**
 * Clinic pricing: a calm two-step timeline on paper cards (sage / teal, serif numbers).
 * The amount mirrors the backend plans (V80__points_pricing.sql) and lib/pricing.ts.
 */
export function ClinicHostingPricing({ p, register }: { p: HostingCopy; register: string }) {
  const reduce = useReducedMotion();
  const rise = (d: number) => ({ initial: reduce ? false : { opacity: 0, y: 22 }, whileInView: { opacity: 1, y: 0 }, viewport: { once: true, margin: "-10% 0px" }, transition: { duration: 0.7, ease: E, delay: d } });
  return (
    <div className="mt-12">
      <div className="relative grid gap-4 md:grid-cols-2">
        <motion.div {...rise(0)} className="lp-paper-card relative overflow-hidden p-7">
          <span className="absolute -end-10 -top-10 h-40 w-40 rounded-full bg-[radial-gradient(circle,rgb(45_140_120/.18),transparent_70%)]" aria-hidden />
          <p className="text-sm font-semibold text-[#2d7d6f]">{p.month1}</p>
          <p className="lp-serif mt-2 text-6xl font-semibold text-[#10302d]">{p.month1Price}</p>
          <p className="mt-2 text-sm text-[#10302d]/60">{p.month1Note}</p>
        </motion.div>
        <motion.div {...rise(0.12)} className="relative overflow-hidden rounded-[1.6rem] bg-[#0f3d3a] p-7 text-[#f3efe6] shadow-[0_40px_80px_-45px_rgb(16_48_45/.7)]">
          <span className="absolute -bottom-12 -start-12 h-48 w-48 rounded-full bg-[radial-gradient(circle,rgb(196_224_207/.16),transparent_70%)]" aria-hidden />
          <p className="text-sm font-semibold text-[#c4e0cf]">{p.after}</p>
          <p className="mt-2 flex items-baseline gap-2"><span className="lp-serif text-6xl font-semibold">{p.afterPrice}</span><span className="text-sm text-[#f3efe6]/70">{p.afterUnit}</span></p>
          <p className="mt-2 text-sm text-[#f3efe6]/65">{p.afterNote}</p>
          <p className="mt-4 inline-flex rounded-full bg-white/10 px-3 py-1 text-xs font-semibold text-[#c4e0cf]">{p.yearly}</p>
        </motion.div>
        {/* the arrow between the two months */}
        <span className="absolute start-1/2 top-1/2 z-[1] hidden h-11 w-11 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border border-[#10302d]/10 bg-[#fbf9f4] text-[#2d7d6f] shadow md:grid rtl:translate-x-1/2" aria-hidden>
          <Icon name="arrow" className="h-4 w-4 flip-rtl" />
        </span>
      </div>
      <motion.div {...rise(0.2)} className="lp-paper-card mt-4 p-7">
        <p className="lp-serif text-2xl font-semibold text-[#10302d]">{p.includedTitle}</p>
        <ul className="mt-5 grid gap-3 sm:grid-cols-2">
          {p.included.map((x) => <li key={x} className="flex items-start gap-3 text-[15px] text-[#10302d]/80"><span className="mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full bg-[#2d7d6f]/12"><Icon name="check" className="h-3 w-3 text-[#2d7d6f]" /></span>{x}</li>)}
        </ul>
        <div className="mt-7 flex flex-wrap items-center justify-between gap-4 border-t border-[#10302d]/8 pt-6">
          <p className="text-sm text-[#10302d]/60">{p.calm}</p>
          <Link href={register} className="inline-flex items-center gap-2 rounded-full bg-[#0f3d3a] px-6 py-3 text-sm font-semibold text-[#f3efe6] transition hover:-translate-y-px hover:bg-[#145049]">{p.cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>
        </div>
      </motion.div>
    </div>
  );
}

/**
 * Lawyer fees: an ivory "fee schedule" with brass rules and a classical serif, like a printed office tariff.
 */
export function LawyerHostingPricing({ p, register }: { p: HostingCopy; register: string }) {
  const reduce = useReducedMotion();
  const rise = (d: number) => ({ initial: reduce ? false : { opacity: 0, y: 18 }, whileInView: { opacity: 1, y: 0 }, viewport: { once: true, margin: "-10% 0px" }, transition: { duration: 0.9, ease: E, delay: d } });
  return (
    <motion.div {...rise(0)} className="lp-ivory-card relative mt-12 overflow-hidden">
      <div className="h-1.5 bg-[linear-gradient(90deg,#8a6a3a,#d9b97f,#8a6a3a)]" aria-hidden />
      <div className="grid md:grid-cols-[1fr_1px_1fr]">
        <div className="p-8 text-center">
          <p className="text-xs font-bold uppercase tracking-[.25em] text-[#8a6a3a]">{p.month1}</p>
          <p className="lp-classic mt-6 text-6xl leading-tight text-[#14213d]">{p.month1Price}</p>
          <p className="mt-3 text-sm text-[#14213d]/60">{p.month1Note}</p>
        </div>
        <span className="mx-8 h-px bg-[#b08d57]/35 md:mx-0 md:my-8 md:h-auto md:w-px" aria-hidden />
        <div className="p-8 text-center">
          <p className="text-xs font-bold uppercase tracking-[.25em] text-[#8a6a3a]">{p.after}</p>
          <p className="mt-6 flex items-baseline justify-center gap-2"><span className="lp-classic text-6xl leading-tight text-[#14213d]">{p.afterPrice}</span><span className="text-sm text-[#14213d]/60">{p.afterUnit}</span></p>
          <p className="mt-3 text-sm text-[#14213d]/60">{p.afterNote}</p>
          <p className="mt-3 text-xs font-semibold text-[#8a6a3a]">{p.yearly}</p>
        </div>
      </div>
      <div className="border-t border-[#b08d57]/30 bg-[#14213d] p-8 text-[#f4ecdc]">
        <p className="lp-classic text-center text-2xl text-[#d9b97f]">{p.includedTitle}</p>
        <ul className="mx-auto mt-6 grid max-w-3xl gap-x-8 gap-y-3 sm:grid-cols-2">
          {p.included.map((x, i) => (
            <motion.li key={x} className="flex items-start gap-3 text-[15px] text-[#f4ecdc]/85"
              initial={reduce ? false : { opacity: 0 }} whileInView={{ opacity: 1 }} viewport={{ once: true }} transition={{ duration: 0.6, delay: 0.1 + i * 0.08 }}>
              <span className="mt-2 h-px w-4 shrink-0 bg-[#d9b97f]" aria-hidden />{x}
            </motion.li>
          ))}
        </ul>
        <div className="mt-8 flex flex-col items-center gap-3">
          <Link href={register} className="inline-flex items-center gap-2 rounded-md border border-[#d9b97f]/60 bg-[#d9b97f] px-7 py-3 text-sm font-bold text-[#14213d] transition hover:-translate-y-px hover:bg-[#e6c993]">{p.cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>
          <p className="text-xs text-[#f4ecdc]/55">{p.calm}</p>
        </div>
      </div>
    </motion.div>
  );
}
