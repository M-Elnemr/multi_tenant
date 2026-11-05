"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Icon } from "@/components/icons";

type Row = { id: string; name: string; specialties: string[]; photo: string | null; years: number; fee: string | null; qualifications: string };
type Labels = { all: string; book: string; years: string; fee: string; none: string; search: string };

/** Medical-centre doctor directory: department chips (from real specialties) and a name search; cards re-flow with a soft layout animation. */
export function DoctorDirectory({ rows, hrefBook, labels }: { rows: Row[]; hrefBook?: string; labels: Labels }) {
  const reduce = useReducedMotion();
  const depts = useMemo(() => [...new Set(rows.flatMap((r) => r.specialties))].sort(), [rows]);
  const [dept, setDept] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const shown = rows.filter((r) => (!dept || r.specialties.includes(dept)) && (!q.trim() || r.name.toLowerCase().includes(q.trim().toLowerCase())));
  return (
    <div>
      <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative sm:w-72">
          <Icon name="search" className="pointer-events-none absolute start-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--c-mute)]" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={labels.search} className="w-full rounded-full border border-[var(--c-line)] bg-[var(--c-card)] py-2.5 pe-4 ps-10 text-sm outline-none transition focus:border-[var(--brand)]" />
        </div>
        <div className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none]">
          {[null, ...depts].map((d) => {
            const on = dept === d;
            return (
              <button key={d ?? "all"} onClick={() => setDept(d)} className={`relative shrink-0 rounded-full px-4 py-2 text-sm font-bold transition ${on ? "text-white" : "bg-[var(--c-card)] text-[var(--c-ink)] ring-1 ring-inset ring-[var(--c-line)] hover:ring-[var(--brand)]"}`}>
                {on && <motion.span layoutId="dept-chip" transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 500, damping: 38 }} className="absolute inset-0 rounded-full" style={{ background: "var(--brand)" }} />}
                <span className="relative">{d ?? labels.all}</span>
              </button>
            );
          })}
        </div>
      </div>
      <motion.ul layout className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <AnimatePresence mode="popLayout">
          {shown.map((r) => (
            <motion.li key={r.id} layout={!reduce} initial={reduce ? false : { opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.97 }} transition={{ duration: 0.22 }} className="c-card c-lift flex gap-4 p-4">
              <span className="h-20 w-20 shrink-0 overflow-hidden rounded-[18px] bg-[var(--c-soft)]">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {r.photo ? <img src={r.photo} alt={r.name} className="h-full w-full object-cover" /> : <span className="c-avatar grid h-full w-full place-items-center text-3xl">{r.name.trim().charAt(0)}</span>}
              </span>
              <div className="min-w-0 flex-1">
                <h3 className="truncate font-extrabold">{r.name}</h3>
                <p className="truncate text-sm" style={{ color: "var(--brand)" }}>{r.specialties.join(" · ")}</p>
                <p className="c-mute mt-1 truncate text-xs">{[r.years ? `${r.years}+ ${labels.years}` : "", r.fee ? `${labels.fee}: ${r.fee}` : ""].filter(Boolean).join(" · ")}</p>
                {hrefBook && <Link href={`${hrefBook}?doctorId=${r.id}`} className="mt-2 inline-flex items-center gap-1 text-sm font-bold" style={{ color: "var(--brand)" }}>{labels.book}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>}
              </div>
            </motion.li>
          ))}
        </AnimatePresence>
      </motion.ul>
      {shown.length === 0 && <p className="c-mute py-10 text-center">{labels.none}</p>}
    </div>
  );
}
