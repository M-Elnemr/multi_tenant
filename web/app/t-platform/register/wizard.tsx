"use client";

/* eslint-disable @next/next/no-img-element */
import Link from "next/link";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { api, newKey } from "@/lib/client";
import { PhoneInput } from "@/components/inputs";
import { useAction, useApi } from "@/components/hooks";
import { CategoryPicker, categoriesValid, type CategoryOption } from "@/components/category-picker";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Field, Input } from "@/components/ui";
import { Icon, type IconName } from "@/components/icons";
import { LogoMark } from "@/components/brand";
import { trackStep } from "@/components/visit-tracker";

type Type = "STORE" | "CLINIC" | "LAWYER";

function slugify(s: string) {
  return s.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40);
}

/** A mobile number is required to register: Egyptian 01x xxxxxxxx, or an international +number. */
const validMobile = (p: string) => /^(\+\d{8,15}|01[0125]\d{8}|1[0125]\d{8})$/.test(p.trim());
const pwScore = (p: string) => (p.length < 8 ? 0 : (p.length >= 12 ? 1 : 0) + (/[A-Za-z]/.test(p) && /\d/.test(p) ? 1 : 0) + (/[^A-Za-z0-9]/.test(p) ? 1 : 0) >= 2 ? 2 : 1);

/** Each vertical has its own look in the journey (the shop one is the AI one). */
const THEME: Record<Type, { pane: string; accent: string; btn: string; ring: string; icon: IconName }> = {
  STORE: { pane: "bg-[#07090d] text-white", accent: "#7c5cff", btn: "bg-[#0d1117] text-white hover:bg-black", ring: "focus-within:ring-[#7c5cff]/30", icon: "bag" },
  CLINIC: { pane: "bg-[#0f3d3a] text-[#f3efe6]", accent: "#0f5e57", btn: "bg-[#0f5e57] text-white hover:bg-[#0c4c46]", ring: "focus-within:ring-[#0f5e57]/30", icon: "stethoscope" },
  LAWYER: { pane: "bg-[#14213d] text-[#f5efe0]", accent: "#b08d57", btn: "bg-[#14213d] text-white hover:bg-[#0e182d]", ring: "focus-within:ring-[#b08d57]/30", icon: "scale" },
};

/**
 * Sign-up as a short journey: (choose type) → name and what you sell → account and address → activation.
 * Creates the business with the same onboarding call as before; the site is live on its own subdomain when it finishes, then the owner is signed in on it:
 * a shop lands on /dashboard/welcome (AI seller setup), a clinic or office on its dashboard.
 */
