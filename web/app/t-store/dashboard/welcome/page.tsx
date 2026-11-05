"use client";

import Link from "next/link";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon, type IconName } from "@/components/icons";
import { LogoMark } from "@/components/brand";
import { ErrorText } from "@/components/ui";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";

type Built = { shortDescription: string; suggestedTemplate: string; products: { id: string; name: string; priceMinor: number }[]; needsPrice?: string[] };
type Settings = { enabled: boolean; tone: "FRIENDLY" | "FORMAL" | "SHORT"; maxDiscountPct: number; greeting: string | null };
type Channel = { platform: string; name: string; status: string };
type Msg = { role: "user" | "assistant"; content: string };

const ACCENT = "#7c5cff";
const STEPS = ["wel.s1", "wel.s2", "wel.s3", "wel.s4"] as const;

/**
 * First run for a new shop (registration lands here): teach the AI the catalog (AI builder / Facebook-Instagram import / manual),
 * set its personality (store/ai/settings), try it (store/ai/test, nothing is ordered), connect chats (existing connection screens).
 * Every step talks to the real endpoints and can be skipped; nothing here is required to use the dashboard.
 */
export default function Welcome() {
  const { t, siteName } = useI18n();
  const reduce = useReducedMotion();
  const [step, setStep] = useState(0);
  const [dir, setDir] = useState(1);
  const go = (s: number) => { setDir(s > step ? 1 : -1); setStep(s); window.scrollTo({ top: 0, behavior: reduce ? "auto" : "smooth" }); };
  const done = step >= STEPS.length;
  return (
    <div className="min-h-dvh bg-[#f6f6f3] text-[#0d1117]">
      <header className="sticky top-0 z-20 border-b border-black/[.06] bg-[#f6f6f3]/85 backdrop-blur">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <div className="flex min-w-0 items-center gap-2.5"><LogoMark className="h-8 w-8" /><span className="truncate font-display font-bold">{siteName}</span></div>
          {!done && (
            <ol className="hidden items-center gap-1 md:flex" aria-label="Progress">
              {STEPS.map((s, i) => (
                <li key={s} className="flex items-center gap-1">
                  <button type="button" onClick={() => go(i)} className={`flex items-center gap-2 rounded-full px-3 py-1.5 text-xs font-semibold transition ${i === step ? "bg-[#0d1117] text-white" : i < step ? "text-[#0d1117]" : "text-black/40"}`}>
                    <span className={`grid h-5 w-5 place-items-center rounded-full text-[10px] ${i === step ? "bg-white/20" : i < step ? "bg-[#a3e635] text-[#0d1117]" : "bg-black/[.06]"}`}>{i < step ? <Icon name="check" className="h-3 w-3" /> : i + 1}</span>{t(s)}
                  </button>
                  {i < STEPS.length - 1 && <span className="h-px w-4 bg-black/10" />}
                </li>
              ))}
            </ol>
          )}
          {!done && <Link href="/dashboard" className="shrink-0 rounded-full px-3 py-1.5 text-sm font-medium text-black/50 transition hover:bg-black/5 hover:text-black">{t("wel.skip")}</Link>}
        </div>
        {!done && <div className="h-0.5 bg-black/[.04] md:hidden"><motion.div className="h-full origin-left bg-[#7c5cff] rtl:origin-right" animate={{ scaleX: (step + 1) / STEPS.length }} /></div>}
      </header>

      <main className="mx-auto max-w-3xl px-4 pb-24 pt-10 sm:px-6 sm:pt-14">
        {step === 0 && !done && (
          <motion.div initial={reduce ? false : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="mb-10">
            <p className="mb-3 inline-flex items-center gap-2 rounded-full bg-[#7c5cff]/10 px-3 py-1 text-xs font-bold text-[#6d4aff]"><Icon name="sparkles" className="h-3.5 w-3.5" />{t("wel.title")}</p>
            <p className="text-black/55">{t("wel.sub")}</p>
          </motion.div>
        )}
        <AnimatePresence mode="wait" custom={dir} initial={false}>
          <motion.section key={step} custom={dir}
            initial={reduce ? { opacity: 0 } : { opacity: 0, y: 18 }} animate={{ opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.2, 0.8, 0.2, 1] } }} exit={{ opacity: 0, y: -10, transition: { duration: 0.15 } }}>
            {step === 0 && <ProductsStep onNext={() => go(1)} />}
            {step === 1 && <PersonalityStep onNext={() => go(2)} onBack={() => go(0)} />}
            {step === 2 && <TryStep onNext={() => go(3)} onBack={() => go(1)} />}
            {step === 3 && <ChannelsStep onNext={() => go(4)} onBack={() => go(2)} />}
            {done && <Done />}
          </motion.section>
        </AnimatePresence>
      </main>
    </div>
  );
}

