"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import type { ChatDetail, ChatRow, Counts, Filters, Page } from "./types";
import { EMPTY } from "./types";

const STORE_KEY = "inboxFilters";

/** Filters live in the address (shareable, survive a refresh) and the last ones are remembered per browser. */
export function useFilters() {
  const params = useSearchParams();
  const router = useRouter();
  const fromUrl = useMemo<Filters | null>(() => {
    if (![...params.keys()].some((k) => ["status", "ai", "channel", "since", "customer", "q", "sort"].includes(k))) return null;
    const list = (k: string) => (params.get(k) ?? "").split(",").filter(Boolean);
    return { status: list("status") as Filters["status"], ai: list("ai") as Filters["ai"], channel: list("channel") as Filters["channel"], since: (params.get("since") ?? "") as Filters["since"],
      customer: list("customer") as Filters["customer"], q: params.get("q") ?? "", sort: (params.get("sort") ?? "newest") as Filters["sort"] };
  }, [params]);
  const [stored] = useState<Filters | null>(() => { try { const v = localStorage.getItem(STORE_KEY); return v ? { ...EMPTY, ...JSON.parse(v), q: "" } : null; } catch { return null; } });
  const [local, setLocal] = useState<Filters | null>(null);   // the latest choice right away (the address updates a moment later)
  const filters = local ?? fromUrl ?? stored ?? EMPTY;
  const set = useCallback((next: Filters) => {
    setLocal(next);
    const p = new URLSearchParams(params.toString());
    (["status", "ai", "channel", "customer"] as const).forEach((k) => { if (next[k].length) p.set(k, next[k].join(",")); else p.delete(k); });
    if (next.since) p.set("since", next.since); else p.delete("since");
    if (next.q) p.set("q", next.q); else p.delete("q");
    if (next.sort !== "newest") p.set("sort", next.sort); else p.delete("sort");
    if (![...p.keys()].some((k) => k !== "id")) p.set("sort", next.sort);   // keeps "cleared" from falling back to the remembered filters
    try { localStorage.setItem(STORE_KEY, JSON.stringify({ ...next, q: "" })); } catch { /* storage blocked */ }
    router.replace(`?${p.toString()}`, { scroll: false });
  }, [params, router]);
  const active = filters.status.length + filters.ai.length + filters.channel.length + filters.customer.length + (filters.since ? 1 : 0);
  return { filters, set, active };
}

export function query(f: Filters) {
  const p = new URLSearchParams({ page: "true", sort: f.sort });
  (["status", "ai", "channel", "customer"] as const).forEach((k) => { if (f[k].length) p.set(k, f[k].join(",")); });
  if (f.since) p.set("since", f.since);
  if (f.q.trim()) p.set("q", f.q.trim());
  return p.toString();
}

/** The list: first page, "load more", a quiet refresh every 10 s while the tab is visible, and local patches for optimistic actions. */
export function useChatList(filters: Filters) {
  const q = query(filters);
  const [state, setRows] = useState<{ q: string; rows: ChatRow[] } | null>(null);
  const rows = state && state.q === q ? state.rows : null;   // a new filter shows the skeleton until its first page arrives
  const [total, setTotal] = useState(0);
  const [cursor, setCursor] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const shown = useRef(30);
  const load = useCallback(async (quiet = false) => {
    try {
      const r = await api<Page<ChatRow>>(`store/chat/conversations?${q}&limit=${Math.min(100, Math.max(30, shown.current))}`);
      setRows({ q, rows: r.data }); setTotal(r.total); setCursor(r.nextCursor); setError(null);
    } catch (e) { if (!quiet) setError(e); }
  }, [q]);
  useEffect(() => { shown.current = 30; void load(); }, [load]);
  useEffect(() => {
    const id = setInterval(() => { if (!document.hidden) void load(true); }, 10_000);
    const vis = () => { if (!document.hidden) void load(true); };
    document.addEventListener("visibilitychange", vis);
    return () => { clearInterval(id); document.removeEventListener("visibilitychange", vis); };
  }, [load]);
  const more = useCallback(async () => {
    if (cursor == null || loadingMore) return;
    setLoadingMore(true);
    try {
      const r = await api<Page<ChatRow>>(`store/chat/conversations?${q}&cursor=${cursor}&limit=30`);
      setRows((x) => ({ q, rows: [...(x?.rows ?? []), ...r.data.filter((n) => !(x?.rows ?? []).some((o) => o.id === n.id))] })); setCursor(r.nextCursor); setTotal(r.total);
      shown.current += r.data.length;
    } finally { setLoadingMore(false); }
  }, [cursor, loadingMore, q]);
  const patch = useCallback((id: string, p: Partial<ChatRow>) => setRows((x) => (x ? { q: x.q, rows: x.rows.map((r) => (r.id === id ? { ...r, ...p } : r)) } : x)), []);
  return { rows, total, more, hasMore: cursor != null, loadingMore, reload: load, patch, error };
}

/** One chat: loaded when picked, refreshed every 4 s while visible (without marking it read again). */
export function useChat(id: string | null) {
  const [state, setState] = useState<ChatDetail | null>(null);
  const chat = state && state.id === id ? state : null;   // another chat shows its skeleton until it loads
  const setChat = useCallback((c: ChatDetail | null) => setState(c), []);
  const [error, setError] = useState<unknown>(null);
  const current = useRef(id);
  const load = useCallback(async (quiet = false) => {
    if (!id) return;
    try {
      const r = await api<ChatDetail>(`store/chat/conversations/${id}${quiet ? "?read=false" : ""}`);
      if (current.current === id) { setState(r); setError(null); }
    } catch (e) { if (!quiet) setError(e); }
  }, [id]);
  useEffect(() => {
    current.current = id;
    if (!id) return;
    const first = setTimeout(() => void load(), 0);   // the request starts right after this render
    return () => clearTimeout(first);
  }, [id, load]);
  useEffect(() => {
    if (!id) return;
    const t = setInterval(() => { if (!document.hidden) void load(true); }, 4_000);
    return () => clearInterval(t);
  }, [id, load]);
  return { chat, setChat, reload: load, error };
}

export function useCounts() {
  const [c, setC] = useState<Counts | null>(null);
  const load = useCallback(() => api<Counts>("store/chat/conversations/counts").then(setC).catch(() => undefined), []);
  useEffect(() => {
    void load();
    const t = setInterval(() => { if (!document.hidden) void load(); }, 20_000);
    return () => clearInterval(t);
  }, [load]);
  return { counts: c, reload: load };
}
