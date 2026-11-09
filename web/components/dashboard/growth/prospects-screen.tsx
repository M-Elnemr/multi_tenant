"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { dateTime, money } from "@/lib/format";
import { ChannelIcon } from "../../channel-icons";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { ConfirmDialog, Checkbox } from "../../overlay";
import { useToast } from "../../toast";
import { Alert, Badge, Button, Empty, ErrorText, Input } from "../../ui";
import { AI_GRADIENT, Bar, Panel } from "../marketing/kit";
import ConnectStrip, { isConnected, useConnections } from "./connect";
import ComposerStep, { type Draft } from "./composer";
import Queue from "./queue";
import Summary from "./summary";
import { CONNECT_LINK, PROSPECT_PLATFORMS, PROSPECT_SOURCES, type Broadcast, type ProspectsRes, type Quote, type SyncStatus } from "./types";
import { usePoll } from "./use-poll";

const EMPTY: Draft = { msg: "", image: null, versions: [], push: false, pushTitle: "" };
const PAGE = 50;
const PRICE = 10;
const toggle = (list: string[], k: string) => (list.includes(k) ? list.filter((x) => x !== k) : [...list, k]);
const chip = (on: boolean) => `inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[12.5px] font-semibold ring-1 transition ${on ? "bg-ink text-surface ring-ink" : "bg-surface text-ink-2 ring-line hover:ring-line-strong"}`;

const STATUS_TONE: Record<string, "slate" | "blue" | "green" | "amber" | "red"> = { NEW: "slate", CONTACTED: "blue", REPLIED: "green", OPTED_OUT: "amber", BLOCKED: "red", NOT_FOUND: "red" };