function StepHead({ title, sub }: { title: string; sub: string }) {
  return <div className="mb-8"><h1 className="font-display text-3xl font-bold tracking-tight sm:text-4xl">{title}</h1><p className="mt-3 max-w-xl text-black/55">{sub}</p></div>;
}

function Nav({ onBack, onNext, nextLabel, loading, disabled }: { onBack?: () => void; onNext: () => void; nextLabel?: string; loading?: boolean; disabled?: boolean }) {
  const { t } = useI18n();
  return (
    <div className="mt-10 flex items-center justify-between gap-3">
      {onBack ? <button type="button" onClick={onBack} className="rounded-2xl px-4 py-3 text-sm font-semibold text-black/55 transition hover:bg-black/5">{t("wel.back")}</button> : <span />}
      <button type="button" onClick={onNext} disabled={loading || disabled} className="inline-flex items-center gap-2 rounded-2xl bg-[#0d1117] px-6 py-3.5 font-bold text-white transition hover:bg-black active:scale-[.98] disabled:opacity-50">
        {loading && <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" />}{nextLabel ?? t("wel.next")}<Icon name="arrow" className="h-4 w-4 flip-rtl" />
      </button>
    </div>
  );
}

function Option({ icon, title, body, on, onClick }: { icon: IconName; title: string; body: string; on: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} aria-pressed={on}
      className={`relative rounded-3xl border bg-white p-5 text-start transition duration-300 hover:-translate-y-0.5 ${on ? "border-[#7c5cff] shadow-[0_20px_50px_-25px_rgba(124,92,255,.7)] ring-4 ring-[#7c5cff]/10" : "border-black/[.07] hover:border-black/20"}`}>
      <span className={`grid h-11 w-11 place-items-center rounded-2xl transition-colors ${on ? "bg-[#7c5cff] text-white" : "bg-black/[.04] text-black/70"}`}><Icon name={icon} className="h-5 w-5" /></span>
      <p className="mt-4 font-display font-bold">{title}</p>
      <p className="mt-1 text-sm leading-relaxed text-black/55">{body}</p>
      {on && <motion.span layoutId="wel-opt" className="absolute end-4 top-4 grid h-6 w-6 place-items-center rounded-full bg-[#7c5cff] text-white"><Icon name="check" className="h-3.5 w-3.5" /></motion.span>}
    </button>
  );
}