export function RegisterWizard({ rootDomain, initialType }: { rootDomain: string; initialType: Type | null }) {
  const { t, locale } = useI18n();
  const reduce = useReducedMotion();
  const [type, setType] = useState<Type | null>(initialType);
  const [step, setStep] = useState(1);
  const [dirn, setDirn] = useState(1);
  const [name, setName] = useState("");
  const [manualSlug, setManualSlug] = useState<string | null>(null);
  const [editSlug, setEditSlug] = useState(false);
  // The address is made for the owner: from the name when it has latin letters, else a short random one (an Arabic name has none).
  const [rand] = useState(() => Math.random().toString(36).slice(2, 6));
  const [suffix, setSuffix] = useState("");
  const base = slugify(name);
  const auto = (base.length >= 3 ? base : `${type === "CLINIC" ? "clinic" : type === "LAWYER" ? "law" : "shop"}-${rand}`) + (suffix ? `-${suffix}` : "");
  const slug = manualSlug ?? auto;
  const [check, setCheck] = useState<{ slug: string; state: "ok" | "taken" | "invalid" }>({ slug: "", state: "ok" });
  const [owner, setOwner] = useState({ phone: "", password: "" });
  const [showPw, setShowPw] = useState(false);
  const [tried, setTried] = useState(false);
  const [cats, setCats] = useState<string[]>([]);
  const [otherCat, setOtherCat] = useState("");
  const catOptions = useApi<CategoryOption[]>(type ? `onboarding/categories?type=${type}` : null);
  const [key] = useState(newKey);
  const [done, setDone] = useState<{ host: string; tenantId: string } | null>(null);
  const [opening, setOpening] = useState(false);
  const [ready, setReady] = useState(false);

  // Debounced live availability check; state is only written after the response arrives.
  useEffect(() => {
    if (slug.length < 3) return;
    const h = setTimeout(async () => {
      try {
        const r = await api<{ available: boolean }>(`onboarding/slug-available?slug=${encodeURIComponent(slug)}`);
        if (!r.available && manualSlug === null) setSuffix(Math.random().toString(36).slice(2, 5));   // taken: quietly try another
        setCheck({ slug, state: r.available ? "ok" : "taken" });
      } catch { setCheck({ slug, state: "invalid" }); }
    }, 350);
    return () => clearTimeout(h);
  }, [slug, manualSlug]);
  const avail: "idle" | "checking" | "ok" | "taken" | "invalid" = slug.length < 3 ? "idle" : check.slug === slug ? check.state : "checking";

  // A brand-new address may need up to a minute before its secure certificate exists. Wait for it here, so the button never lands on a browser warning.
  const doneHost = done?.host;
  useEffect(() => {
    if (!doneHost) return;
    let stop = false;
    const probe = async () => {
      try {
        const port = window.location.port ? `:${window.location.port}` : "";
        await fetch(`${window.location.protocol}//${doneHost}${port}/api/v1/tenant/resolve`, { mode: "no-cors", cache: "no-store" });
        if (!stop) setReady(true);
      } catch {
        if (!stop) setTimeout(() => void probe(), 3000);
      }
    };
    void probe();
    return () => { stop = true; };
  }, [doneHost]);

  const siteUrl = (host: string) => `${window.location.protocol}//${host}${window.location.port ? `:${window.location.port}` : ""}`;

  const create = useAction(async () => {
    const r = await api<{ host: string; tenant: { id: string } }>("onboarding/tenants", {
      body: { type, name: name.trim(), slug, ownerFirstName: name.trim(), phone: owner.phone.trim(), password: owner.password, locale, categories: cats, otherCategory: cats.includes("other") ? otherCat.trim() : undefined },
      idempotencyKey: key,
    });
    trackStep("/register/~done");
    setDone({ host: r.host, tenantId: r.tenant.id });   // the session stays: opening the dashboard signs in on the new site without asking again
  });

  const next = type === "STORE" ? "/dashboard/welcome" : "/dashboard";
  const openDashboard = async () => {
    if (!done) return;
    setOpening(true);
    try {
      const r = await api<{ ticket: string }>("auth/handoff", { body: { tenantId: done.tenantId } });
      window.location.assign(`${siteUrl(done.host)}/api/sso?ticket=${encodeURIComponent(r.ticket)}&next=${encodeURIComponent(next)}`);
    } catch {
      window.location.assign(`${siteUrl(done.host)}/dashboard/login?phone=${encodeURIComponent(owner.phone)}&next=${encodeURIComponent(next)}`);   // fall back to the normal login
    }
  };

  // As soon as the secure address answers, go straight in: nobody has to find and press a button.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (done && ready && !autoOpened.current) { autoOpened.current = true; const id = setTimeout(() => void openDashboard(), 900); return () => clearTimeout(id); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [done, ready]);

  useEffect(() => { if (create.error) trackStep("/register/~error"); }, [create.error]);
  useEffect(() => { if (type) trackStep(`/register/~form-${type.toLowerCase()}`); }, [type]);
  const touched = useRef(false);
  const onStart = () => { if (!touched.current) { touched.current = true; trackStep("/register/~typing"); } };

  const go = (s: number) => { setDirn(s > step ? 1 : -1); setStep(s); };
  const th = type ? THEME[type] : THEME.STORE;
  const step1ok = name.trim().length >= 2 && (cats.length === 0 || categoriesValid(cats, otherCat));
  const step2ok = validMobile(owner.phone) && owner.password.length >= 8 && avail === "ok";
  const score = pwScore(owner.password);
  const slide = {
    initial: (d: number) => (reduce ? { opacity: 0 } : { opacity: 0, x: d * (locale === "ar" ? -40 : 40) }),
    animate: { opacity: 1, x: 0, transition: { duration: 0.4, ease: [0.2, 0.8, 0.2, 1] as const } },
    exit: (d: number) => (reduce ? { opacity: 0 } : { opacity: 0, x: d * (locale === "ar" ? 40 : -40), transition: { duration: 0.2 } }),
  };

  const topBar = (
    <div className="flex items-center justify-between gap-3 px-5 py-4 sm:px-8">
      <Link href={type === "CLINIC" ? "/tabib" : "/"} className="flex items-center gap-2"><LogoMark className="h-8 w-8" /><span className="font-display font-bold">{t("brand.name")}</span></Link>
      <div className="flex items-center gap-1 text-sm">
        <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="rounded-full px-3 py-1.5 font-medium text-slate-500 hover:bg-slate-100 hover:text-slate-900">{locale === "ar" ? "EN" : "عربي"}</a>
        {!done && <Link href="/login" className="rounded-full px-3 py-1.5 font-medium text-slate-500 hover:bg-slate-100 hover:text-slate-900">{t("nav.login")}</Link>}
      </div>
    </div>
  );

  // ---------- choose the kind of business ----------
  if (!type) {
    const opts: [Type, string, string, IconName][] = [["STORE", "type.store", "register.storeDesc", "bag"], ["CLINIC", "type.clinic", "register.clinicDesc", "stethoscope"], ["LAWYER", "type.lawyer", "register.lawyerDesc", "scale"]];
    return (
      <div className="min-h-dvh bg-[#f6f6f3]">
        {topBar}
        <div className="mx-auto max-w-3xl px-5 pb-16 pt-10 sm:pt-16">
          <motion.h1 initial={reduce ? false : { opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} className="text-center font-display text-3xl font-bold tracking-tight sm:text-4xl">{t("reg2.chooseTitle")}</motion.h1>
          <p className="mt-3 text-center text-slate-500">{t("reg2.chooseSub")}</p>
          <div className="mt-10 grid gap-4 sm:grid-cols-3">
            {opts.map(([v, title, desc, icon], i) => (
              <motion.button key={v} initial={reduce ? false : { opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0, transition: { delay: 0.08 * i } }} whileHover={reduce ? undefined : { y: -4 }}
                onClick={() => setType(v)} className="group rounded-3xl border border-black/[.07] bg-white p-6 text-start shadow-[0_1px_0_rgba(0,0,0,.03)] transition hover:shadow-[0_30px_60px_-30px_rgba(0,0,0,.3)]">
                <span className="mb-5 grid h-12 w-12 place-items-center rounded-2xl text-white" style={{ background: THEME[v].accent }}><Icon name={icon} className="h-6 w-6" /></span>
                <p className="font-display text-lg font-bold">{t(title)}</p>
                <p className="mt-1 text-sm leading-relaxed text-slate-500">{t(desc)}</p>
                <span className="mt-5 inline-flex items-center gap-1 text-sm font-semibold" style={{ color: THEME[v].accent }}>{t("reg2.next")}<Icon name="arrow" className="h-4 w-4 flip-rtl transition group-hover:translate-x-1 rtl:group-hover:-translate-x-1" /></span>
              </motion.button>
            ))}
          </div>
        </div>
      </div>
    );
  }

  const host = `${slug}.${rootDomain}`;
  const gets = type === "STORE" ? ["reg2.getStore1", "reg2.getStore2", "reg2.getStore3", "reg2.getStore4"] : type === "CLINIC" ? ["reg2.getClinic1", "reg2.getClinic2", "reg2.getClinic3", "reg2.getStore4"] : ["reg2.getLawyer1", "reg2.getLawyer2", "reg2.getLawyer3", "reg2.getStore4"];

  return (
    <div className="grid min-h-dvh lg:grid-cols-[minmax(0,1fr)_minmax(0,0.85fr)]">
      {/* ---------- form side ---------- */}
      <div className="flex min-h-dvh flex-col bg-[#fbfbf9]">
        {topBar}
        <div className="flex flex-1 items-start justify-center px-5 pb-12 pt-4 sm:items-center sm:px-8">
          <div className="w-full max-w-md">
            {!done && (
              <div className="mb-8">
                <div className="mb-3 flex items-center justify-between text-xs font-semibold text-slate-500">
                  <span>{t("reg2.step", { n: step, total: 2 })}</span>
                  {step === 1 && !initialType && <button type="button" onClick={() => { setType(null); setCats([]); setOtherCat(""); }} className="hover:text-slate-900">{t("reg2.back")}</button>}
                </div>
                <div className="flex gap-1.5">{[1, 2].map((s) => <span key={s} className="h-1 flex-1 overflow-hidden rounded-full bg-black/[.06]"><motion.span className="block h-full origin-left rounded-full rtl:origin-right" style={{ background: th.accent }} initial={false} animate={{ scaleX: step >= s ? 1 : 0 }} transition={{ duration: 0.5, ease: [0.2, 0.8, 0.2, 1] }} /></span>)}</div>
              </div>
            )}
            <AnimatePresence mode="wait" custom={dirn} initial={false}>
              {done ? (
                <motion.div key="act" custom={1} variants={slide} initial="initial" animate="animate" exit="exit">
                  <Activation t={t} type={type} ready={ready} opening={opening} accent={th.accent} host={done.host} onOpen={() => void openDashboard()} siteHref={siteUrl(done.host)} />
                </motion.div>
              ) : step === 1 ? (
                <motion.form key="s1" custom={dirn} variants={slide} initial="initial" animate="animate" exit="exit" onFocus={onStart}
                  onSubmit={(e) => { e.preventDefault(); if (step1ok) go(2); }}>
                  <h1 className="font-display text-3xl font-bold tracking-tight">{type === "STORE" ? t("reg2.s1Store") : type === "LAWYER" ? t("reg2.s1Lawyer") : t("reg2.s1Clinic")}</h1>
                  <p className="mt-2 text-slate-500">{t("reg2.s1Hint")}</p>
                  <div className={`mt-6 rounded-2xl ring-4 ring-transparent transition ${th.ring}`}>
                    <Input value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} autoComplete="organization" autoFocus className="!h-14 !rounded-2xl !text-lg" aria-label={type === "STORE" ? t("register.storeName") : type === "LAWYER" ? t("register.lawyerName") : t("register.clinicName")} />
                  </div>
                  <div className="mt-8">
                    <p className="font-semibold">{type === "STORE" ? t("reg2.whatSell") : type === "LAWYER" ? t("reg2.whatPractice") : t("reg2.whatSpecialty")}</p>
                    <p className="mb-3 mt-1 text-sm text-slate-500">{type === "STORE" ? t("reg2.whatHint") : t("reg2.whatHintPro")}</p>
                    {catOptions.data ? <CategoryPicker key={type} options={catOptions.data} value={cats} onChange={setCats} other={otherCat} onOther={setOtherCat} /> : <div className="skeleton h-40 rounded-xl" />}
                  </div>
                  <button type="submit" disabled={!step1ok} className={`mt-8 flex h-13 w-full items-center justify-center gap-2 rounded-2xl py-3.5 text-base font-bold transition active:scale-[.98] disabled:opacity-40 ${th.btn}`}>
                    {t("reg2.next")}<Icon name="arrow" className="h-5 w-5 flip-rtl" />
                  </button>
                </motion.form>
              ) : (
                <motion.form key="s2" custom={dirn} variants={slide} initial="initial" animate="animate" exit="exit" className="space-y-5"
                  onSubmit={(e) => { e.preventDefault(); setTried(true); if (step2ok) { trackStep("/register/~submit"); void create.run(); } }}>
                  <div>
                    <h1 className="font-display text-3xl font-bold tracking-tight">{t("reg2.s2")}</h1>
                    <p className="mt-2 text-slate-500">{t("reg2.s2Hint")}</p>
                  </div>
                  <Field label={t("register.phone")} hint={t("register.phoneHint")} error={(tried || owner.phone.length > 10) && !validMobile(owner.phone) ? t("register.phoneInvalid") : undefined}>
                    <PhoneInput value={owner.phone} onValue={(phone) => setOwner({ ...owner, phone })} required />
                  </Field>
                  <Field label={t("login.password")} hint={t("login.passwordRule")} error={tried && owner.password.length < 8 ? t("login.passwordRule") : undefined}>
                    <div className="relative">
                      <Input type={showPw ? "text" : "password"} value={owner.password} onChange={(e) => setOwner({ ...owner, password: e.target.value })} required minLength={8} autoComplete="new-password" className="pe-16" />
                      <button type="button" onClick={() => setShowPw(!showPw)} className="absolute end-3 top-1/2 -translate-y-1/2 text-xs font-semibold" style={{ color: th.accent }}>{showPw ? t("register.hidePw") : t("register.showPw")}</button>
                    </div>
                    {owner.password && (
                      <div className="mt-2 flex items-center gap-2">
                        <div className="flex flex-1 gap-1">{[0, 1, 2].map((i) => <span key={i} className="h-1 flex-1 rounded-full transition-colors duration-300" style={{ background: i <= score && owner.password.length >= 8 ? (score === 2 ? "#16a34a" : "#f59e0b") : i === 0 && owner.password.length < 8 ? "#ef4444" : "rgba(0,0,0,.08)" }} />)}</div>
                        <span className="text-xs font-semibold text-slate-500">{owner.password.length < 8 ? t("reg2.pwWeak") : score === 2 ? t("reg2.pwStrong") : t("reg2.pwOk")}</span>
                      </div>
                    )}
                  </Field>
                  <div className="rounded-2xl border border-black/[.07] bg-white p-4">
                    <div className="flex items-center justify-between gap-3">
                      <p className="text-sm font-semibold">{t("reg2.yourAddress")}</p>
                      <button type="button" onClick={() => setEditSlug(!editSlug)} className="text-xs font-semibold" style={{ color: th.accent }}>{t("reg2.change")}</button>
                    </div>
                    <div dir="ltr" className="mt-2">
                      {editSlug ? (
                        <div className="flex items-center gap-2">
                          <Input value={slug} onChange={(e) => setManualSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ""))} required minLength={3} maxLength={40} autoCapitalize="none" autoCorrect="off" spellCheck={false} autoFocus />
                          <span className="whitespace-nowrap text-sm text-slate-500">.{rootDomain}</span>
                        </div>
                      ) : <p className="truncate font-mono text-sm text-slate-700">{host}</p>}
                      <p className={`mt-1.5 text-xs ${avail === "ok" ? "text-emerald-600" : avail === "checking" || avail === "idle" ? "text-slate-500" : "text-red-600"}`}>
                        {avail === "checking" ? t("register.checking") : avail === "ok" ? `✓ ${t("register.available")}` : avail === "taken" ? t("register.taken") : avail === "invalid" ? t("register.invalidSlug") : " "}
                      </p>
                    </div>
                  </div>
                  <ErrorText error={create.error} />
                  <div className="flex gap-2">
                    <button type="button" onClick={() => go(1)} className="h-13 rounded-2xl border border-black/10 bg-white px-5 font-semibold text-slate-700 transition hover:bg-slate-50">{t("reg2.back")}</button>
                    <button type="submit" disabled={create.loading} className={`flex h-13 flex-1 items-center justify-center gap-2 rounded-2xl py-3.5 text-base font-bold transition active:scale-[.98] disabled:opacity-60 ${th.btn}`}>
                      {create.loading ? <span className="h-5 w-5 animate-spin rounded-full border-2 border-white/40 border-t-white" /> : <Icon name="sparkles" className="h-5 w-5" />}
                      {type === "STORE" ? t("reg2.createStore") : type === "LAWYER" ? t("reg2.createLawyer") : t("reg2.createClinic")}
                    </button>
                  </div>
                  <p className="text-center text-xs text-slate-500">{t("register.reassure")} · <Link href="/privacy" className="underline">{t("reg2.terms")}</Link></p>
                </motion.form>
              )}
            </AnimatePresence>
          </div>
        </div>
      </div>

      {/* ---------- preview side (desktop) ---------- */}
      <aside className={`relative hidden overflow-hidden lg:flex lg:flex-col lg:justify-center ${th.pane}`}>
        <div className="pointer-events-none absolute inset-0" style={{ background: `radial-gradient(60% 50% at 70% 20%, ${th.accent}55, transparent 70%)` }} aria-hidden />
        <div className="relative mx-auto w-full max-w-md px-10 py-16">
          <p className="mb-4 text-xs font-bold uppercase tracking-widest opacity-60">{t("reg2.previewLabel")}</p>
          <Preview type={type} name={name.trim() || (type === "STORE" ? t("reg2.previewName") : type === "LAWYER" ? t("reg2.previewLawyer") : t("reg2.previewClinic"))} host={host} accent={th.accent} cats={cats} options={catOptions.data ?? []} locale={locale} />
          <p className="mb-4 mt-12 text-sm font-bold opacity-80">{t("reg2.getTitle")}</p>
          <ul className="space-y-3">
            {gets.map((g, i) => (
              <motion.li key={g} initial={reduce ? false : { opacity: 0, x: 12 }} animate={{ opacity: 1, x: 0, transition: { delay: 0.2 + i * 0.08 } }} className="flex items-start gap-3 text-[15px] opacity-90">
                <span className="mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full bg-white/15"><Icon name="check" className="h-3 w-3" /></span>{t(g)}
              </motion.li>
            ))}
          </ul>
        </div>
      </aside>
    </div>
  );
}

