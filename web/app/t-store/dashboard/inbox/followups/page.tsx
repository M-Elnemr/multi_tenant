"use client";

import Link from "next/link";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Suspense, useMemo, useState } from "react";
import { api, ApiError } from "@/lib/client";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ChannelIcon } from "@/components/channel-icons";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { PageHeader } from "@/components/ui";
import { useSection } from "@/components/dashboard/section";
import type { SavedReply } from "@/components/inbox/types";
import { reasonKey } from "@/components/inbox/bits";

type Step = { position?: number; delayMinutes: number; message: string; enabled: boolean };
type Settings = { enabled: boolean; steps: Step[]; minDelayMinutes: number; maxDelayMinutes: number; maxSteps: number };
type Row = { id: string; conversationId: string; stepPosition: number; dueAt: string; status: string; reason?: string | null; body: string; sentAt?: string | null; returnedAt?: string | null; channel: string; displayName?: string | null };
type Stats = { scheduled: number; sent: number; cancelled: number; failed: number; ineligible: number; returned: number; waiting: number };

const PRESETS = [30, 60, 180, 480, 720, 1380];

export default function FollowUpsPage() { return <Suspense fallback={null}><FollowUps /></Suspense>; }

/** Free automatic follow-ups (a sequence of up to five messages inside the 24 h window), their activity, and the saved replies used in the inbox. */
function FollowUps() {
  const { t } = useI18n();
  const [tab, setTab] = useSection(["automation", "activity", "saved"], "automation");
  return (
    <>
      <PageHeader eyebrow={t("fu.eyebrow")} title={t("fu.title")} subtitle={t("fu.subtitle")}
        actions={<Link href="/dashboard/inbox" className="inline-flex h-10 items-center gap-1.5 rounded-[11px] px-3.5 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-surface"><Icon name="chat" className="h-4 w-4" />{t("fu.backToInbox")}</Link>} />
      <div className="mb-6 flex w-fit rounded-[12px] bg-surface p-1 ring-1 ring-line">
        {(["automation", "activity", "saved"] as const).map((k) => (
          <button key={k} type="button" onClick={() => setTab(k)} className={`relative rounded-[9px] px-4 py-2 text-[13px] font-semibold transition ${tab === k ? "text-white" : "text-muted hover:text-ink"}`}>
            {tab === k && <motion.span layoutId="fu-tab" className="absolute inset-0 rounded-[9px] bg-ink" transition={{ type: "spring", stiffness: 500, damping: 38 }} />}
            <span className="relative">{t(`fu.tab.${k}`)}</span>
          </button>
        ))}
      </div>
      {tab === "automation" && <Automation />}
      {tab === "activity" && <Activity />}
      {tab === "saved" && <Saved />}
    </>
  );
}

function fmtDelay(min: number, locale: string, t: (k: string, p?: Record<string, string | number>) => string) {
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  if (min < 60) return t("fu.min", { n: n(min) });
  const h = Math.floor(min / 60), m = min % 60;
  return m ? t("fu.hmin", { h: n(h), m: n(m) }) : t("fu.hours", { n: n(h) });
}