// ---------- 1. products ----------
function ProductsStep({ onNext }: { onNext: () => void }) {
  const { t, locale, currency } = useI18n();
  const { can } = useMe();
  const allowed = can("experiment.manage");
  const [mode, setMode] = useState<"describe" | "social" | "manual">("describe");
  const [d, setD] = useState("");
  const [res, setRes] = useState<Built | null>(null);
  const sources = useApi<{ platform: "FACEBOOK" | "INSTAGRAM"; name: string }[]>(allowed && mode === "social" ? "store/builder/social" : null);
  const build = useAction(async () => { setRes(await api<Built>("store/builder", { body: { description: d.trim(), language: locale } })); });
  const fromSocial = useAction(async (platform: string) => { setRes(await api<Built>("store/builder/social", { body: { platform, language: locale } })); });
  const busy = build.loading || fromSocial.loading;
  return (
    <>
      <StepHead title={t("wel.p1Title")} sub={t("wel.p1Sub")} />
      <div className="grid gap-3 sm:grid-cols-3">
        <Option icon="sparkles" title={t("wel.optDescribe")} body={t("wel.optDescribeBody")} on={mode === "describe"} onClick={() => setMode("describe")} />
        <Option icon="camera" title={t("wel.optSocial")} body={t("wel.optSocialBody")} on={mode === "social"} onClick={() => setMode("social")} />
        <Option icon="plus" title={t("wel.optManual")} body={t("wel.optManualBody")} on={mode === "manual"} onClick={() => setMode("manual")} />
      </div>

      <div className="mt-6 rounded-3xl border border-black/[.07] bg-white p-5 sm:p-6">
        {!allowed && mode !== "manual" ? <p className="text-sm text-black/55">{t("wel.noAccess")}</p>
          : res ? <BuiltResult res={res} currency={currency} locale={locale} />
          : busy ? <Building />
          : mode === "describe" ? (
            <>
              <label className="text-sm font-semibold" htmlFor="wel-desc">{t("wel.describeLabel")}</label>
              <textarea id="wel-desc" rows={5} maxLength={1500} value={d} onChange={(e) => setD(e.target.value)} placeholder={t("wel.describePh")}
                className="mt-2 w-full resize-none rounded-2xl border border-black/10 bg-[#fbfbf9] p-4 text-[15px] leading-relaxed outline-none transition focus:border-[#7c5cff] focus:ring-4 focus:ring-[#7c5cff]/10" />
              <ErrorText error={build.error} />
              <button type="button" onClick={() => void build.run()} disabled={d.trim().length < 15} className="mt-3 inline-flex items-center gap-2 rounded-2xl bg-[linear-gradient(135deg,#7c5cff,#22b8d4)] px-5 py-3 font-bold text-white shadow-[0_12px_30px_-12px_rgba(124,92,255,.9)] transition active:scale-[.98] disabled:opacity-40">
                <Icon name="sparkles" className="h-4 w-4" />{t("wel.build")}
              </button>
            </>
          ) : mode === "social" ? (
            sources.loading ? <div className="skeleton h-16 rounded-2xl" />
              : (sources.data ?? []).length === 0 ? <p className="flex items-start gap-2 text-sm leading-relaxed text-black/60"><Icon name="link" className="mt-0.5 h-4 w-4 shrink-0" />{t("wel.socialNone")}</p>
              : <div className="flex flex-wrap gap-2">{sources.data!.map((s) => <button key={s.platform + s.name} type="button" onClick={() => void fromSocial.run(s.platform)} className="inline-flex items-center gap-2 rounded-2xl border border-black/10 px-4 py-3 font-semibold transition hover:border-[#7c5cff]"><Icon name={s.platform === "INSTAGRAM" ? "camera" : "chat"} className="h-4 w-4" />{t("wel.socialFrom", { name: s.name })}</button>)}<ErrorText error={fromSocial.error} /></div>
          ) : (
            <Link href="/dashboard/products/new" className="inline-flex items-center gap-2 rounded-2xl border border-black/10 px-5 py-3 font-semibold transition hover:border-black/30"><Icon name="plus" className="h-4 w-4" />{t("wel.optManual")}<Icon name="external" className="h-3.5 w-3.5 opacity-50" /></Link>
          )}
      </div>
      <Nav onNext={onNext} />
    </>
  );
}

function Building() {
  const { t } = useI18n();
  return (
    <div className="flex flex-col items-center py-8 text-center">
      <div className="relative h-16 w-16"><span className="absolute inset-0 animate-spin rounded-full bg-[conic-gradient(#7c5cff,#22d3ee,#a3e635,#7c5cff)] [animation-duration:2s]" /><span className="absolute inset-1.5 grid place-items-center rounded-full bg-white"><Icon name="sparkles" className="h-6 w-6 text-[#7c5cff]" /></span></div>
      <p className="mt-4 font-semibold">{t("wel.building")}</p>
      <div className="mt-5 grid w-full max-w-sm grid-cols-3 gap-2">{[0, 1, 2].map((i) => <div key={i} className="skeleton aspect-[4/5] rounded-xl" style={{ animationDelay: `${i * 0.15}s` }} />)}</div>
    </div>
  );
}

