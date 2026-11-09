"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { ConfirmDialog } from "../../overlay";
import { useToast } from "../../toast";
import { Alert } from "../../ui";
import { AI_GRADIENT, Panel } from "../marketing/kit";
import AudiencePicker from "./audience";
import ClientList from "./clients";
import ComposerStep, { type Draft } from "./composer";
import Queue from "./queue";
import ConnectStrip, { useConnections } from "./connect";
import Summary from "./summary";
import { PLATFORMS, csv, type Broadcast, type ClientsRes, type SegmentsRes, type SendResult } from "./types";

function Step({ n, title, hint, children }: { n: number; title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="space-y-3">
      <div className="flex items-center gap-2.5">
        <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-ink text-[12px] font-bold text-surface tnum">{n}</span>
        <div className="min-w-0"><h3 className="font-display text-[15px] font-semibold text-ink">{title}</h3>{hint && <p className="text-[12.5px] text-muted">{hint}</p>}</div>
      </div>
      {children}
    </section>
  );
}

const EMPTY: Draft = { msg: "", image: null, versions: [], push: false, pushTitle: "" };

/** Customer segments: pick groups and platforms, review the clients, write the message, send, and follow the live queue. */
export default function SegmentsScreen() {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const [segs, setSegs] = useState<string[]>(["ALL"]);
  const [plats, setPlats] = useState<string[]>([...PLATFORMS]);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const seg = useApi<SegmentsRes>(`store/segments?platforms=${csv(PLATFORMS)}`);
  const um = useConnections();
  const totals = useApi<ClientsRes>(segs.length && plats.length ? `store/segments/customers?segments=${csv(segs)}&platforms=${csv(plats)}&limit=1&offset=0` : null);
  const hist = useApi<Broadcast[]>("store/broadcasts");

  const counts = seg.data ? Object.fromEntries(seg.data.segments.map((s) => [s.key, s.count])) : null;
  const price = seg.data?.messagePriceMinor ?? 10;
  const balance = seg.data?.balanceMinor ?? 0;
  const reachable = segs.length && plats.length ? (totals.data?.reachable ?? 0) : 0;
  // one message every 5 to 15 seconds: 10 seconds on average
  const eta = Math.ceil((reachable * 10) / 60);
  const ready = draft.msg.trim().length >= 3 && segs.length > 0 && plats.length > 0;

  const send = useAction(async () => {
    const r = await api<SendResult>("store/broadcasts", { body: {
      segments: segs, platforms: plats, message: draft.msg, imageFileId: draft.image ?? undefined,
      variants: draft.versions.map((v) => v.trim()).filter((v) => v.length >= 3), push: draft.push, pushTitle: draft.pushTitle || undefined,
    } });
    const text = t("gx.sentResult", { n: r.reachable, cost: money(r.costMinor, currency, locale), eta: r.etaMinutes });
    setDone(text); toast(text);
    setDraft(EMPTY);
    await Promise.all([hist.reload(), seg.reload(), totals.reload(), um.reload()]);
  });
  const tick = () => { void hist.reload(); void seg.reload(); };

  return (
    <div className="space-y-5">
      <ConnectStrip conns={um.data} onChanged={() => { void um.reload(); void seg.reload(); void totals.reload(); }} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] lg:items-start">
        <Panel className="space-y-7 p-4 sm:p-6">
          <div className="flex items-center gap-3">
            <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-[12px] text-white ${AI_GRADIENT}`}><Icon name="send" className="h-5 w-5 flip-rtl" /></span>
            <div className="min-w-0"><h2 className="font-display text-[17px] font-semibold text-ink">{t("gx.title")}</h2><p className="text-[12.5px] text-muted">{t("gx.subtitle")}</p></div>
          </div>
          <Step n={1} title={t("gx.step1")} hint={t("gx.step1Hint")}>
            <AudiencePicker counts={counts} byPlatform={seg.data?.byPlatform ?? null} ownLinked={seg.data?.ownNumberLinked ?? true}
              segs={segs} onSegs={(v) => { setSegs(v); setDone(null); }} plats={plats} onPlats={(v) => { setPlats(v); setDone(null); }}
              />
          </Step>
          <Step n={2} title={t("grx.step2")} hint={t("grx.step2Hint")}>
            <ComposerStep draft={draft} onDraft={setDraft} />
          </Step>
        </Panel>
        <div className="space-y-4 lg:sticky lg:top-4">
          <Summary msg={draft.msg} image={draft.image} plats={plats} byPlatform={seg.data?.byPlatform ?? null} reachable={reachable} outsideWindow={totals.data?.outsideWindow ?? 0}
            costMinor={reachable * price} etaMinutes={eta} priceMinor={price} balanceMinor={balance} ownLinked={seg.data?.ownNumberLinked ?? true} ready={ready} sending={send.loading} error={send.error}
            onSend={() => { setDone(null); setConfirm(true); }}
            />
          {done && <Alert tone="green">{done}</Alert>}
        </div>
      </div>

      <ClientList segs={segs} plats={plats} />
      <Queue list={(hist.data ?? []).filter((b) => b.kind !== "PROSPECTS")} balanceMinor={balance} onTick={tick} />

      <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} loading={send.loading}
        onConfirm={() => { setConfirm(false); void send.run(); }}
        title={t("grx.confirmTitle")} body={<>{t("gx.confirmBody", { segments: segs.map((s) => t(`gr.seg.${s}`)).join("، "), n: reachable })}<span className="mt-1 block font-semibold text-ink">{money(reachable * price, currency, locale)}</span></>}
        confirmLabel={t("gr.send")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