function Automation() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const reduce = useReducedMotion();
  const { canWrite } = useMe();
  const write = canWrite("ai");
  const remote = useApi<Settings>("store/chat/followups/settings");
  const [edited, setS] = useState<{ enabled: boolean; steps: Step[] } | null>(null);   // null: not edited yet, show what is saved
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState<"WHATSAPP" | "FACEBOOK" | "INSTAGRAM" | "WEB">("WHATSAPP");
  const s = edited ?? (remote.data ? { enabled: remote.data.enabled, steps: remote.data.steps.length ? remote.data.steps : [{ delayMinutes: 120, message: t("fu.defaultMsg"), enabled: true }] } : null);
  const problems = useMemo(() => {
    if (!s) return [];
    const out: string[] = [];
    s.steps.forEach((st, i) => {
      if (st.delayMinutes < 30 || st.delayMinutes > 1380) out.push(t("fu.err.range", { n: i + 1 }));
      if (i > 0 && st.delayMinutes <= s.steps[i - 1].delayMinutes) out.push(t("fu.err.order", { n: i + 1 }));
      if (!st.message.trim()) out.push(t("fu.err.message", { n: i + 1 }));
    });
    return out;
  }, [s, t]);
  if (!s) return <div className="grid gap-5 lg:grid-cols-[1.25fr_0.75fr]"><div className="skeleton h-96 rounded-[18px]" /><div className="skeleton h-96 rounded-[18px]" /></div>;
  const setStep = (i: number, p: Partial<Step>) => setS({ ...s, steps: s.steps.map((x, k) => (k === i ? { ...x, ...p } : x)) });
  const addStep = () => {
    const last = s.steps[s.steps.length - 1]?.delayMinutes ?? 0;
    const next = PRESETS.find((p) => p > last) ?? Math.min(1380, last + 60);
    setS({ ...s, steps: [...s.steps, { delayMinutes: next, message: "", enabled: true }] });
  };
  const save = async () => {
    setSaving(true);
    try {
      const r = await api<Settings>("store/chat/followups/settings", { method: "PUT", body: { enabled: s.enabled, steps: s.steps.map((x) => ({ delayMinutes: x.delayMinutes, message: x.message, enabled: x.enabled })) } });
      setS({ enabled: r.enabled, steps: r.steps });
      toast(t("fu.saved"));
    } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); } finally { setSaving(false); }
  };
  const total = 1380;
  const bubble = preview === "WHATSAPP" ? "bg-[#d9fdd3] text-[#111b21]" : preview === "FACEBOOK" ? "bg-[linear-gradient(135deg,#00b2ff,#006aff)] text-white" : preview === "INSTAGRAM" ? "bg-[linear-gradient(135deg,#a033ff,#e1306c)] text-white" : "bg-ink text-surface";
  const fill = (m: string) => m.replace("{name}", locale === "ar" ? "سارة" : "Sara").replace("{shop}", t("fu.shopSample"));

  return (
    <div className="grid gap-5 lg:grid-cols-[1.25fr_0.75fr]">
      <div className="space-y-5">
        {/* on / off */}
        <div className={`flex items-start justify-between gap-4 rounded-[18px] p-5 ring-1 transition ${s.enabled ? "bg-[linear-gradient(135deg,rgb(124_92_255/.07),rgb(34_211_238/.07))] ring-violet-200" : "bg-surface ring-line"}`}>
          <div>
            <p className="flex items-center gap-2 font-display text-[16px] font-semibold text-ink">{t("fu.autoTitle")}<span className="rounded-full bg-emerald-500 px-2 py-0.5 text-[10.5px] font-bold uppercase tracking-wide text-white">{t("fu.free")}</span></p>
            <p className="mt-1 max-w-lg text-[13px] leading-relaxed text-muted">{t("fu.autoHint")}</p>
          </div>
          <Switch checked={s.enabled} onChange={(v) => setS({ ...s, enabled: v })} disabled={!write} label={t("fu.autoTitle")} />
        </div>

        {/* the 24 h window bar */}
        <div className="rounded-[18px] bg-surface p-5 ring-1 ring-line">
          <p className="mb-3 text-[12px] font-semibold uppercase tracking-wide text-faint">{t("fu.windowTitle")}</p>
          <div className="relative h-10">
            <div className="absolute inset-x-0 top-4 h-2 rounded-full bg-[linear-gradient(90deg,rgb(16_185_129/.25),rgb(16_185_129/.15)_70%,rgb(244_63_94/.2))] rtl:bg-[linear-gradient(270deg,rgb(16_185_129/.25),rgb(16_185_129/.15)_70%,rgb(244_63_94/.2))]" />
            <span className="absolute start-0 top-0 text-[10.5px] font-semibold text-faint">{t("fu.lastMsg")}</span>
            <span className="absolute end-0 top-0 text-[10.5px] font-semibold text-rose-500">{t("fu.windowEnd")}</span>
            {s.steps.map((st, i) => (
              <motion.span key={i} layout={!reduce} className="absolute top-2.5 grid h-5 w-5 -translate-x-1/2 place-items-center rounded-full bg-violet-600 text-[10px] font-bold text-white ring-4 ring-surface rtl:translate-x-1/2"
                style={{ insetInlineStart: `${Math.min(100, (st.delayMinutes / total) * 100)}%`, opacity: st.enabled ? 1 : 0.35 }}>{i + 1}</motion.span>
            ))}
          </div>
          <p className="mt-2 text-[12px] text-muted">{t("fu.windowHint")}</p>
        </div>

        {/* steps */}
        <ol className="relative space-y-3">
          <AnimatePresence initial={false}>
            {s.steps.map((st, i) => (
              <motion.li key={i} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, height: 0 }} className="relative rounded-[18px] bg-surface p-4 ring-1 ring-line">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="grid h-7 w-7 place-items-center rounded-full bg-violet-600 text-[12px] font-bold text-white">{i + 1}</span>
                  <span className="text-[13px] font-semibold text-ink">{t("fu.sendAfter")}</span>
                  <select value={PRESETS.includes(st.delayMinutes) ? st.delayMinutes : "custom"} disabled={!write}
                    onChange={(e) => setStep(i, { delayMinutes: e.target.value === "custom" ? st.delayMinutes : Number(e.target.value) })}
                    className="h-9 rounded-[10px] bg-canvas px-2.5 text-[13px] font-semibold outline-none ring-1 ring-line focus:ring-2 focus:ring-brand/30">
                    {PRESETS.map((p) => <option key={p} value={p}>{fmtDelay(p, locale, t)}</option>)}
                    <option value="custom">{t("fu.custom")}</option>
                  </select>
                  {!PRESETS.includes(st.delayMinutes) && (
                    <span className="flex items-center gap-1">
                      <input type="number" min={0} max={23} value={Math.floor(st.delayMinutes / 60)} onChange={(e) => setStep(i, { delayMinutes: Math.max(0, Math.min(23, Number(e.target.value) || 0)) * 60 + (st.delayMinutes % 60) })} className="h-9 w-14 rounded-[10px] bg-canvas px-2 text-center text-[13px] ring-1 ring-line" aria-label={t("fu.hoursLabel")} />
                      <span className="text-[12px] text-muted">{t("fu.h")}</span>
                      <input type="number" min={0} max={59} step={5} value={st.delayMinutes % 60} onChange={(e) => setStep(i, { delayMinutes: Math.floor(st.delayMinutes / 60) * 60 + Math.max(0, Math.min(59, Number(e.target.value) || 0)) })} className="h-9 w-14 rounded-[10px] bg-canvas px-2 text-center text-[13px] ring-1 ring-line" aria-label={t("fu.minutesLabel")} />
                      <span className="text-[12px] text-muted">{t("fu.m")}</span>
                    </span>
                  )}
                  <span className="text-[12px] text-faint">{t("fu.afterLast")}</span>
                  <span className="ms-auto flex items-center gap-2">
                    <Switch size="sm" checked={st.enabled} onChange={(v) => setStep(i, { enabled: v })} disabled={!write} label={t("fu.stepOn")} />
                    {write && s.steps.length > 1 && <button type="button" onClick={() => setS({ ...s, steps: s.steps.filter((_, k) => k !== i) })} aria-label={t("common.delete")} className="grid h-8 w-8 place-items-center rounded-[9px] text-faint hover:bg-red-50 hover:text-red-600"><Icon name="close" className="h-4 w-4" /></button>}
                  </span>
                </div>
                <textarea value={st.message} onChange={(e) => setStep(i, { message: e.target.value })} maxLength={600} rows={3} disabled={!write} placeholder={t("fu.msgPh")}
                  className="mt-3 w-full resize-none rounded-[12px] bg-canvas px-3 py-2.5 text-[14px] leading-relaxed outline-none ring-1 ring-line focus:bg-surface focus:ring-2 focus:ring-brand/30" dir="auto" />
                <div className="mt-1.5 flex items-center justify-between text-[11.5px] text-faint">
                  <span className="flex gap-1">{["{name}", "{shop}"].map((v) => <button key={v} type="button" disabled={!write} onClick={() => setStep(i, { message: (st.message + " " + v).trim() })} className="rounded-[6px] bg-canvas px-1.5 py-0.5 font-mono text-brand ring-1 ring-line" dir="ltr">{v}</button>)}</span>
                  <span className="tnum">{st.message.length}/600</span>
                </div>
              </motion.li>
            ))}
          </AnimatePresence>
        </ol>
        {write && s.steps.length < 5 && (
          <button type="button" onClick={addStep} className="flex w-full items-center justify-center gap-2 rounded-[16px] border border-dashed border-line-strong py-3.5 text-[13px] font-semibold text-muted transition hover:border-violet-400 hover:text-violet-700"><Icon name="plus" className="h-4 w-4" />{t("fu.addStep")}</button>
        )}
        {problems.length > 0 && <ul className="space-y-1 rounded-[12px] bg-red-50 px-4 py-3 text-[12.5px] text-red-700">{problems.map((p) => <li key={p}>• {p}</li>)}</ul>}
        <div className="rounded-[14px] bg-canvas p-4 text-[12.5px] leading-relaxed text-muted ring-1 ring-line">
          <p className="mb-1 font-semibold text-ink">{t("fu.rulesTitle")}</p>
          <ul className="list-disc space-y-0.5 ps-4">{[1, 2, 3, 4, 5].map((n) => <li key={n}>{t(`fu.rule${n}`)}</li>)}</ul>
        </div>
        {write && <button type="button" onClick={() => void save()} disabled={saving || problems.length > 0} className="h-11 w-full rounded-[12px] bg-ink text-[14px] font-semibold text-surface transition hover:bg-ink-2 disabled:opacity-50 sm:w-auto sm:px-8">{saving ? "…" : t("common.save")}</button>}
      </div>

      {/* live preview */}
      <div className="lg:sticky lg:top-20 lg:self-start">
        <div className="rounded-[22px] bg-surface p-4 ring-1 ring-line">
          <div className="mb-3 flex items-center justify-between">
            <p className="text-[12px] font-semibold uppercase tracking-wide text-faint">{t("fu.preview")}</p>
            <div className="flex gap-1">{(["WHATSAPP", "FACEBOOK", "INSTAGRAM", "WEB"] as const).map((c) => <button key={c} type="button" onClick={() => setPreview(c)} aria-label={t(`inbox.ch.${c}`)} className={`grid h-8 w-8 place-items-center rounded-[9px] ring-1 transition ${preview === c ? "bg-canvas ring-line-strong" : "ring-transparent opacity-60 hover:opacity-100"}`}><ChannelIcon channel={c} className="h-4 w-4" /></button>)}</div>
          </div>
          <div className={`space-y-2 rounded-[18px] p-3 ${preview === "WHATSAPP" ? "bg-[#efeae2]" : "bg-canvas"}`} style={{ minHeight: 320 }}>
            <div className="flex justify-start"><span className="max-w-[80%] rounded-[16px] rounded-ss-[5px] bg-white px-3 py-2 text-[13px] text-[#111b21] shadow-sm">{t("fu.sampleCustomer")}</span></div>
            <div className="flex justify-end"><span className={`max-w-[80%] rounded-[16px] rounded-se-[5px] px-3 py-2 text-[13px] shadow-sm ${bubble}`}>{t("fu.sampleAi")}</span></div>
            {s.steps.filter((x) => x.enabled && x.message.trim()).map((x, i) => (
              <motion.div key={`${i}-${x.delayMinutes}`} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: reduce ? 0 : 0.12 * i }}>
                <p className="my-1.5 text-center text-[10.5px] font-semibold text-[#667781]">{t("fu.laterBy", { d: fmtDelay(x.delayMinutes, locale, t) })}</p>
                <div className="flex justify-end"><span className={`max-w-[80%] whitespace-pre-wrap rounded-[16px] rounded-se-[5px] px-3 py-2 text-[13px] shadow-sm ${bubble}`} dir="auto">{fill(x.message)}</span></div>
              </motion.div>
            ))}
          </div>
          <p className="mt-3 flex items-start gap-2 rounded-[12px] bg-emerald-50 px-3 py-2.5 text-[12px] leading-relaxed text-emerald-900"><Icon name="check" className="mt-0.5 h-4 w-4 shrink-0" /><span><b>{t("fu.freeBadge")}</b> {t("fu.providerNote")}</span></p>
        </div>
      </div>
    </div>
  );
}

