"use client";

import Link from "next/link";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ChannelIcon } from "@/components/channel-icons";
import { Alert, Badge, Button, ErrorText } from "@/components/ui";
import { CopyField } from "@/components/dashboard/marketing/kit";
import type { Account } from "./common";

type Channel = { id: string; platform: string; name?: string; status: string };
type Tele = { available: boolean; botUsername?: string | null; linked: boolean; channel?: { name?: string; status: string } | null };

function Dot({ on, label }: { on: boolean; label: string }) {
  return <li className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12.5px] font-medium ring-1 ${on ? "bg-emerald-50 text-emerald-800 ring-emerald-600/20" : "bg-canvas text-muted ring-line"}`}><span className={`h-1.5 w-1.5 rounded-full ${on ? "bg-emerald-500" : "bg-faint"}`} />{label}</li>;
}

/** One click on "Connect with Facebook" connects everything Meta offers: the Page, Instagram, WhatsApp, the Shop catalog and the ad account. */
export function FacebookCard({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const channels = useApi<Channel[]>("store/ai/channels");
  const accounts = useApi<Account[]>("store/integrations/accounts");
  const login = useApi<{ available: boolean }>("store/ai/meta/available");
  const start = useAction(async () => { const r = await api<{ url: string }>("store/ai/meta/start", { body: {} }); window.location.assign(r.url); });
  const has = (p: string) => (channels.data ?? []).some((c) => c.platform === p && c.status === "ACTIVE");
  const hasAcc = (p: string) => (accounts.data ?? []).some((a) => a.provider === p);
  const items = [["FACEBOOK", has("FACEBOOK")], ["INSTAGRAM", has("INSTAGRAM")], ["WHATSAPP", has("WHATSAPP")], ["CATALOG", hasAcc("META_CATALOG")], ["ADS", hasAcc("META_ADS")]] as const;
  const any = items.some(([, on]) => on);
  return (
    <section className="space-y-4 rounded-[18px] bg-surface p-5 ring-1 ring-line">
      <div className="flex flex-wrap items-center gap-3">
        <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-[#0866ff] text-white"><ChannelIcon channel="FACEBOOK" className="h-6 w-6 [&_svg]:text-white" /></span>
        <div className="min-w-0 flex-1"><h3 className="font-display text-[16px] font-semibold text-ink">{t("hub.fb.title")}</h3><p className="text-[13px] text-muted">{t("hub.fb.sub")}</p></div>
        {canManage && <Button onClick={() => void start.run()} loading={start.loading} disabled={login.data ? !login.data.available : false}>{any ? t("hub.fb.again") : t("ai.loginFacebook")}</Button>}
      </div>
      <ul className="flex flex-wrap gap-2">{items.map(([k, on]) => <Dot key={k} on={on} label={t(`hub.fb.item.${k}`)} />)}</ul>
      {login.data && !login.data.available && <Alert tone="amber">{t("ai.loginNotReady")}</Alert>}
      <p className="text-[12.5px] text-muted">{t("hub.fb.note")}</p>
      <ErrorText error={start.error} />
    </section>
  );
}

/** Telegram Business: link the owner's Telegram, then add the bot in Telegram's own settings. */
export function TelegramCard({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const { data, reload } = useApi<Tele>("store/ai/telegram");
  const link = useAction(async () => { const r = await api<{ url: string }>("store/ai/telegram/link", { body: {} }); window.open(r.url, "_blank", "noopener"); await reload(); });
  const ch = data?.channel;
  const state = !data ? "loading" : !data.available ? "off" : ch?.status === "ACTIVE" ? "on" : ch ? "paused" : data.linked ? "linked" : "new";
  return (
    <section className="space-y-3 rounded-[18px] bg-surface p-5 ring-1 ring-line">
      <div className="flex flex-wrap items-center gap-3">
        <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-[#229ED9] text-white"><ChannelIcon channel="TELEGRAM" className="h-6 w-6 [&_svg]:text-white" /></span>
        <div className="min-w-0 flex-1">
          <h3 className="flex flex-wrap items-center gap-2 font-display text-[16px] font-semibold text-ink">{t("ai.platform.TELEGRAM")} {state === "on" && <Badge tone="green">{t("ai.connected")}</Badge>}{state === "paused" && <Badge tone="amber">{t("hub.tg.paused")}</Badge>}</h3>
          <p className="text-[13px] text-muted">{state === "on" ? t("hub.tg.on", { name: ch?.name ?? "" }) : t("hub.tg.sub")}</p>
        </div>
        {canManage && (state === "new" || state === "linked" || state === "paused") && <Button variant={state === "new" ? "primary" : "secondary"} loading={link.loading} onClick={() => void link.run()}>{state === "new" ? t("hub.tg.start") : t("hub.tg.again")}</Button>}
      </div>
      {state === "off" && <Alert tone="amber">{t("hub.tg.off")}</Alert>}
      {(state === "linked" || state === "paused") && (
        <Alert tone="blue">
          <div className="font-semibold">{state === "paused" ? t("hub.tg.pausedTitle") : t("hub.tg.nextTitle")}</div>
          <ol className="mt-1 list-decimal space-y-1 ps-5 text-sm"><li>{t("hub.tg.step1")}</li><li>{t("hub.tg.step2", { bot: data?.botUsername ? `@${data.botUsername}` : "" })}</li><li>{t("hub.tg.step3")}</li></ol>
          <Button size="sm" variant="secondary" className="mt-3" onClick={() => void reload()}>{t("hub.tg.refresh")}</Button>
        </Alert>
      )}
      <ErrorText error={link.error} />
    </section>
  );
}

/** X: log in with X; the assistant then answers the account's direct messages. Needs the platform's X developer app. */
export function XCard({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const { data } = useApi<{ available: boolean; channel?: { name?: string; status: string } | null }>("store/ai/x");
  const start = useAction(async () => { const r = await api<{ url: string }>("store/ai/x/start", { body: {} }); window.location.assign(r.url); });
  const on = data?.channel?.status === "ACTIVE";
  return (
    <section className="space-y-3 rounded-[18px] bg-surface p-5 ring-1 ring-line">
      <div className="flex flex-wrap items-center gap-3">
        <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-[#111111] text-white"><ChannelIcon channel="X" className="h-6 w-6 [&_svg]:text-white" /></span>
        <div className="min-w-0 flex-1">
          <h3 className="flex flex-wrap items-center gap-2 font-display text-[16px] font-semibold text-ink">{t("ai.platform.X")} {on && <Badge tone="green">{t("ai.connected")}</Badge>}</h3>
          <p className="text-[13px] text-muted">{on ? t("hub.x.on", { name: data?.channel?.name ?? "" }) : t("hub.x.sub")}</p>
        </div>
        {canManage && data?.available && <Button variant={on ? "secondary" : "primary"} loading={start.loading} onClick={() => void start.run()}>{on ? t("hub.x.again") : t("hub.x.start")}</Button>}
      </div>
      {data && !data.available && <Alert tone="amber">{t("hub.x.off")}</Alert>}
      <p className="text-[12.5px] text-muted">{t("hub.x.cost")}</p>
      <ErrorText error={start.error} />
    </section>
  );
}

type TikTok = { available: boolean; webhookReady: boolean; channel?: { name?: string; status: string; connectionState: string; statusNote?: string | null } | null };

/** TikTok Business Messaging (official API, direct): connect, and see exactly what is missing when the account is authorized but cannot message yet. */
export function TikTokCard({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const { data, reload } = useApi<TikTok>("store/ai/tiktok");
  const start = useAction(async () => { const r = await api<{ url: string }>("store/ai/tiktok/start", { body: {} }); window.location.assign(r.url); });
  const recheck = useAction(async () => { await api("store/ai/tiktok/recheck", { body: {} }); await reload(); });
  const ch = data?.channel;
  const state = !data ? "loading" : !data.available ? "off" : !ch ? "new" : ch.connectionState;
  const tone = state === "CONNECTED" ? "green" : state === "new" || state === "loading" ? undefined : state === "off" ? "amber" : "amber";
  return (
    <section className="space-y-3 rounded-[18px] bg-surface p-5 ring-1 ring-line">
      <div className="flex flex-wrap items-center gap-3">
        <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-[#111111] text-white"><ChannelIcon channel="TIKTOK" className="h-6 w-6 [&_svg]:text-white" /></span>
        <div className="min-w-0 flex-1">
          <h3 className="flex flex-wrap items-center gap-2 font-display text-[16px] font-semibold text-ink">{t("ai.platform.TIKTOK")} {state !== "loading" && state !== "new" && state !== "off" && <Badge tone={tone}>{t(`hub.tt.state.${state}`)}</Badge>}</h3>
          <p className="text-[13px] text-muted">{state === "CONNECTED" ? t("hub.tt.on") : t("hub.tt.sub")}</p>
        </div>
        {canManage && data?.available && (state === "new" || state === "REAUTH_REQUIRED") && <Button loading={start.loading} onClick={() => void start.run()}>{state === "new" ? t("hub.tt.start") : t("hub.tt.reconnect")}</Button>}
        {canManage && (state === "APPROVAL_REQUIRED" || state === "RESTRICTED" || state === "UNAVAILABLE" || state === "ERROR") && <Button variant="secondary" loading={recheck.loading} onClick={() => void recheck.run()}>{t("hub.tt.recheck")}</Button>}
      </div>
      {state === "off" && <Alert tone="amber">{t("hub.tt.off")}</Alert>}
      {ch?.statusNote && state !== "CONNECTED" && <Alert tone={state === "RESTRICTED" ? "red" : "amber"}>{ch.statusNote}</Alert>}
      <details className="group rounded-[12px] bg-canvas ring-1 ring-line">
        <summary className="flex cursor-pointer list-none items-center justify-between gap-2 px-4 py-3 text-[13px] font-semibold text-ink">{t("hub.tt.reqTitle")}<Icon name="chevron" className="h-4 w-4 text-muted transition-transform group-open:rotate-90" /></summary>
        <ul className="list-disc space-y-1.5 px-4 pb-4 ps-8 text-[12.5px] leading-relaxed text-muted">
          <li>{t("hub.tt.req1")}</li><li>{t("hub.tt.req2")}</li><li>{t("hub.tt.req3")}</li><li>{t("hub.tt.req4")}</li><li>{t("hub.tt.req5")}</li><li>{t("hub.tt.req6")}</li>
        </ul>
      </details>
      <ErrorText error={start.error ?? recheck.error} />
    </section>
  );
}

/** TikTok and Snapchat take a product feed address: elmanassa's feeds carry the shop's real prices and stock. */
export function FeedCard() {
  const { t } = useI18n();
  const origin = typeof window === "undefined" ? "" : window.location.origin;
  return (
    <section className="space-y-4 rounded-[18px] bg-surface p-5 ring-1 ring-line">
      <div className="flex items-center gap-3">
        <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-ink text-surface"><Icon name="file" className="h-6 w-6" /></span>
        <div className="min-w-0 flex-1"><h3 className="font-display text-[16px] font-semibold text-ink">{t("hub.feed.title")}</h3><p className="text-[13px] text-muted">{t("hub.feed.sub")}</p></div>
      </div>
      <div className="space-y-2">
        <p className="text-[12.5px] font-semibold text-ink-2">{t("hub.feed.xml")}</p><CopyField value={`${origin}/feeds/google.xml`} href={`${origin}/feeds/google.xml`} />
        <p className="pt-1 text-[12.5px] font-semibold text-ink-2">{t("hub.feed.csv")}</p><CopyField value={`${origin}/feeds/facebook.csv`} href={`${origin}/feeds/facebook.csv`} />
      </div>
      <ol className="list-decimal space-y-1 ps-5 text-[13px] text-muted"><li>{t("hub.feed.step1")}</li><li>{t("hub.feed.step2")}</li><li>{t("hub.feed.step3")}</li></ol>
    </section>
  );
}

const SOON = [["SNAPCHAT", "#F5C400"]] as const;

/** Honest status of the networks whose messaging and ads wait for each network's approval of our app. */
export function SoonCards() {
  const { t } = useI18n();
  return (
    <section className="space-y-3">
      <div><h3 className="font-display text-[15px] font-semibold text-ink">{t("hub.soon.title")}</h3><p className="text-[13px] text-muted">{t("hub.soon.sub")}</p></div>
      <ul className="grid gap-3 sm:grid-cols-2">
        {SOON.map(([k, color]) => (
          <li key={k} className="flex flex-col gap-2 rounded-[16px] bg-surface p-4 ring-1 ring-line">
            <div className="flex items-center gap-2.5"><span className="grid h-9 w-9 place-items-center rounded-xl text-white" style={{ background: color }}><ChannelIcon channel={k} className="h-5 w-5 [&_svg]:text-white" /></span><span className="font-semibold text-ink">{t(`ai.platform.${k}`)}</span><Badge tone="amber">{t("ch.soon")}</Badge></div>
            <p className="text-[12.5px] leading-relaxed text-muted">{t(`hub.soon.${k}`)}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Everything a shop can connect, in the order that matters: Facebook once, Telegram, product feeds, then the stores and marketplaces below. */
export function ConnectHub({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  return (
    <div className="mb-8 space-y-4">
      <div><h2 className="font-display text-[18px] font-semibold text-ink">{t("hub.title")}</h2><p className="text-[13.5px] text-muted">{t("hub.sub")}</p></div>
      <FacebookCard canManage={canManage} />
      <div className="grid gap-4 lg:grid-cols-2"><TelegramCard canManage={canManage} /><XCard canManage={canManage} /></div>
      <TikTokCard canManage={canManage} />
      <FeedCard />
      <SoonCards />
      <div className="pt-2"><h3 className="font-display text-[15px] font-semibold text-ink">{t("hub.stores.title")}</h3><p className="text-[13px] text-muted">{t("hub.stores.sub")} <Link href="/dashboard/marketing" className="font-semibold text-brand hover:underline">{t("hub.ads.link")}</Link></p></div>
    </div>
  );
}
