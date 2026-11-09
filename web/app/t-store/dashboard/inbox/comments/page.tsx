"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { api, ApiError } from "@/lib/client";
import { useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ChannelDot, ChannelIcon } from "@/components/channel-icons";
import { Icon } from "@/components/icons";
import { Sheet, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { ChatAvatar, when } from "@/components/inbox/bits";
import { money } from "@/lib/format";
import { POINT_PRICES } from "@/lib/pricing";

type Comment = {
  id: string; platform: "FACEBOOK" | "INSTAGRAM"; commentId: string; postId?: string; postPermalink?: string | null; postText?: string | null; postImage?: string | null; isAd: boolean;
  authorName?: string | null; text: string; question: boolean; status: "UNREPLIED" | "REPLIED" | "IGNORED"; replyText?: string | null; repliedBy?: "AI" | "STAMP" | "OWNER" | null; repliedAt?: string | null;
  dmStatus: "NONE" | "SENT" | "FAILED" | "NOT_ALLOWED"; dmText?: string | null; dmAt?: string | null; error?: string | null; createdAt: string;
};
type Settings = { publicReply: "OFF" | "STAMP" | "AI"; stampText?: string | null; dmEnabled: boolean; dmOnlyQuestions: boolean; aiPriceMinor: number; connected: string; todayReplies?: number; todayDms?: number; todayCostMinor?: number; messagePriceMinor?: number };
const msgPrice = (s: Settings | null) => s?.messagePriceMinor ?? Math.round(POINT_PRICES.comment * 100);

const mq = (q: string) => (cb: () => void) => { const m = window.matchMedia(q); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); };

export default function CommentsPage() { return <Suspense fallback={null}><Comments /></Suspense>; }

/**
 * Comments on the shop's Facebook and Instagram posts and ads, together with the platform tag: the unreplied ones first, a public reply or a private message
 * (written by the owner or suggested by the AI), and the automation (a fixed stamp reply, an AI reply, an AI private message to commenters).
 */