/** New customers targeting: prospects pulled from the shop's own Instagram / Messenger audience (followers, commenters). */
export default function ProspectsScreen() {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const conns = useConnections();
  const [plats, setPlats] = useState<string[]>([...PROSPECT_PLATFORMS]);
  const [sources, setSources] = useState<string[]>([...PROSPECT_SOURCES]);
  const [q, setQ] = useState("");
  const [qNow, setQNow] = useState("");
  const [offset, setOffset] = useState(0);
  // selection: explicit ids, or "all matching the filters" minus the unticked ones
  const [ids, setIds] = useState<string[]>([]);
  const [allMode, setAllMode] = useState(false);
  const [excluded, setExcluded] = useState<string[]>([]);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => { const id = setTimeout(() => { setQNow(q.trim()); setOffset(0); }, 350); return () => clearTimeout(id); }, [q]);

  const platParam = plats.length === 1 ? `&platform=${plats[0]}` : "";
  const srcParam = sources.length === 1 ? `&source=${sources[0]}` : "";
  const list = useApi<ProspectsRes>(plats.length && sources.length ? `store/prospects?limit=${PAGE}&offset=${offset}&q=${encodeURIComponent(qNow)}${platParam}${srcParam}` : null);
  const hist = useApi<Broadcast[]>("store/broadcasts");

  // ---- sync -------------------------------------------------------------------------------------------------------------
  const [sync, setSync] = useState<Record<string, SyncStatus>>({});
  const wasRunning = useRef(false);
  const running = Object.values(sync).some((s) => s.status === "RUNNING");
  const loadSync = async () => {
    const entries = await Promise.all(PROSPECT_PLATFORMS.map(async (p) => [p, await api<SyncStatus>(`store/prospects/sync/status?platform=${p}`).catch(() => null)] as const));
    const next: Record<string, SyncStatus> = {};
    for (const [p, s] of entries) if (s) next[p] = s;
    setSync(next);
    const now = Object.values(next).some((s) => s.status === "RUNNING");
    if (wasRunning.current && !now) void list.reload();
    wasRunning.current = now;
  };
  useEffect(() => { const id = setTimeout(() => { void loadSync(); }, 0); return () => clearTimeout(id); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  usePoll(running, () => { void loadSync(); }, 3000);
  const startSync = useAction(async () => {
    const targets = plats.filter((p) => isConnected(conns.data?.[p as "INSTAGRAM" | "FACEBOOK"]));
    await Promise.all(targets.map((p) => api(`store/prospects/sync`, { body: { platform: p } })));
    wasRunning.current = true;
    await loadSync();
  });
  const connectedPlats = plats.filter((p) => isConnected(conns.data?.[p as "INSTAGRAM" | "FACEBOOK"]));
  const activeSync = plats.map((p) => sync[p]).filter((s): s is SyncStatus => !!s);
  const fetched = activeSync.reduce((a, s) => a + (s.status === "RUNNING" ? s.fetched : 0), 0);
  const total = activeSync.reduce((a, s) => a + (s.status === "RUNNING" ? Math.max(s.total, s.fetched) : 0), 0);
  const lastSynced = activeSync.map((s) => s.lastSyncedAt).filter(Boolean).sort().at(-1);
  const syncErr = plats.map((p) => sync[p]).find((s) => s?.status === "FAILED" && s.error)?.error;
  const errText = (code: string) => { const k = `error.${code}`; const s = t(k); return s === k ? code : s; };

  // ---- selection --------------------------------------------------------------------------------------------------------
  const resetSel = () => { setIds([]); setAllMode(false); setExcluded([]); setDone(null); };
  const rows = list.data?.items ?? [];
  const totalRows = list.data?.total ?? 0;
  const selectedCount = allMode ? Math.max(0, totalRows - excluded.length) : ids.length;
  const checked = (id: string) => (allMode ? !excluded.includes(id) : ids.includes(id));
  const flip = (id: string) => { if (allMode) setExcluded(toggle(excluded, id)); else setIds(toggle(ids, id)); setDone(null); };
  const body = () => ({
    platforms: plats, sources, q: qNow || undefined, message: draft.msg, imageFileId: draft.image ?? undefined,
    variants: draft.versions.map((v) => v.trim()).filter((v) => v.length >= 3),
    ...(allMode ? { all: true, excludeIds: excluded.length ? excluded : undefined } : { ids }),
  });

  // ---- quote (debounced) ------------------------------------------------------------------------------------------------
  const [quote, setQuote] = useState<Quote | null>(null);
  const quoteKey = JSON.stringify({ plats, sources, qNow, ids, allMode, excluded, msg: draft.msg.length >= 3 });
  useEffect(() => {
    if (selectedCount === 0 || !plats.length) return;
    let off = false;
    const id = setTimeout(() => {
      api<Quote>("store/prospects/broadcasts/quote", { body: { ...body(), message: draft.msg.trim() || "-" } }).then((r) => { if (!off) setQuote(r); }).catch(() => { if (!off) setQuote(null); });
    }, 400);
    return () => { off = true; clearTimeout(id); };
  }, [quoteKey]); // eslint-disable-line react-hooks/exhaustive-deps
  const q0 = selectedCount === 0 ? null : quote;

  const send = useAction(async () => {
    const r = await api<{ id: string; audience?: number; reachable?: number; costMinor?: number; etaMinutes?: number }>("store/prospects/broadcasts", { body: body() });
    const text = t("gx.sentResult", { n: r.reachable ?? r.audience ?? selectedCount, cost: money(r.costMinor ?? q0?.costMinor ?? 0, currency, locale), eta: r.etaMinutes ?? q0?.etaMinutes ?? 1 });
    setDone(text); toast(text);
    setDraft(EMPTY); resetSel();
    await Promise.all([hist.reload(), list.reload()]);
  });
  const tick = () => { void hist.reload(); };

  const counts = list.data?.counts ?? {};
  const platCount = (p: string) => Object.values(counts[p] ?? {}).reduce((a, b) => a + b, 0);
  const srcCount = (s: string) => plats.reduce((a, p) => a + (counts[p]?.[s] ?? 0), 0);
  const pagesFrom = offset + 1;
  const pagesTo = Math.min(offset + PAGE, totalRows);
  const ready = draft.msg.trim().length >= 3 && selectedCount > 0 && plats.length > 0;
  const noneConnected = conns.data && !isConnected(conns.data.INSTAGRAM) && !isConnected(conns.data.FACEBOOK);

  const lines = (
    <>
      {plats.map((p) => (
        <div key={p} className="flex items-baseline justify-between gap-3 py-1.5 text-[13px]">
          <span className="text-muted">{t(`gx.plat.${p}`)}</span>
          <span className="inline-flex items-center gap-1.5 font-medium text-ink-2 tnum"><ChannelIcon channel={p} className="h-3.5 w-3.5" />{n(platCount(p))}</span>
        </div>
      ))}
      <div className="flex items-baseline justify-between gap-3 py-1.5 text-[13px]"><span className="text-muted">{t("px.selected")}</span><span className="font-medium text-ink-2 tnum">{n(selectedCount)}</span></div>
    </>
  );

  return (
    <div className="space-y-5">
      <ConnectStrip conns={conns.data} showImport={false} onChanged={() => { void conns.reload(); void loadSync(); }} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] lg:items-start">
        <Panel className="space-y-6 p-4 sm:p-6">
          <div className="flex items-center gap-3">
            <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-[12px] text-white ${AI_GRADIENT}`}><Icon name="users" className="h-5 w-5" /></span>
            <div className="min-w-0"><h2 className="font-display text-[17px] font-semibold text-ink">{t("px.title")}</h2><p className="text-[12.5px] text-muted">{t("px.subtitle")}</p></div>
          </div>

          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t("gx.pickPlatforms")}>
              {PROSPECT_PLATFORMS.map((p) => (
                <button key={p} type="button" aria-pressed={plats.includes(p)} className={chip(plats.includes(p))} onClick={() => { setPlats(toggle(plats, p)); setOffset(0); resetSel(); }}>
                  <ChannelIcon channel={p} className="h-4 w-4" />{t(`gx.plat.${p}`)}<span className="tnum opacity-70">{n(platCount(p))}</span>
                </button>
              ))}
              <span className="mx-1 h-5 w-px bg-line" aria-hidden />
              {PROSPECT_SOURCES.map((s) => (
                <button key={s} type="button" aria-pressed={sources.includes(s)} className={chip(sources.includes(s))} onClick={() => { setSources(toggle(sources, s)); setOffset(0); resetSel(); }}>
                  {t(`px.src.${s}`)}<span className="tnum opacity-70">{n(srcCount(s))}</span>
                </button>
              ))}
            </div>
            <div className="relative">
              <Icon name="search" className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint" />
              <Input className="ps-9" value={q} onChange={(e) => { setQ(e.target.value); resetSel(); }} placeholder={t("px.search")} aria-label={t("px.search")} />
            </div>
          </div>

          <div className="space-y-2.5 rounded-[14px] bg-canvas p-3.5 ring-1 ring-line">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="min-w-0">
                <p className="text-[13.5px] font-semibold text-ink">{t("px.syncTitle")}</p>
                <p className="text-[12px] text-muted">{lastSynced ? t("px.lastSync", { when: dateTime(lastSynced, locale) }) : t("px.neverSynced")}</p>
              </div>
              <Button size="sm" variant="ink" loading={startSync.loading || running} disabled={connectedPlats.length === 0} onClick={() => void startSync.run()}><Icon name="return" className="h-4 w-4" />{t("px.syncNow")}</Button>
            </div>
            {running && (
              <div className="space-y-1">
                <Bar value={fetched} max={Math.max(1, total)} />
                <p className="text-[12px] text-muted tnum">{t("px.syncing", { fetched: n(fetched), total: total > 0 ? n(total) : "?" })}</p>
              </div>
            )}
            {syncErr && <Alert tone="red">{errText(syncErr)}</Alert>}
            <ErrorText error={startSync.error} />
          </div>

          {list.error && <ErrorText error={list.error} />}
          {!list.data && !list.error && plats.length > 0 && sources.length > 0 && <div className="space-y-2">{[0, 1, 2, 3].map((i) => <div key={i} className="skeleton h-11 rounded-[10px]" />)}</div>}
          {list.data && rows.length === 0 && (
            <Empty icon="users" title={t("px.emptyTitle")} action={noneConnected || connectedPlats.length === 0 ? <a href={CONNECT_LINK} className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-brand hover:underline"><Icon name="link" className="h-4 w-4" />{t("px.emptyConnect")}</a> : undefined}>
              {t(plats.length === 1 && plats[0] === "FACEBOOK" ? "px.emptyBodyFb" : "px.emptyBody")}
            </Empty>
          )}
          {rows.length > 0 && (
            <div className="space-y-2">
              <div className="flex items-center gap-2.5 px-1 text-[12.5px] text-muted">
                <Checkbox checked={allMode && excluded.length === 0} indeterminate={selectedCount > 0 && !(allMode && excluded.length === 0)} label={t("px.selectAll")}
                  onChange={() => { if (allMode && excluded.length === 0) resetSel(); else { setAllMode(true); setExcluded([]); setIds([]); setDone(null); } }} />
                <span className="font-semibold text-ink-2">{t("px.selectAllN", { n: n(totalRows) })}</span>
                <span className="ms-auto tnum">{t("px.selected")}: {n(selectedCount)}</span>
              </div>
              <ul className="space-y-1.5">
                {rows.map((r) => (
                  <li key={r.id} className={`flex items-center gap-2.5 rounded-[10px] px-3 py-2 text-[13px] ring-1 ${checked(r.id) ? "bg-surface-2 ring-line-strong" : "bg-surface ring-line"}`}>
                    <Checkbox checked={checked(r.id)} onChange={() => flip(r.id)} label={r.username ?? r.displayName ?? ""} />
                    <ChannelIcon channel={r.platform} className="h-4 w-4 shrink-0" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium text-ink" dir="auto">{r.displayName || r.username || "-"}</span>
                      {r.username && <span className="block truncate text-[11.5px] text-muted" dir="ltr">@{r.username}</span>}
                    </span>
                    <span className="hidden shrink-0 text-[12px] text-muted sm:block">{t(`px.src.${r.source}`)}{r.postRef ? ` · ${r.postRef}` : ""}</span>
                    <Badge tone={STATUS_TONE[r.status] ?? "slate"}>{t(`px.st.${r.status}`)}</Badge>
                  </li>
                ))}
              </ul>
              {totalRows > PAGE && (
                <div className="flex items-center justify-between gap-2 pt-1 text-[12px] text-muted tnum">
                  <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}><Icon name="arrow" className="h-4 w-4 rotate-180 flip-rtl" />{t("common.prev")}</Button>
                  <span>{n(pagesFrom)}-{n(pagesTo)} / {n(totalRows)}</span>
                  <Button size="sm" variant="secondary" disabled={offset + PAGE >= totalRows} onClick={() => setOffset(offset + PAGE)}>{t("common.next")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Button>
                </div>
              )}
            </div>
          )}

          <div className="space-y-3">
            <div className="min-w-0"><h3 className="font-display text-[15px] font-semibold text-ink">{t("grx.step2")}</h3><p className="text-[12.5px] text-muted">{t("grx.step2Hint")}</p></div>
            <ComposerStep draft={draft} onDraft={setDraft} noPush />
          </div>
        </Panel>

        <div className="space-y-4 lg:sticky lg:top-4">
          <Summary msg={draft.msg} image={draft.image} plats={plats} byPlatform={null} extraLines={lines}
            reachable={q0?.reachable ?? 0} outsideWindow={0} costMinor={q0?.costMinor ?? 0} etaMinutes={q0?.etaMinutes ?? 1} priceMinor={PRICE}
            balanceMinor={q0?.balanceMinor ?? 0} ownLinked ready={ready} sending={send.loading} error={send.error}
            onSend={() => { setDone(null); setConfirm(true); }} />
          {done && <Alert tone="green">{done}</Alert>}
        </div>
      </div>

      <Queue list={(hist.data ?? []).filter((b) => b.kind === "PROSPECTS")} balanceMinor={q0?.balanceMinor ?? 0} onTick={tick} />

      <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} loading={send.loading}
        onConfirm={() => { setConfirm(false); void send.run(); }}
        title={t("grx.confirmTitle")} body={<>{t("px.confirmBody", { n: q0?.reachable ?? selectedCount })}<span className="mt-1 block font-semibold text-ink">{money(q0?.costMinor ?? 0, currency, locale)}</span></>}
        confirmLabel={t("gr.send")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