function BuiltResult({ res, currency, locale }: { res: Built; currency: string; locale: Locale }) {
  const { t } = useI18n();
  return (
    <div>
      <p className="flex items-center gap-2 font-display text-lg font-bold"><span className="grid h-7 w-7 place-items-center rounded-full bg-[#a3e635]"><Icon name="check" className="h-4 w-4" /></span>{t("wel.built", { n: res.products.length })}</p>
      <p className="mt-1 text-sm text-black/55">{t("wel.builtHint")}</p>
      <ul className="mt-4 grid gap-2 sm:grid-cols-2">
        {res.products.slice(0, 8).map((p, i) => (
          <motion.li key={p.id} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0, transition: { delay: i * 0.05 } }} className="flex items-center justify-between gap-3 rounded-xl bg-[#f6f6f3] px-3 py-2.5 text-sm">
            <span className="truncate font-medium">{p.name}</span><span className="shrink-0 font-semibold tnum">{p.priceMinor ? money(p.priceMinor, currency, locale) : "—"}</span>
          </motion.li>
        ))}
      </ul>
      {(res.needsPrice?.length ?? 0) > 0 && <p className="mt-3 text-sm text-amber-700">{t("wel.needsPrice", { list: res.needsPrice!.slice(0, 5).join("، ") })}</p>}
      <div className="mt-4 flex flex-wrap items-center gap-3 text-sm">
        <Link href="/dashboard/products" className="font-semibold text-[#6d4aff] hover:underline">{t("wel.review")}</Link>
        {res.suggestedTemplate && <Link href="/dashboard/settings?tab=look" className="text-black/55 hover:text-black">{t("wel.suggested", { tpl: t(`tpl.${res.suggestedTemplate}`) })}</Link>}
      </div>
    </div>
  );
}