/** The site they are creating, drawn live from what they type (name, address, categories). */
function Preview({ type, name, host, accent, cats, options, locale }: { type: Type; name: string; host: string; accent: string; cats: string[]; options: CategoryOption[]; locale: string }) {
  const labels = cats.map((c) => options.find((o) => o.code === c)).filter(Boolean).map((o) => (locale === "ar" ? o!.nameAr : o!.nameEn));
  return (
    <motion.div layout className="overflow-hidden rounded-2xl bg-white text-[#111] shadow-[0_40px_80px_-30px_rgba(0,0,0,.6)]">
      <div className="flex items-center gap-1.5 border-b border-black/5 bg-[#f6f6f3] px-3 py-2.5">
        {["#ff5f57", "#febc2e", "#28c840"].map((c) => <span key={c} className="h-2 w-2 rounded-full" style={{ background: c }} />)}
        <span dir="ltr" suppressHydrationWarning className="ms-2 flex-1 truncate rounded-md bg-white px-2 py-0.5 text-[11px] text-slate-500"><Icon name="lock" className="me-1 inline h-3 w-3 align-[-2px]" />{host}</span>
      </div>
      <div className="p-4">
        <div className="flex items-center justify-between">
          <motion.p key={name} initial={{ opacity: 0.4 }} animate={{ opacity: 1 }} className="truncate font-display text-lg font-bold">{name}</motion.p>
          <span className="grid h-7 w-7 place-items-center rounded-full text-white" style={{ background: accent }}><Icon name={THEME[type].icon} className="h-3.5 w-3.5" /></span>
        </div>
        <div className="mt-2 flex min-h-6 flex-wrap gap-1">
          <AnimatePresence>{labels.slice(0, 4).map((l) => <motion.span key={l} initial={{ opacity: 0, scale: 0.8 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.8 }} className="rounded-full bg-black/[.05] px-2 py-0.5 text-[10px] font-semibold">{l}</motion.span>)}</AnimatePresence>
        </div>
        {type === "STORE" ? (
          <div className="mt-3 grid grid-cols-3 gap-2">
            {["85624674", "b865ad31", "495912d5"].map((im) => (
              <div key={im} className="overflow-hidden rounded-lg bg-[#f1f2f4]"><img src={`/demo-colors/${im}.png`} alt="" className="aspect-square w-full object-contain p-1.5" /><div className="space-y-1 bg-white p-1.5"><span className="block h-1.5 w-4/5 rounded bg-black/10" /><span className="block h-1.5 w-2/5 rounded" style={{ background: `${accent}88` }} /></div></div>
            ))}
          </div>
        ) : (
          <div className="mt-3 space-y-2">
            <div className="h-20 rounded-xl" style={{ background: `linear-gradient(135deg, ${accent}22, ${accent}08)` }} />
            <div className="flex gap-2"><span className="h-8 flex-1 rounded-lg bg-black/[.05]" /><span className="flex h-8 items-center rounded-lg px-3 text-[11px] font-bold text-white" style={{ background: accent }}>{type === "CLINIC" ? (locale === "ar" ? "احجز" : "Book") : (locale === "ar" ? "تواصل" : "Contact")}</span></div>
          </div>
        )}
      </div>
    </motion.div>
  );
}