function Comments() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { canWrite } = useMe();
  const write = canWrite("ai");
  const wide = useSyncExternalStore(mq("(min-width: 1024px)"), () => window.matchMedia("(min-width: 1024px)").matches, () => true);
  const [platform, setPlatform] = useState<"" | "FACEBOOK" | "INSTAGRAM">("");
  const [unreplied, setUnreplied] = useState(true);
  const [q, setQ] = useState("");
  const [rows, setRows] = useState<Comment[] | null>(null);
  const [total, setTotal] = useState(0);
  const [sel, setSel] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [mode, setMode] = useState<"public" | "private">("public");
  const [busy, setBusy] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [lastMode, setLastMode] = useState<"STAMP" | "AI" | null>(null);

  const load = useCallback(async () => {
    const p = new URLSearchParams();
    if (platform) p.set("platform", platform);
    if (unreplied) p.set("status", "UNREPLIED");
    if (q.trim()) p.set("q", q.trim());
    try { const r = await api<{ data: Comment[]; total: number }>(`store/comments?${p}`); setRows(r.data); setTotal(r.total); } catch { setRows([]); }
  }, [platform, unreplied, q]);
  useEffect(() => { const id = setTimeout(() => void load(), q ? 300 : 0); return () => clearTimeout(id); }, [load, q]);
  useEffect(() => { const id = setInterval(() => { if (!document.hidden) void load(); }, 20_000); return () => clearInterval(id); }, [load]);
  useEffect(() => { api<Settings>("store/comments/settings").then(setSettings).catch(() => undefined); }, []);
  const pick = (id: string | null) => { setSel(id); setText(""); setMode("public"); };
  const shownId = sel ?? (wide ? rows?.[0]?.id ?? null : null);   // desktop: the first comment is open until one is picked
  const c = rows?.find((r) => r.id === shownId) ?? null;
  const update = (r: Comment) => setRows((x) => x?.map((o) => (o.id === r.id ? r : o)) ?? x);
  const run = async (path: string, body?: unknown, ok?: string) => {
    if (!c) return;
    setBusy(true);
    try { const r = await api<Comment>(`store/comments/${c.id}/${path}`, { body: body ?? {} }); update(r); setText(""); if (ok) toast(ok); }
    catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.actionFailed"), { tone: "error" }); }
    finally { setBusy(false); }
  };
  const suggest = async () => {
    if (!c) return;
    setBusy(true);
    try { const r = await api<{ text: string }>(`store/comments/${c.id}/suggest`, { body: { privately: mode === "private" } }); setText(r.text); }
    catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.assistFailed"), { tone: "error" }); }
    finally { setBusy(false); }
  };
  const saveSettings = async (next: Settings) => {
    try { setSettings(await api<Settings>("store/comments/settings", { method: "PUT", body: next })); toast(t("inbox.saved.ok")); }
    catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); }
  };

  const toggleAuto = (on: boolean) => {
    if (!settings) return;
    if (!on) { if (settings.publicReply !== "OFF") setLastMode(settings.publicReply); void saveSettings({ ...settings, publicReply: "OFF" }); return; }
    if (lastMode) void saveSettings({ ...settings, publicReply: lastMode }); else setSettingsOpen(true);
  };
  const list = (
    <div className="flex h-full min-h-0 flex-col bg-surface">
      {settings && <AutoReplyCard s={settings} write={write} onToggle={toggleAuto} onDm={(v) => void saveSettings({ ...settings, dmEnabled: v })} onEdit={() => setSettingsOpen(true)} />}
      <div className="space-y-3 px-4 pb-3 pt-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex rounded-[11px] bg-canvas p-0.5 ring-1 ring-line">
            <Link href="/dashboard/inbox" className="rounded-[9px] px-3 py-1.5 text-[13px] font-medium text-muted transition hover:text-ink">{t("inbox.conversations")}</Link>
            <span className="rounded-[9px] bg-surface px-3 py-1.5 text-[13px] font-semibold text-ink shadow-sm">{t("inbox.comments")}</span>
          </div>
        </div>
        <label className="flex h-10 items-center gap-2 rounded-[11px] bg-canvas px-3 ring-1 ring-line focus-within:ring-2 focus-within:ring-brand/30">
          <Icon name="search" className="h-4 w-4 text-faint" /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("cm.searchPh")} className="min-w-0 flex-1 bg-transparent text-[13.5px] outline-none placeholder:text-faint" />
        </label>
        <div className="flex items-center justify-between gap-2">
          <div className="flex gap-1.5">
            {(["", "FACEBOOK", "INSTAGRAM"] as const).map((p) => (
              <button key={p || "all"} type="button" onClick={() => setPlatform(p)} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[12.5px] font-semibold transition ${platform === p ? "bg-ink text-surface" : "bg-canvas text-muted ring-1 ring-line hover:text-ink"}`}>
                {p && <ChannelIcon channel={p} className="h-3.5 w-3.5" />}{p ? t(`inbox.ch.${p}`) : t("inbox.all")}
              </button>
            ))}
          </div>
          <label className="flex items-center gap-2 text-[12.5px] font-medium text-muted">{t("cm.unreplied")}<Switch size="sm" checked={unreplied} onChange={setUnreplied} label={t("cm.unreplied")} /></label>
        </div>
      </div>
      <div className="border-y border-line bg-canvas/60 px-4 py-1.5 text-[11.5px] text-faint tnum">{rows ? t("cm.count", { n: total.toLocaleString(locale === "ar" ? "ar-EG" : "en-US") }) : "…"}</div>
      <div className="min-h-0 flex-1 overflow-y-auto p-1.5">
        {!rows && <div className="space-y-1 p-2">{Array.from({ length: 6 }, (_, i) => <div key={i} className="skeleton h-16 rounded-[12px]" />)}</div>}
        {rows && rows.length === 0 && (
          <div className="px-6 py-16 text-center">
            <span className="mx-auto mb-4 grid h-14 w-14 place-items-center rounded-[18px] bg-sky-50 text-sky-600"><Icon name="edit" className="h-6 w-6" /></span>
            <p className="font-display text-[15px] font-semibold text-ink">{settings && !settings.connected ? t("cm.notConnected") : t("cm.empty")}</p>
            <p className="mx-auto mt-1 max-w-xs text-[13px] text-muted">{settings && !settings.connected ? t("cm.notConnectedHint") : t("cm.emptyHint")}</p>
            {settings && settings.connected && settings.publicReply === "OFF" && !settings.dmEnabled && (
              <div className="mx-auto mt-5 max-w-xs rounded-[14px] bg-[linear-gradient(135deg,rgb(124_92_255/.10),rgb(34_211_238/.10))] p-4 text-start ring-1 ring-violet-200">
                <p className="flex items-center gap-1.5 text-[13.5px] font-semibold text-ink"><Icon name="sparkles" className="h-4 w-4 text-violet-600" />{t("cm.card.setupTitle")}</p>
                <p className="mt-1 text-[12.5px] text-muted">{t("cm.card.setupHint")}</p>
                <button type="button" onClick={() => setSettingsOpen(true)} className="mt-3 h-9 rounded-[10px] bg-ink px-4 text-[13px] font-semibold text-surface">{t("cm.card.setupCta")}</button>
              </div>
            )}
            {settings && !settings.connected && <Link href="/dashboard/ai?tab=channels" className="mt-4 inline-block rounded-[10px] bg-ink px-4 py-2 text-[13px] font-semibold text-surface">{t("inbox.connectChannels")}</Link>}
          </div>
        )}
        {rows?.map((r) => (
          <button key={r.id} type="button" onClick={() => pick(r.id)} className={`flex w-full gap-3 rounded-[13px] px-2.5 py-3 text-start transition ${r.id === shownId ? "bg-[color-mix(in_srgb,var(--brand)_8%,var(--surface))] ring-1 ring-brand/20" : "hover:bg-[color-mix(in_srgb,var(--ink)_3.5%,transparent)]"}`}>
            <ChatAvatar name={r.authorName} channel={r.platform} />
            <span className="min-w-0 flex-1">
              <span className="flex items-baseline justify-between gap-2"><span className={`truncate text-[14px] ${r.status === "UNREPLIED" ? "font-bold text-ink" : "font-semibold text-ink-2"}`}>{r.authorName || t("inbox.guest")}</span><span className="shrink-0 text-[11px] text-faint tnum">{when(r.createdAt, locale, t("inbox.yesterday"))}</span></span>
              <span className="line-clamp-2 text-[12.5px] text-muted" dir="auto">{r.text}</span>
              <span className="mt-1.5 flex flex-wrap gap-1">
                {r.isAd && <Tag cls="bg-amber-50 text-amber-800 ring-amber-600/15">{t("cm.ad")}</Tag>}
                {r.question && <Tag cls="bg-sky-50 text-sky-800 ring-sky-600/15">{t("cm.question")}</Tag>}
                {r.status === "UNREPLIED" ? <Tag cls="bg-rose-50 text-rose-700 ring-rose-600/15">{t("cm.st.UNREPLIED")}</Tag> : <Tag cls="bg-emerald-50 text-emerald-700 ring-emerald-600/15">{r.repliedBy ? t(`cm.by.${r.repliedBy}`) : t(`cm.st.${r.status}`)}</Tag>}
                {r.dmStatus === "SENT" && <Tag cls="bg-violet-50 text-violet-700 ring-violet-600/15">{t("cm.dmSent")}</Tag>}
              </span>
            </span>
          </button>
        ))}
      </div>
    </div>
  );

  const detail = c ? (
    <div className="flex h-full min-h-0 flex-col bg-canvas">
      <div className="flex items-center gap-3 border-b border-line bg-surface px-4 py-3 sm:px-5">
        {!wide && <button type="button" onClick={() => pick(null)} aria-label={t("common.back")} className="grid h-10 w-10 place-items-center rounded-[10px] text-ink-2"><Icon name="arrow" className="h-5 w-5 rotate-180 rtl:rotate-0" /></button>}
        <ChannelDot channel={c.platform} size={30} />
        <div className="min-w-0 flex-1"><p className="truncate font-display text-[15px] font-semibold text-ink">{c.authorName || t("inbox.guest")}</p><p className="text-[12px] text-muted">{t(`inbox.ch.${c.platform}`)} · {c.isAd ? t("cm.onAd") : t("cm.onPost")}</p></div>
        {c.postPermalink && <a href={c.postPermalink} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 rounded-[10px] px-3 py-1.5 text-[12.5px] font-semibold text-ink-2 ring-1 ring-line hover:bg-surface">{t("cm.openPost")}<Icon name="external" className="h-3.5 w-3.5" /></a>}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-5 sm:px-8">
        <div className="mx-auto max-w-2xl space-y-4">
          {(c.postText || c.postImage) && (
            <div className="overflow-hidden rounded-[16px] bg-surface ring-1 ring-line">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {c.postImage && <img src={c.postImage} alt="" className="max-h-64 w-full object-cover" />}
              {c.postText && <p className="line-clamp-4 px-4 py-3 text-[13px] text-ink-2" dir="auto">{c.postText}</p>}
            </div>
          )}
          <div className="flex gap-3">
            <ChatAvatar name={c.authorName} channel={c.platform} size={36} />
            <div className="min-w-0 flex-1 rounded-[16px] rounded-ss-[6px] bg-surface px-4 py-3 ring-1 ring-line">
              <p className="text-[12px] font-semibold text-ink">{c.authorName}</p>
              <p className="mt-0.5 whitespace-pre-wrap text-[14px] text-ink" dir="auto">{c.text}</p>
              <p className="mt-1 text-[11px] text-faint tnum">{new Date(c.createdAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" })}</p>
            </div>
          </div>
          {c.replyText && (
            <div className="ms-12 rounded-[16px] rounded-ss-[6px] bg-ink px-4 py-3 text-surface">
              <p className="flex items-center gap-1.5 text-[11.5px] font-semibold text-white/60"><Icon name={c.repliedBy === "AI" ? "sparkles" : "users"} className="h-3 w-3" />{t(`cm.by.${c.repliedBy ?? "OWNER"}`)} · {t("cm.public")}</p>
              <p className="mt-0.5 whitespace-pre-wrap text-[14px]" dir="auto">{c.replyText}</p>
            </div>
          )}
          {c.dmText && (
            <div className="ms-12 rounded-[16px] rounded-ss-[6px] bg-violet-50 px-4 py-3 text-violet-950 ring-1 ring-violet-200">
              <p className="flex items-center gap-1.5 text-[11.5px] font-semibold text-violet-700"><Icon name="lock" className="h-3 w-3" />{t("cm.privateSent")}</p>
              <p className="mt-0.5 whitespace-pre-wrap text-[14px]" dir="auto">{c.dmText}</p>
            </div>
          )}
          {c.error && <p className="rounded-[12px] bg-red-50 px-3 py-2 text-[12.5px] text-red-700">{c.error}</p>}
        </div>
      </div>
      {write && (
        <div className="border-t border-line bg-surface px-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 sm:px-5">
          <div className="mb-2 flex items-center justify-between gap-2">
            <div className="flex rounded-[9px] bg-canvas p-0.5 ring-1 ring-line">
              {(["public", "private"] as const).map((m) => (
                <button key={m} type="button" onClick={() => setMode(m)} disabled={m === "private" && (c.dmStatus === "SENT" || c.dmStatus === "NOT_ALLOWED")}
                  className={`rounded-[7px] px-2.5 py-1 text-[12px] font-semibold transition disabled:opacity-40 ${mode === m ? "bg-surface text-ink shadow-sm" : "text-muted"}`}>{t(`cm.mode.${m}`)}</button>
              ))}
            </div>
            <button type="button" onClick={() => void suggest()} disabled={busy} className="inline-flex items-center gap-1.5 rounded-[9px] bg-[linear-gradient(135deg,rgb(124_92_255/.12),rgb(34_211_238/.12))] px-2.5 py-1 text-[12px] font-semibold text-violet-700 disabled:opacity-60"><Icon name="sparkles" className="h-3.5 w-3.5" />{t("inbox.aiAssist")}</button>
          </div>
          {mode === "private" && <p className="mb-2 text-[11.5px] text-muted">{t("cm.privateRule")}</p>}
          <div className="flex items-end gap-2">
            <textarea value={text} onChange={(e) => setText(e.target.value)} rows={2} maxLength={1900} placeholder={mode === "public" ? t("cm.publicPh") : t("cm.privatePh")}
              className="min-h-[44px] flex-1 resize-none rounded-[13px] bg-canvas px-3 py-2.5 text-[14px] outline-none ring-1 ring-line focus:bg-surface focus:ring-2 focus:ring-brand/30" />
            <button type="button" disabled={busy || !text.trim()} onClick={() => void run(mode === "public" ? "reply" : "dm", { text }, mode === "public" ? t("cm.toast.replied") : t("cm.toast.dm"))}
              className="inline-flex h-11 items-center gap-1.5 rounded-[11px] bg-ink px-4 text-[13px] font-semibold text-surface disabled:opacity-40"><Icon name="send" className="h-4 w-4 flip-rtl" />{t("inbox.send")}</button>
          </div>
          <div className="mt-2 flex justify-between text-[12px]">
            {c.status !== "IGNORED" ? <button type="button" onClick={() => void run("ignore")} className="font-semibold text-muted hover:text-ink">{t("cm.ignore")}</button> : <span />}
            {mode === "private" && c.dmStatus !== "SENT" && <button type="button" onClick={() => void run("dm", {}, t("cm.toast.dm"))} disabled={busy} className="font-semibold text-violet-700 hover:underline">{t("cm.aiDm", { price: money(msgPrice(settings), "EGP", locale) })}</button>}
          </div>
        </div>
      )}
    </div>
  ) : (
    <div className="grid h-full place-items-center bg-canvas p-8 text-center"><div><span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-[20px] bg-sky-50 text-sky-600"><Icon name="edit" className="h-7 w-7" /></span><p className="font-display text-[16px] font-semibold text-ink">{t("cm.pick")}</p></div></div>
  );

  return (
    <div className="flex min-h-0 flex-1">
      {(wide || !sel) && <div className={`${wide ? "w-[23rem] shrink-0 border-e border-line" : "w-full"} min-h-0`}>{list}</div>}
      {(wide || sel) && <div className="min-h-0 min-w-0 flex-1">{detail}</div>}
      <Sheet open={settingsOpen} onClose={() => setSettingsOpen(false)} title={t("cm.automation")}>
        {settings && <CommentSettings value={settings} onSave={saveSettings} canWrite={write} />}
      </Sheet>
    </div>
  );
}

/** Always-visible state of the comment automation: public reply mode, private message, today's usage and the price line; Edit opens the full settings. */
function AutoReplyCard({ s, write, onToggle, onDm, onEdit }: { s: Settings; write: boolean; onToggle: (on: boolean) => void; onDm: (v: boolean) => void; onEdit: () => void }) {
  const { t, locale } = useI18n();
  const on = s.publicReply !== "OFF";
  const nf = (n: number) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const price = money(msgPrice(s), "EGP", locale);
  const hasToday = s.todayReplies != null || s.todayDms != null || s.todayCostMinor != null;
  return (
    <div className="shrink-0 border-b border-line p-3">
      <div className="rounded-[16px] bg-[linear-gradient(135deg,rgb(124_92_255/.10),rgb(34_211_238/.10))] p-3.5 ring-1 ring-violet-200/70">
        <div className="flex items-center gap-2.5">
          <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[11px] bg-[linear-gradient(135deg,#7c5cff,#22d3ee)] text-white"><Icon name="sparkles" className="h-[18px] w-[18px]" /></span>
          <div className="min-w-0 flex-1">
            <p className="truncate font-display text-[14px] font-semibold text-ink">{t("cm.card.title")}</p>
            <p className={`text-[12px] font-semibold ${on ? "text-emerald-700" : "text-muted"}`}>{on ? t("cm.card.on") : t("cm.card.off")}</p>
          </div>
          <Switch checked={on} onChange={onToggle} disabled={!write} label={t("cm.card.title")} />
        </div>
        <dl className="mt-3 hidden grid-cols-2 gap-2 text-[12px] sm:grid">
          <div className="rounded-[11px] bg-surface/80 px-2.5 py-2 ring-1 ring-line"><dt className="text-faint">{t("cm.card.public")}</dt><dd className="mt-0.5 font-semibold text-ink">{t(`cm.pr.${s.publicReply}`)}</dd></div>
          <div className="rounded-[11px] bg-surface/80 px-2.5 py-2 ring-1 ring-line">
            <dt className="flex items-center justify-between gap-1 text-faint">{t("cm.card.dm")}<Switch size="sm" checked={s.dmEnabled} onChange={onDm} disabled={!write} label={t("cm.card.dm")} /></dt>
            <dd className="mt-0.5 font-semibold text-ink">{s.dmEnabled ? t("cm.card.dmOn") : t("cm.card.dmOff")}</dd>
          </div>
        </dl>
        {hasToday && <p className="mt-2.5 text-[12px] font-medium text-ink-2 tnum">{t("cm.card.today", { replies: nf(s.todayReplies ?? 0), dms: nf(s.todayDms ?? 0), cost: money(s.todayCostMinor ?? 0, "EGP", locale) })}</p>}
        <p className="mt-1.5 text-[11.5px] leading-relaxed text-muted">{t("cm.card.costLine", { price })}</p>
        <button type="button" onClick={onEdit} className="mt-3 inline-flex h-9 w-full items-center justify-center gap-1.5 rounded-[10px] bg-ink px-3 text-[13px] font-semibold text-surface"><Icon name="edit" className="h-4 w-4" />{t("cm.card.edit")}</button>
      </div>
    </div>
  );
}

function CommentSettings({ value, onSave, canWrite }: { value: Settings; onSave: (s: Settings) => Promise<void>; canWrite: boolean }) {
  const { t, locale } = useI18n();
  const [s, setS] = useState(value);
  const price = money(msgPrice(value), "EGP", locale);
  const option = (m: Settings["publicReply"], title: string, body: string, badge?: string) => (
    <button type="button" onClick={() => setS({ ...s, publicReply: m })} disabled={!canWrite}
      className={`w-full rounded-[14px] p-3.5 text-start ring-1 transition ${s.publicReply === m ? "bg-brand-soft/60 ring-2 ring-brand" : "bg-surface ring-line hover:ring-line-strong"}`}>
      <span className="flex items-center justify-between gap-2"><span className="text-[13.5px] font-semibold text-ink">{title}</span>{badge && <span className="rounded-full bg-canvas px-2 py-0.5 text-[11px] font-semibold text-muted ring-1 ring-line">{badge}</span>}</span>
      <span className="mt-0.5 block text-[12.5px] text-muted">{body}</span>
    </button>
  );
  return (
    <div className="space-y-6">
      <section className="space-y-2">
        <p className="text-[12px] font-semibold uppercase tracking-wide text-faint">{t("cm.publicReplies")}</p>
        {option("OFF", t("cm.pr.OFF"), t("cm.pr.OFFHint"))}
        {option("STAMP", t("cm.pr.STAMP"), t("cm.pr.STAMPHint"), t("cm.free"))}
        {s.publicReply === "STAMP" && <textarea value={s.stampText ?? ""} onChange={(e) => setS({ ...s, stampText: e.target.value })} maxLength={300} rows={2} placeholder={t("cm.stampPh")} className="w-full rounded-[12px] bg-canvas px-3 py-2 text-[13.5px] outline-none ring-1 ring-line focus:ring-2 focus:ring-brand/30" />}
        {option("AI", t("cm.pr.AI"), t("cm.pr.AIHint"), t("cm.perReply", { price }))}
      </section>
      <section className="space-y-3 rounded-[14px] bg-canvas p-4 ring-1 ring-line">
        <div className="flex items-start justify-between gap-3">
          <div><p className="text-[13.5px] font-semibold text-ink">{t("cm.dmTitle")}</p><p className="mt-0.5 text-[12.5px] text-muted">{t("cm.dmHint", { price })}</p></div>
          <Switch checked={s.dmEnabled} onChange={(v) => setS({ ...s, dmEnabled: v })} disabled={!canWrite} label={t("cm.dmTitle")} />
        </div>
        {s.dmEnabled && <label className="flex items-center justify-between gap-3 text-[13px] text-ink-2">{t("cm.dmOnlyQuestions")}<Switch size="sm" checked={s.dmOnlyQuestions} onChange={(v) => setS({ ...s, dmOnlyQuestions: v })} disabled={!canWrite} /></label>}
      </section>
      <p className="rounded-[12px] bg-amber-50 px-3 py-2.5 text-[12px] leading-relaxed text-amber-900">{t("cm.metaNote")}</p>
      {canWrite && <button type="button" onClick={() => void onSave(s)} className="w-full rounded-[12px] bg-ink py-2.5 text-[13.5px] font-semibold text-surface">{t("common.save")}</button>}
    </div>
  );
}

function Tag({ cls, children }: { cls: string; children: React.ReactNode }) {
  return <span className={`inline-flex items-center rounded-[6px] px-1.5 py-px text-[10.5px] font-semibold ring-1 ring-inset ${cls}`}>{children}</span>;
}
