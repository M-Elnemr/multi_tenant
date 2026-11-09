"use client";

import { Suspense, useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { AnimatePresence, motion } from "motion/react";
import { api, ApiError } from "@/lib/client";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Composer } from "@/components/inbox/composer";
import { CustomerPanel } from "@/components/inbox/customer-panel";
import { ConversationList } from "@/components/inbox/list";
import { Thread } from "@/components/inbox/thread";
import type { ChatDetail, SavedReply } from "@/components/inbox/types";
import { useChat, useChatList, useCounts, useFilters } from "@/components/inbox/use-inbox";

const mq = (q: string) => (cb: () => void) => { const m = window.matchMedia(q); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); };
function useMedia(q: string, server = false) { return useSyncExternalStore(mq(q), () => window.matchMedia(q).matches, () => server); }

export default function InboxPage() {
  return <Suspense fallback={null}><Inbox /></Suspense>;
}

/**
 * The unified inbox. Desktop: conversations | the chat | the customer (collapsible). Tablet: the customer opens as a side sheet. Phone: the list is the screen, a chat
 * opens full screen (?id=), the customer and the filters open as bottom sheets. Keyboard: j / k next / previous, / search, r reply, t take over / resume, e close, Esc back.
 */
function Inbox() {
  const { t } = useI18n();
  const toast = useToast();
  const { canWrite } = useMe();
  const write = canWrite("ai");
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const id = params.get("id");
  const wide = useMedia("(min-width: 1024px)", true);
  const xl = useMedia("(min-width: 1280px)", true);
  const { filters, set: setFilters, active } = useFilters();
  const list = useChatList(filters);
  const { chat, setChat, reload } = useChat(id);
  const { counts, reload: reloadCounts } = useCounts();
  const saved = useApi<SavedReply[]>("store/chat/saved-replies");
  const [panel, setPanel] = useState(true);
  const [sheet, setSheet] = useState(false);
  const [summary, setSummary] = useState<{ open: boolean; text?: string; loading?: boolean }>({ open: false });
  const [olderLoading, setOlderLoading] = useState(false);

  const select = useCallback((next: string | null) => {
    const p = new URLSearchParams(params.toString());
    if (next) p.set("id", next); else p.delete("id");
    router.replace(`${pathname}?${p.toString()}`, { scroll: false });
  }, [params, router, pathname]);

  // desktop: open the first chat when none is picked
  useEffect(() => { if (wide && !id && list.rows && list.rows.length > 0) select(list.rows[0].id); }, [wide, id, list.rows, select]);
  // opening a chat marks it read in the list at once
  useEffect(() => { if (id) list.patch(id, { unread: false, unreadCount: 0 }); }, [id, chat?.id]);   // eslint-disable-line react-hooks/exhaustive-deps

  const act = useCallback(async (path: string, optimistic: Partial<ChatDetail>, done?: string, body?: unknown) => {
    if (!chat) return;
    const before = chat;
    setChat({ ...chat, ...optimistic });
    list.patch(chat.id, optimistic);
    try {
      const r = await api<ChatDetail | undefined>(`store/chat/conversations/${chat.id}/${path}`, { body: body ?? {} });
      if (r && typeof r === "object" && "id" in r) setChat(r);
      if (done) toast(done);
      void list.reload(true); void reloadCounts();
    } catch (e) {
      setChat(before);
      list.patch(before.id, before);
      toast(e instanceof ApiError ? e.message : t("inbox.actionFailed"), { tone: "error" });
    }
  }, [chat, setChat, list, toast, t, reloadCounts]);

  const takeover = useCallback(() => act("takeover", { takeover: true, aiActive: false, statuses: [...(chat?.statuses ?? []).filter((s) => s !== "TAKEOVER"), "TAKEOVER"] }, t("inbox.toast.takeover")), [act, chat, t]);
  const resume = useCallback(() => act("resume", { takeover: false, aiActive: !!chat?.aiChannelOn, aiPaused: false, statuses: (chat?.statuses ?? []).filter((s) => s !== "TAKEOVER") }, t("inbox.toast.resume")), [act, chat, t]);
  const close = useCallback(async () => {
    if (!chat) return;
    const cid = chat.id;
    await act("close", { status: "CLOSED" });
    toast(t("inbox.toast.closed"), { action: { label: t("inbox.undo"), run: () => void api(`store/chat/conversations/${cid}/reopen`, { body: {} }).then(() => { void reload(); void list.reload(true); }) } });
  }, [act, chat, toast, t, reload, list]);
  const reopen = useCallback(() => act("reopen", { status: "OPEN" }, t("inbox.toast.reopened")), [act, t]);
  const unhappy = useCallback((on: boolean) => act("unhappy", { unhappy: on }, on ? t("inbox.toast.unhappyOn") : t("inbox.toast.unhappyOff"), { on }), [act, t]);
  const unread = useCallback(async () => {
    if (!chat) return;
    await api(`store/chat/conversations/${chat.id}/unread`, { body: {} }).catch(() => undefined);
    list.patch(chat.id, { unread: true });
    select(null);
  }, [chat, list, select]);

  const move = useCallback((dir: 1 | -1) => {
    const rows = list.rows ?? [];
    const i = rows.findIndex((r) => r.id === id);
    const next = rows[i + dir] ?? (i < 0 ? rows[0] : undefined);
    if (next) select(next.id);
  }, [list.rows, id, select]);

  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      if (["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName) || el.isContentEditable || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === "j") { e.preventDefault(); move(1); }
      else if (e.key === "k") { e.preventDefault(); move(-1); }
      else if (e.key === "t" && chat && write) { e.preventDefault(); void (chat.takeover ? resume() : takeover()); }
      else if (e.key === "e" && chat && write && chat.status === "OPEN") { e.preventDefault(); void close(); }
      else if (e.key === "Escape" && !wide && id) select(null);
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [move, chat, write, resume, takeover, close, wide, id, select]);

  const send = async (text: string, imageFileId: string | null) => {
    if (!chat) return;
    const r = await api<ChatDetail>(`store/chat/conversations/${chat.id}/reply`, { body: { message: text, imageFileId: imageFileId ?? undefined } });
    setChat(r);
    if (r.delivery === "FAILED") toast(r.deliveryNote ?? t("inbox.sendFailed"), { tone: "error", ms: 7000 });
    else toast(r.delivery === "SAVED" ? t("inbox.toast.savedWeb") : t("inbox.toast.sent"));
    void list.reload(true); void reloadCounts();
  };
  const note = async (text: string) => {
    if (!chat) return;
    await api(`store/chat/conversations/${chat.id}/notes`, { body: { body: text } });
    void reload(true);
    toast(t("inbox.toast.note"));
  };
  const deleteNote = async (noteId: number) => {
    if (!chat) return;
    await api(`store/chat/conversations/${chat.id}/notes/${noteId}`, { method: "DELETE" }).catch(() => undefined);
    void reload(true);
  };
  const summarize = async () => {
    if (!chat) return;
    setSummary({ open: true, loading: true });
    try {
      const r = await api<{ text: string }>(`store/chat/conversations/${chat.id}/assist`, { body: { action: "SUMMARY" } });
      setSummary({ open: true, text: r.text });
    } catch (e) { setSummary({ open: true, text: e instanceof ApiError ? e.message : t("inbox.assistFailed") }); }
  };
  const older = async () => {
    if (!chat || chat.messages.length === 0) return;
    setOlderLoading(true);
    try {
      const r = await api<ChatDetail>(`store/chat/conversations/${chat.id}?before=${chat.messages[0].id}&read=false`);
      setChat({ ...chat, messages: [...r.messages, ...chat.messages], hasOlder: r.hasOlder, events: [...r.events.filter((e) => !chat.events.some((x) => x.id === e.id)), ...chat.events] });
    } finally { setOlderLoading(false); }
  };
  const bulk = async (ids: string[], action: string) => {
    try {
      const r = await api<{ changed: number }>("store/chat/conversations/bulk", { body: { ids, action } });
      toast(t("inbox.toast.bulk", { n: r.changed }));
      void list.reload(true); void reloadCounts(); if (id && ids.includes(id)) void reload(true);
    } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.actionFailed"), { tone: "error" }); }
  };

  const rows = list.rows ?? [];
  const idx = rows.findIndex((r) => r.id === id);
  const showList = wide || !id;
  const showThread = wide || !!id;
  const panelInline = xl && panel && !!chat;

  const listEl = (
    <ConversationList rows={list.rows} total={list.total} hasMore={list.hasMore} loadingMore={list.loadingMore} onMore={list.more} filters={filters} setFilters={setFilters}
      activeFilters={active} selected={id} onSelect={(x) => select(x)} counts={counts} canWrite={write} onBulk={bulk} phone={!wide} />
  );
  const threadEl = chat ? (
    <Thread key={chat.id} chat={chat} onBack={wide ? undefined : () => select(null)} panelOpen={xl ? panel : false} onTogglePanel={() => (xl ? setPanel((v) => !v) : setSheet(true))} canWrite={write}
      actions={{ takeover, resume, close, reopen, unread, unhappy, prev: idx > 0 ? () => move(-1) : undefined, next: idx >= 0 && idx < rows.length - 1 ? () => move(1) : undefined }}
      onOlder={older} olderLoading={olderLoading} onDeleteNote={deleteNote}
      composer={<Composer key={chat.id} chat={chat} canWrite={write} onSent={send} onNote={note} onTakeover={takeover} saved={saved.data ?? []} onSummarize={summarize} />} />
  ) : id ? (
    <div className="flex h-full flex-col bg-canvas"><div className="flex items-center gap-3 border-b border-line bg-surface px-5 py-3.5"><div className="skeleton h-10 w-10 rounded-full" /><div className="space-y-2"><div className="skeleton h-3.5 w-40 rounded" /><div className="skeleton h-3 w-24 rounded" /></div></div>
      <div className="flex-1 space-y-3 p-6">{[60, 40, 70, 30].map((w, i) => <div key={i} className={`flex ${i % 2 ? "justify-end" : ""}`}><div className="skeleton h-12 rounded-[18px]" style={{ width: `${w}%` }} /></div>)}</div></div>
  ) : (
    <div className="grid h-full place-items-center bg-canvas p-8 text-center">
      <div><span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-[20px] bg-[linear-gradient(135deg,rgb(124_92_255/.14),rgb(34_211_238/.14))] text-violet-600"><Icon name="chat" className="h-7 w-7" /></span>
        <p className="font-display text-[16px] font-semibold text-ink">{t("inbox.pickTitle")}</p><p className="mt-1 text-[13px] text-muted">{t("inbox.pickHint")}</p></div>
    </div>
  );

  return (
    <div className="flex min-h-0 flex-1">
      {showList && <div className={`${wide ? "w-[22rem] shrink-0 border-e border-line xl:w-[23.5rem]" : "w-full"} min-h-0`}>{listEl}</div>}
      {showThread && <div className="min-h-0 min-w-0 flex-1">{threadEl}</div>}
      <AnimatePresence initial={false}>
        {panelInline && chat && (
          <motion.div key="cp" initial={{ width: 0, opacity: 0 }} animate={{ width: 340, opacity: 1 }} exit={{ width: 0, opacity: 0 }} transition={{ duration: 0.24, ease: [0.2, 0.8, 0.2, 1] }} className="min-h-0 shrink-0 overflow-hidden border-s border-line">
            <div className="h-full w-[340px]"><CustomerPanel key={chat.id} chat={chat} onClose={() => setPanel(false)} onChanged={() => void reload(true)} canWrite={write} /></div>
          </motion.div>
        )}
      </AnimatePresence>
      {!xl && chat && (
        <Sheet open={sheet} onClose={() => setSheet(false)} width="23rem" label={t("inbox.p.details")}>
          <div className="-mx-5 -my-4 h-[70dvh] sm:h-[calc(100dvh-2rem)]"><CustomerPanel key={chat.id} chat={chat} onClose={() => setSheet(false)} onChanged={() => void reload(true)} canWrite={write} /></div>
        </Sheet>
      )}
      <Sheet open={summary.open} onClose={() => setSummary({ open: false })} title={t("inbox.summaryTitle")}>
        {summary.loading ? <div className="space-y-2">{[90, 75, 85].map((w, i) => <div key={i} className="skeleton h-3.5 rounded" style={{ width: `${w}%` }} />)}</div>
          : <><p className="whitespace-pre-wrap text-[14px] leading-relaxed text-ink-2" dir="auto">{summary.text}</p><p className="mt-4 flex items-center gap-1.5 text-[11.5px] text-faint"><Icon name="sparkles" className="h-3.5 w-3.5" />{t("inbox.summaryNote")}</p></>}
      </Sheet>
    </div>
  );
}