// ---------- 2. personality ----------
function PersonalityStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const { t, siteName } = useI18n();
  const { can } = useMe();
  const allowed = can("ai.manage");
  const cur = useApi<Settings>(can("ai.read") || allowed ? "store/ai/settings" : null);
  const [s, setS] = useState<Settings | null>(null);
  const v = s ?? cur.data ?? { enabled: true, tone: "FRIENDLY", maxDiscountPct: 0, greeting: null };
  const set = (p: Partial<Settings>) => setS({ ...v, ...p });
  const save = useAction(async () => { if (s && allowed) await api("store/ai/settings", { method: "PUT", body: { enabled: v.enabled, tone: v.tone, maxDiscountPct: v.maxDiscountPct, greeting: v.greeting ?? "" } }); onNext(); });
  const tones: Settings["tone"][] = ["FRIENDLY", "FORMAL", "SHORT"];
  const sample: Record<Settings["tone"], IconName> = { FRIENDLY: "heart", FORMAL: "briefcase", SHORT: "bolt" };
  return (
    <>
      <StepHead title={t("wel.p2Title")} sub={t("wel.p2Sub")} />
      {!allowed && <p className="mb-4 rounded-2xl bg-amber-50 p-4 text-sm text-amber-800">{t("wel.noAccess")}</p>}
      <fieldset disabled={!allowed} className="space-y-6">
        <div className="grid gap-3 sm:grid-cols-3">
          {tones.map((tone) => (
            <button key={tone} type="button" onClick={() => set({ tone })} aria-pressed={v.tone === tone}
              className={`rounded-3xl border bg-white p-5 text-start transition ${v.tone === tone ? "border-[#7c5cff] ring-4 ring-[#7c5cff]/10" : "border-black/[.07] hover:border-black/20"}`}>
              <span className={`grid h-10 w-10 place-items-center rounded-xl ${v.tone === tone ? "bg-[#7c5cff] text-white" : "bg-black/[.04]"}`}><Icon name={sample[tone]} className="h-5 w-5" /></span>
              <p className="mt-3 font-display font-bold">{t(`wel.tone${tone}`)}</p>
              <p className="mt-1 text-sm text-black/55">{t(`wel.tone${tone}Body`)}</p>
            </button>
          ))}
        </div>
        <div className="rounded-3xl border border-black/[.07] bg-white p-5 sm:p-6">
          <div className="flex items-center justify-between gap-4">
            <label htmlFor="wel-disc" className="font-semibold">{t("wel.discount")}</label>
            <motion.span key={v.maxDiscountPct} initial={{ scale: 0.85 }} animate={{ scale: 1 }} className="rounded-full bg-[#0d1117] px-3 py-1 font-display text-sm font-bold text-white tnum">{v.maxDiscountPct === 0 ? t("wel.noDiscount") : `${v.maxDiscountPct}%`}</motion.span>
          </div>
          <input id="wel-disc" dir="ltr" type="range" min={0} max={30} step={1} value={v.maxDiscountPct} onChange={(e) => set({ maxDiscountPct: Number(e.target.value) })} className="price-thumb mt-4 w-full accent-[#7c5cff]" />
          <div className="mt-1 flex justify-between text-xs text-black/40 tnum" dir="ltr"><span>0%</span><span>10%</span><span>20%</span><span>30%</span></div>
        </div>
        <div className="rounded-3xl border border-black/[.07] bg-white p-5 sm:p-6">
          <label htmlFor="wel-greet" className="font-semibold">{t("wel.greeting")}</label>
          <textarea id="wel-greet" rows={2} maxLength={300} value={v.greeting ?? ""} onChange={(e) => set({ greeting: e.target.value })} placeholder={t("wel.greetingPh").replace(/Nour Boutique|بوتيك نور/, siteName || "$&")}
            className="mt-2 w-full resize-none rounded-2xl border border-black/10 bg-[#fbfbf9] p-3.5 outline-none transition focus:border-[#7c5cff] focus:ring-4 focus:ring-[#7c5cff]/10" />
          <label className="mt-4 flex cursor-pointer items-center justify-between gap-3">
            <span className="font-semibold">{t("wel.enabled")}</span>
            <span className={`relative h-7 w-12 rounded-full transition-colors ${v.enabled ? "bg-[#7c5cff]" : "bg-black/15"}`}>
              <input type="checkbox" checked={v.enabled} onChange={(e) => set({ enabled: e.target.checked })} className="peer sr-only" />
              <motion.span className="absolute top-1 h-5 w-5 rounded-full bg-white shadow" animate={{ insetInlineStart: v.enabled ? 24 : 4 }} transition={{ type: "spring", stiffness: 500, damping: 32 }} />
            </span>
          </label>
        </div>
      </fieldset>
      <ErrorText error={save.error} />
      <Nav onBack={onBack} onNext={() => void save.run()} loading={save.loading} nextLabel={allowed && s ? t("wel.save") : t("wel.next")} />
    </>
  );
}