function Activity() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { canWrite } = useMe();
  const write = canWrite("ai");
  const [status, setStatus] = useState("SCHEDULED");
  const stats = useApi<Stats>("store/chat/followups/stats");
  const list = useApi<Row[]>(`store/chat/followups?status=${status}`);
  const [cancel, setCancel] = useState<Row | null>(null);
  const [edit, setEdit] = useState<{ id: string; body: string } | null>(null);
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const cards: { k: keyof Stats; icon: string; tone: string }[] = [
    { k: "scheduled", icon: "clock", tone: "text-sky-600 bg-sky-50" }, { k: "sent", icon: "send", tone: "text-emerald-600 bg-emerald-50" },
    { k: "returned", icon: "heart", tone: "text-violet-600 bg-violet-50" }, { k: "cancelled", icon: "xcircle", tone: "text-slate-600 bg-slate-100" }, { k: "failed", icon: "alert", tone: "text-red-600 bg-red-50" },
  ];
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        {cards.map((c) => (
          <div key={c.k} className="rounded-[16px] bg-surface p-4 ring-1 ring-line">
            <span className={`mb-2 grid h-8 w-8 place-items-center rounded-[10px] ${c.tone}`}><Icon name={c.icon} className="h-4 w-4" /></span>
            <p className="font-display text-[22px] font-semibold text-ink tnum">{stats.data ? n(stats.data[c.k]) : "–"}</p>
            <p className="text-[12px] text-muted">{t(`fu.stat.${c.k}`)}</p>
          </div>
        ))}
      </div>
      <p className="text-[12px] text-faint">{t("fu.statsNote")}</p>
      <div className="flex flex-wrap gap-1.5">
        {["SCHEDULED", "SENT", "CANCELLED", "FAILED", "INELIGIBLE"].map((s) => <button key={s} type="button" onClick={() => setStatus(s)} className={`rounded-full px-3 py-1.5 text-[12.5px] font-semibold transition ${status === s ? "bg-ink text-surface" : "bg-surface text-muted ring-1 ring-line hover:text-ink"}`}>{t(`fu.st.${s}`)}</button>)}
      </div>
      <div className="overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
        {!list.data && <div className="space-y-2 p-4">{[0, 1, 2].map((i) => <div key={i} className="skeleton h-14 rounded-[12px]" />)}</div>}
        {list.data?.length === 0 && <p className="px-6 py-14 text-center text-[13px] text-muted">{t("fu.none")}</p>}
        <ul className="divide-y divide-line">
          {list.data?.map((r) => (
            <li key={r.id} className="flex flex-col gap-2 px-4 py-3.5 sm:flex-row sm:items-center">
              <Link href={`/dashboard/inbox?id=${r.conversationId}`} className="flex min-w-0 flex-1 items-start gap-3">
                <ChannelIcon channel={r.channel} className="mt-0.5 h-4 w-4" />
                <span className="min-w-0"><span className="block text-[13.5px] font-semibold text-ink">{r.displayName || t("inbox.guest")} <span className="font-normal text-faint">· {t("inbox.step", { n: r.stepPosition })}</span></span>
                  <span className="line-clamp-1 text-[12.5px] text-muted" dir="auto">{r.body}</span>
                  {r.reason && <span className="text-[11.5px] text-faint">{t(reasonKey(r.reason))}</span>}</span>
              </Link>
              <span className="text-[12px] text-muted tnum sm:w-40 sm:text-end">{new Date(r.sentAt ?? r.dueAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" })}{r.returnedAt && <span className="ms-1 rounded-full bg-violet-50 px-1.5 text-[10.5px] font-semibold text-violet-700">{t("fu.returned")}</span>}</span>
              {write && r.status === "SCHEDULED" && (
                <span className="flex gap-1">
                  <button type="button" onClick={() => setEdit({ id: r.id, body: r.body })} className="rounded-[9px] px-2.5 py-1.5 text-[12px] font-semibold text-ink-2 ring-1 ring-line hover:bg-canvas">{t("common.edit")}</button>
                  <button type="button" onClick={() => setCancel(r)} className="rounded-[9px] px-2.5 py-1.5 text-[12px] font-semibold text-red-600 ring-1 ring-red-200 hover:bg-red-50">{t("common.cancel")}</button>
                </span>
              )}
            </li>
          ))}
        </ul>
      </div>
      <ConfirmDialog open={!!cancel} onClose={() => setCancel(null)} title={t("fu.cancelTitle")} body={t("fu.cancelBody")} confirmLabel={t("fu.cancelYes")} cancelLabel={t("common.back")} danger
        onConfirm={async () => { if (!cancel) return; try { await api(`store/chat/followups/${cancel.id}/cancel`, { body: {} }); toast(t("fu.cancelled")); } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.actionFailed"), { tone: "error" }); } setCancel(null); void list.reload(); void stats.reload(); }} />
      <ConfirmDialog open={!!edit} onClose={() => setEdit(null)} title={t("fu.editTitle")} confirmLabel={t("common.save")} cancelLabel={t("common.cancel")}
        body={edit && <textarea value={edit.body} onChange={(e) => setEdit({ ...edit, body: e.target.value })} rows={4} maxLength={600} className="mt-2 w-full rounded-[12px] bg-canvas px-3 py-2 text-[13.5px] text-ink ring-1 ring-line" dir="auto" />}
        onConfirm={async () => { if (!edit) return; try { await api(`store/chat/followups/${edit.id}`, { method: "PUT", body: { body: edit.body } }); toast(t("fu.saved")); } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); } setEdit(null); void list.reload(); }} />
    </div>
  );
}