/** After "create": real milestones (account + site created by the call, address answering, sign-in) with a calm animation. */
function Activation({ t, type, ready, opening, accent, host, onOpen, siteHref }: { t: (k: string, v?: Record<string, string | number>) => string; type: Type; ready: boolean; opening: boolean; accent: string; host: string; onOpen: () => void; siteHref: string }) {
  const reduce = useReducedMotion();
  const steps = [t("reg2.act1"), type === "STORE" ? t("reg2.act2Store") : t("reg2.act2"), t("reg2.act3"), t("reg2.act4")];
  const doneCount = opening ? 4 : ready ? 3 : 2;
  return (
    <div className="text-center">
      <div className="relative mx-auto h-28 w-28">
        <motion.span className="absolute inset-0 rounded-full" style={{ background: `conic-gradient(${accent} ${doneCount * 25}%, rgba(0,0,0,.06) 0)` }} initial={false} animate={{ rotate: reduce ? 0 : 360 }} transition={{ duration: 8, repeat: Infinity, ease: "linear" }} />
        <span className="absolute inset-2 grid place-items-center rounded-full bg-[#fbfbf9]">
          <AnimatePresence mode="wait">
            {doneCount >= 3 ? <motion.span key="ok" initial={{ scale: 0.4, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} className="grid h-14 w-14 place-items-center rounded-full text-white" style={{ background: accent }}><Icon name="check" className="h-7 w-7" /></motion.span>
              : <motion.span key="sp" exit={{ scale: 0.6, opacity: 0 }}><Icon name="sparkles" className="h-9 w-9" style={{ color: accent }} /></motion.span>}
          </AnimatePresence>
        </span>
      </div>
      <h1 className="mt-6 font-display text-2xl font-bold tracking-tight">{type === "STORE" ? t("reg2.actTitleStore") : t("reg2.actTitle")}</h1>
      <p className="mt-2 text-slate-500">{t("reg2.actBody")}</p>
      <ol className="mx-auto mt-8 max-w-xs space-y-3 text-start">
        {steps.map((s, i) => {
          const on = i < doneCount, busy = i === doneCount;
          return (
            <motion.li key={s} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0, transition: { delay: i * 0.15 } }} className={`flex items-center gap-3 text-sm font-medium ${on ? "text-slate-900" : "text-slate-400"}`}>
              <span className="grid h-6 w-6 place-items-center rounded-full transition-colors duration-500" style={{ background: on ? accent : "rgba(0,0,0,.06)", color: on ? "#fff" : undefined }}>
                {on ? <Icon name="check" className="h-3.5 w-3.5" /> : busy ? <span className="h-3 w-3 animate-spin rounded-full border-2 border-black/15 border-t-black/60" /> : null}
              </span>{s}
            </motion.li>
          );
        })}
      </ol>
      <p dir="ltr" className="mx-auto mt-8 w-fit rounded-full border border-black/[.07] bg-white px-4 py-2 font-mono text-xs text-slate-600">{host}</p>
      <div className="mt-6 flex flex-col justify-center gap-2 sm:flex-row">
        <button type="button" onClick={onOpen} disabled={opening || !ready} className="rounded-2xl px-6 py-3 text-sm font-bold text-white transition active:scale-[.97] disabled:opacity-50" style={{ background: accent }}>{opening ? t("myplaces.opening") : !ready ? t("register.preparing") : t("register.openDashboard")}</button>
        {ready && <a href={siteHref} className="rounded-2xl border border-black/10 bg-white px-6 py-3 text-sm font-semibold">{t("register.viewSite")}</a>}
      </div>
    </div>
  );
}
