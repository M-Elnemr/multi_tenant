"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { ChannelIcon } from "../../channel-icons";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { ConfirmDialog } from "../../overlay";
import { Alert, Button, ErrorText, Field, Input, Textarea } from "../../ui";
import { Panel, PanelHead } from "../marketing/kit";
import type { Connection, Connections } from "./types";

type Plat = "INSTAGRAM" | "FACEBOOK";

/** GET store/unofficial-meta, whichever shape the server sends it in (a map under `platforms`, a list, or a bare map). */
export function useConnections() {
  const q = useApi<unknown>("store/unofficial-meta");
  const raw = q.data as { platforms?: unknown } | unknown[] | null;
  const src = Array.isArray(raw) ? raw : raw && typeof raw === "object" && "platforms" in raw ? (raw as { platforms?: unknown }).platforms : raw;
  const list: Connection[] = Array.isArray(src)
    ? (src as Connection[])
    : src && typeof src === "object"
      ? Object.entries(src as Record<string, Connection>).filter(([, v]) => v && typeof v === "object" && "status" in v).map(([k, v]) => ({ ...v, platform: v.platform ?? k }))
      : [];
  const data: Connections | null = q.data ? { INSTAGRAM: list.find((c) => c.platform === "INSTAGRAM"), FACEBOOK: list.find((c) => c.platform === "FACEBOOK") } : null;
  return { data, reload: q.reload };
}

export const isConnected = (c?: Connection) => !!c && (c.status === "ACTIVE" || c.status === "BLOCKED");

function PlatformCard({ plat, conn, onChanged }: { plat: Plat; conn?: Connection; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const [open, setOpen] = useState(false);       // the form, when already connected (reconnect)
  const [confirm, setConfirm] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [totp, setTotp] = useState("");
  const [cookies, setCookies] = useState("");
  const [pageId, setPageId] = useState("");
  const on = isConnected(conn);
  const showForm = !on || open;
  const ig = plat === "INSTAGRAM";
  const connect = useAction(async () => {
    const body = ig ? { username: username.trim(), password, totpSecret: totp.trim() || undefined } : { cookies: cookies.trim(), pageId: pageId.trim() || undefined };
    setPassword(""); setTotp(""); setCookies("");   // never kept after the request leaves
    await api(`store/unofficial-meta/${plat}/connect`, { body });
    setUsername(""); setPageId(""); setOpen(false);
    onChanged();
  });
  const wipe = useAction(async () => { await api(`store/unofficial-meta/${plat}`, { method: "DELETE" }); setConfirm(false); onChanged(); });
  const ready = ig ? username.trim().length >= 2 && password.length >= 4 : cookies.trim().length >= 20;
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  return (
    <Panel className="space-y-3 p-4">
      <div className="flex flex-wrap items-center gap-2.5">
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[10px] bg-surface-2 ring-1 ring-line"><ChannelIcon channel={plat} className="h-5 w-5" /></span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[14px] font-semibold text-ink">{t(ig ? "cx.igTitle" : "cx.fbTitle")}</p>
          {on
            ? <p className="truncate text-[12px] text-muted tnum">{t("mx.connected", { name: conn?.account ?? "" })}{conn && conn.dailyCap > 0 ? ` · ${t("cx.today", { sent: n(conn.sentToday), cap: n(conn.dailyCap) })}` : ""}</p>
            : <p className="text-[12px] text-muted">{t(ig ? "cx.igHint" : "cx.fbHint")}</p>}
        </div>
        {on && <span className={`rounded-full px-2 py-0.5 text-[11.5px] font-semibold ring-1 ring-inset ${conn?.status === "ACTIVE" ? "bg-emerald-50 text-emerald-800 ring-emerald-600/20 dark:bg-emerald-400/10 dark:text-emerald-100" : "bg-amber-50 text-amber-900 ring-amber-600/20 dark:bg-amber-400/10 dark:text-amber-100"}`}>{t(`mx.status.${conn?.status}`)}</span>}
        {on && (
          <span className="flex flex-wrap gap-1.5">
            <Button size="sm" variant="secondary" onClick={() => setOpen(!open)}>{t("cx.reconnect")}</Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirm(true)}>{t("cx.disconnect")}</Button>
          </span>
        )}
      </div>
      {conn && !conn.supported && <Alert tone="blue">{t("mx.unavailable")}</Alert>}
      {conn?.status === "BLOCKED" && conn.blockedUntil && <Alert tone="amber">{t("mx.blockedUntil", { when: dateTime(conn.blockedUntil, locale) })}</Alert>}
      {conn?.status === "NEEDS_LOGIN" && <Alert tone="amber">{t("error.NEEDS_LOGIN")}</Alert>}
      {showForm && (
        <form className="space-y-3" autoComplete="off" onSubmit={(e) => { e.preventDefault(); void connect.run(); }}>
          {ig ? (
            <>
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label={t("mx.username")}><Input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" dir="ltr" /></Field>
                <Field label={t("mx.password")}><Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" dir="ltr" /></Field>
              </div>
              <Field label={t("mx.totp")} hint={t("mx.totpHint")}><Input value={totp} onChange={(e) => setTotp(e.target.value)} autoComplete="off" dir="ltr" /></Field>
            </>
          ) : (
            <>
              <Field label={t("cx.cookies")} hint={t("cx.cookiesHint")}><Textarea rows={3} value={cookies} onChange={(e) => setCookies(e.target.value)} autoComplete="off" spellCheck={false} dir="ltr" /></Field>
              <Field label={t("cx.pageId")} hint={t("cx.pageIdHint")}><Input value={pageId} onChange={(e) => setPageId(e.target.value)} autoComplete="off" inputMode="numeric" dir="ltr" /></Field>
            </>
          )}
          <ErrorText error={connect.error} />
          <Button type="submit" variant="ink" loading={connect.loading} disabled={!ready}><Icon name="link" className="h-4 w-4" />{t("mx.connect")}</Button>
        </form>
      )}
      <ErrorText error={wipe.error} />
      <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} loading={wipe.loading} danger onConfirm={() => void wipe.run()}
        title={t("mx.wipeTitle")} body={t("mx.wipeBody")} confirmLabel={t("cx.disconnect")} cancelLabel={t("common.cancel")} />
    </Panel>
  );
}

/** Top of both targeting tabs: connect the Instagram and Messenger accounts used to reach old contacts and new prospects. */
export default function ConnectStrip({ conns, onChanged }: { conns: Connections | null; onChanged: () => void }) {
  const { t } = useI18n();
  return (
    <section id="connect-accounts" className="scroll-mt-20 space-y-2.5">
      <PanelHead icon="link" title={t("cx.title")} hint={t("cx.hint")} />
      {!conns ? <div className="skeleton h-28 rounded-[18px]" /> : (
        <div className="grid gap-3 lg:grid-cols-2 lg:items-start">
          <PlatformCard plat="INSTAGRAM" conn={conns.INSTAGRAM} onChanged={onChanged} />
          <PlatformCard plat="FACEBOOK" conn={conns.FACEBOOK} onChanged={onChanged} />
        </div>
      )}
    </section>
  );
}