// ---------- 3. try it ----------
function TryStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const { t } = useI18n();
  const { can } = useMe();
  const allowed = can("ai.manage");
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [text, setText] = useState("");
  const box = useRef<HTMLDivElement>(null);
  const send = useAction(async (m: string) => {
    setMsgs((x) => [...x, { role: "user", content: m }]);
    setText("");
    const r = await api<{ reply: string }>("store/ai/test", { body: { message: m } });
    setMsgs((x) => [...x, { role: "assistant", content: r.reply }]);
  });
  useEffect(() => { box.current?.scrollTo({ top: box.current.scrollHeight, behavior: "smooth" }); }, [msgs, send.loading]);
  const quick = [t("wel.try1"), t("wel.try2"), t("wel.try3")];
  return (
    <>
      <StepHead title={t("wel.p3Title")} sub={t("wel.p3Sub")} />
      {!allowed ? <p className="rounded-2xl bg-amber-50 p-4 text-sm text-amber-800">{t("wel.noAccess")}</p> : (
        <div className="overflow-hidden rounded-3xl border border-black/[.07] bg-white">
          <div ref={box} className="h-[360px] space-y-2 overflow-y-auto bg-[#efeae2] p-4">
            {msgs.length === 0 && <p className="mx-auto mt-16 max-w-sm text-center text-sm leading-relaxed text-black/50">{t("wel.tryEmpty")}</p>}
            {msgs.map((m, i) => (
              <motion.div key={i} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}>
                <p className={`max-w-[80%] whitespace-pre-wrap rounded-2xl px-3.5 py-2.5 text-[14px] leading-relaxed shadow-sm ${m.role === "user" ? "rounded-se-md bg-[#d9fdd3]" : "rounded-ss-md bg-white"}`}>{m.content}</p>
              </motion.div>
            ))}
            {send.loading && <div className="flex"><span className="flex gap-1 rounded-2xl bg-white px-4 py-3 shadow-sm">{[0, 1, 2].map((k) => <span key={k} className="h-1.5 w-1.5 animate-bounce rounded-full bg-black/30" style={{ animationDelay: `${k * 0.12}s` }} />)}</span></div>}
          </div>
          <div className="flex flex-wrap gap-2 border-t border-black/[.06] px-4 pt-3">{quick.map((q) => <button key={q} type="button" disabled={send.loading} onClick={() => void send.run(q)} className="rounded-full border border-black/10 px-3 py-1.5 text-xs font-semibold transition hover:border-[#7c5cff] hover:text-[#6d4aff] disabled:opacity-50">{q}</button>)}</div>
          <form onSubmit={(e) => { e.preventDefault(); if (text.trim()) void send.run(text.trim()); }} className="flex gap-2 p-3">
            <input value={text} onChange={(e) => setText(e.target.value)} placeholder={t("wel.tryPh")} maxLength={1000} className="h-12 flex-1 rounded-2xl border border-black/10 bg-[#fbfbf9] px-4 outline-none focus:border-[#7c5cff]" />
            <button type="submit" disabled={!text.trim() || send.loading} className="grid h-12 w-12 place-items-center rounded-2xl bg-[#0d1117] text-white disabled:opacity-40" aria-label="Send"><Icon name="send" className="h-5 w-5 flip-rtl" /></button>
          </form>
          <div className="px-4 pb-3"><ErrorText error={send.error} /></div>
        </div>
      )}
      <Nav onBack={onBack} onNext={onNext} />
    </>
  );
}