function Saved() {
  const { t } = useI18n();
  const toast = useToast();
  const { canWrite } = useMe();
  const write = canWrite("ai");
  const list = useApi<SavedReply[]>("store/chat/saved-replies");
  const [form, setForm] = useState<{ id?: string; title: string; body: string } | null>(null);
  const save = async () => {
    if (!form) return;
    try {
      if (form.id) await api(`store/chat/saved-replies/${form.id}`, { method: "PUT", body: { title: form.title, body: form.body } });
      else await api("store/chat/saved-replies", { body: { title: form.title, body: form.body } });
      setForm(null); void list.reload(); toast(t("fu.saved"));
    } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); }
  };
  return (
    <div className="grid gap-5 lg:grid-cols-[1fr_22rem]">
      <div className="overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
        {list.data?.length === 0 && <p className="px-6 py-14 text-center text-[13px] text-muted">{t("inbox.savedNone")}</p>}
        <ul className="divide-y divide-line">
          {list.data?.map((r) => (
            <li key={r.id} className="flex items-start gap-3 px-4 py-3.5">
              <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-[10px] bg-brand-soft text-brand"><Icon name="file" className="h-4 w-4" /></span>
              <span className="min-w-0 flex-1"><span className="block text-[13.5px] font-semibold text-ink">{r.title}</span><span className="line-clamp-2 text-[12.5px] text-muted" dir="auto">{r.body}</span></span>
              {write && <span className="flex gap-1">
                <button type="button" onClick={() => setForm({ id: r.id, title: r.title, body: r.body })} className="rounded-[9px] px-2.5 py-1.5 text-[12px] font-semibold text-ink-2 ring-1 ring-line hover:bg-canvas">{t("common.edit")}</button>
                <button type="button" onClick={async () => { await api(`store/chat/saved-replies/${r.id}`, { method: "DELETE" }).catch(() => undefined); void list.reload(); }} className="rounded-[9px] px-2.5 py-1.5 text-[12px] font-semibold text-red-600 ring-1 ring-red-200 hover:bg-red-50">{t("common.delete")}</button>
              </span>}
            </li>
          ))}
        </ul>
      </div>
      {write && (
        <div className="h-fit rounded-[18px] bg-surface p-4 ring-1 ring-line">
          <p className="mb-3 font-display text-[15px] font-semibold">{form?.id ? t("fu.editSaved") : t("fu.newSaved")}</p>
          <input value={form?.title ?? ""} onChange={(e) => setForm({ ...(form ?? { body: "" }), title: e.target.value })} maxLength={80} placeholder={t("fu.savedTitlePh")} className="mb-2 h-10 w-full rounded-[11px] bg-canvas px-3 text-[13.5px] outline-none ring-1 ring-line focus:ring-2 focus:ring-brand/30" />
          <textarea value={form?.body ?? ""} onChange={(e) => setForm({ ...(form ?? { title: "" }), body: e.target.value })} maxLength={2000} rows={5} placeholder={t("fu.savedBodyPh")} className="w-full resize-none rounded-[11px] bg-canvas px-3 py-2 text-[13.5px] outline-none ring-1 ring-line focus:ring-2 focus:ring-brand/30" dir="auto" />
          <p className="mt-1 text-[11.5px] text-faint">{t("fu.savedVars")}</p>
          <div className="mt-3 flex gap-2">
            <button type="button" onClick={() => void save()} disabled={!form?.title?.trim() || !form?.body?.trim()} className="flex-1 rounded-[11px] bg-ink py-2.5 text-[13px] font-semibold text-surface disabled:opacity-40">{t("common.save")}</button>
            {form && <button type="button" onClick={() => setForm(null)} className="rounded-[11px] px-3 text-[13px] font-semibold text-muted ring-1 ring-line">{t("common.cancel")}</button>}
          </div>
        </div>
      )}
    </div>
  );
}