// ---------- 4. channels ----------
function ChannelsStep({ onNext, onBack }: { onNext: () => void; onBack: () => void }) {
  const { t } = useI18n();
  const { can } = useMe();
  const list = useApi<Channel[]>(can("ai.read") || can("ai.manage") ? "store/ai/channels" : null);
  const active = (p: string[]) => (list.data ?? []).some((c) => p.includes(c.platform) && c.status === "ACTIVE");
  const rows: { icon: IconName; color: string; title: string; body: string; on: boolean; href?: string }[] = [
    { icon: "phone", color: "#25d366", title: t("wel.chWa"), body: t("wel.chWaBody"), on: active(["WHATSAPP"]), href: "/dashboard/ai?tab=whatsapp" },
    { icon: "camera", color: "#d300c5", title: t("wel.chMeta"), body: t("wel.chMetaBody"), on: active(["INSTAGRAM", "FACEBOOK"]), href: "/dashboard/ai?tab=channels" },
    { icon: "globe", color: ACCENT, title: t("wel.chWeb"), body: t("wel.chWebBody"), on: true },
  ];
  return (
    <>
      <StepHead title={t("wel.p4Title")} sub={t("wel.p4Sub")} />
      <ul className="space-y-3">
        {rows.map((r, i) => (
          <motion.li key={r.title} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0, transition: { delay: i * 0.08 } }} className="flex items-center gap-4 rounded-3xl border border-black/[.07] bg-white p-5">
            <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl" style={{ background: `${r.color}18`, color: r.color }}><Icon name={r.icon} className="h-5 w-5" /></span>
            <div className="min-w-0 flex-1"><p className="font-display font-bold">{r.title}</p><p className="mt-0.5 text-sm text-black/55">{r.body}</p></div>
            {r.on ? <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-[#a3e635]/25 px-3 py-1.5 text-xs font-bold text-[#3f6212]"><span className="h-1.5 w-1.5 rounded-full bg-[#65a30d]" />{r.href ? t("wel.connected") : t("wel.on")}</span>
              : <Link href={r.href!} className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-[#0d1117] px-4 py-2 text-sm font-semibold text-white">{t("wel.connect")}<Icon name="external" className="h-3.5 w-3.5" /></Link>}
          </motion.li>
        ))}
      </ul>
      <Nav onBack={onBack} onNext={onNext} nextLabel={t("wel.finish")} />
    </>
  );
}

// ---------- done ----------
function Done() {
  const { t } = useI18n();
  const reduce = useReducedMotion();
  const next: { href: string; icon: IconName; label: string; external?: boolean }[] = [
    { href: "/dashboard/settings?tab=look", icon: "palette", label: t("wel.nextLook") },
    { href: "/dashboard/settings", icon: "edit", label: t("wel.nextLogo") },
    { href: "/dashboard/products", icon: "box", label: t("wel.nextProducts") },
    { href: "/", icon: "external", label: t("wel.nextShare"), external: true },
  ];
  return (
    <div className="text-center">
      <div className="relative mx-auto h-32 w-32">
        {!reduce && Array.from({ length: 14 }).map((_, i) => {
          const a = (i / 14) * Math.PI * 2;
          return <motion.span key={i} className="absolute left-1/2 top-1/2 h-2 w-2 rounded-full" style={{ background: ["#7c5cff", "#22d3ee", "#a3e635"][i % 3] }}
            initial={{ x: 0, y: 0, opacity: 1, scale: 1 }} animate={{ x: Math.cos(a) * 90, y: Math.sin(a) * 90, opacity: 0, scale: 0.4 }} transition={{ duration: 1.1, ease: [0.2, 0.8, 0.2, 1], delay: 0.2 }} />;
        })}
        <motion.span initial={reduce ? false : { scale: 0.3, rotate: -30 }} animate={{ scale: 1, rotate: 0 }} transition={{ type: "spring", stiffness: 260, damping: 16 }}
          className="absolute inset-0 grid place-items-center rounded-full bg-[conic-gradient(#7c5cff,#22d3ee,#a3e635,#7c5cff)] shadow-[0_20px_60px_-15px_rgba(124,92,255,.8)]">
          <span className="grid h-[86%] w-[86%] place-items-center rounded-full bg-[#0d1117]"><Icon name="sparkles" className="h-11 w-11 text-white" /></span>
        </motion.span>
      </div>
      <h1 className="mt-8 font-display text-3xl font-bold tracking-tight sm:text-4xl">{t("wel.doneTitle")}</h1>
      <p className="mt-3 text-black/55">{t("wel.doneSub")}</p>
      <div className="mx-auto mt-10 grid max-w-xl gap-3 text-start sm:grid-cols-2">
        {next.map((n, i) => (
          <motion.div key={n.href + n.label} initial={reduce ? false : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0, transition: { delay: 0.4 + i * 0.07 } }}>
            {n.external
              ? <a href={n.href} target="_blank" rel="noopener" className="flex items-center gap-3 rounded-2xl border border-black/[.07] bg-white p-4 font-semibold transition hover:border-black/20"><Icon name={n.icon} className="h-5 w-5 text-[#7c5cff]" />{n.label}</a>
              : <Link href={n.href} className="flex items-center gap-3 rounded-2xl border border-black/[.07] bg-white p-4 font-semibold transition hover:border-black/20"><Icon name={n.icon} className="h-5 w-5 text-[#7c5cff]" />{n.label}</Link>}
          </motion.div>
        ))}
      </div>
      <Link href="/dashboard" className="mt-10 inline-flex items-center gap-2 rounded-2xl bg-[#0d1117] px-7 py-4 font-bold text-white transition hover:bg-black active:scale-[.98]">{t("wel.toDashboard")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>
    </div>
  );
}
