"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { Avatar, Badge, Button, Empty, ErrorText } from "../../ui";
import { dateOnly, money } from "@/lib/format";
import { SearchBox } from "../filters";
import { CardSkeletons, Panel, PanelHead } from "../marketing/kit";
import { PLAT_COLOR, WA_OWN_LINK, csv, type ClientRow, type ClientsRes } from "./types";

const LIMIT = 50;

function PlatTag({ p }: { p: string }) {
  const { t } = useI18n();
  const c = PLAT_COLOR[p] ?? "var(--muted)";
  return <span className="inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset" style={{ color: c, background: `color-mix(in srgb, ${c} 12%, transparent)`, ["--tw-ring-color" as string]: `color-mix(in srgb, ${c} 30%, transparent)` }}>{t(`gx.plat.${p}`)}</span>;
}

function Reach({ c }: { c: ClientRow }) {
  const { t } = useI18n();
  if (c.reachable) return <Badge tone="green">{t("gx.reachable")}</Badge>;
  const reason = c.reason ?? "";
  return (
    <span className="flex flex-wrap items-center gap-1.5">
      <Badge tone="amber">{t("gx.unreachable")}</Badge>
      {reason && <span className="text-[11.5px] text-muted">{["OUTSIDE_WINDOW", "NO_OWN_NUMBER", "OPTED_OUT", "NO_PHONE", "CONNECT_ACCOUNT"].includes(reason) ? t(`gx.reason.${reason}`) : reason}</span>}
      {reason === "NO_OWN_NUMBER" && <Link href={WA_OWN_LINK} className="text-[11.5px] font-semibold text-brand hover:underline">{t("gx.connectNumber")}</Link>}
    </span>
  );
}

/** The clients inside the chosen groups and platforms, with search and paging. */
export default function ClientList({ segs, plats }: { segs: string[]; plats: string[] }) {
  const { t, locale, currency } = useI18n();
  const [q, setQ] = useState("");
  const [qd, setQd] = useState("");
  const filterKey = `${csv(segs)}|${csv(plats)}|${qd}`;
  const [pg, setPg] = useState({ key: filterKey, offset: 0 });
  const offset = pg.key === filterKey ? pg.offset : 0;
  const path = segs.length && plats.length ? `store/segments/customers?segments=${csv(segs)}&platforms=${csv(plats)}&q=${encodeURIComponent(qd)}&limit=${LIMIT}&offset=${offset}` : null;
  const res = useApi<ClientsRes>(path);
  const d = res.data;
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const pages = d ? Math.max(1, Math.ceil(d.total / LIMIT)) : 1;
  const page = Math.floor(offset / LIMIT) + 1;

  // debounce the search box: the state is set from the timer callback, not synchronously inside the effect
  useEffect(() => {
    const id = setTimeout(() => setQd(q.trim()), 300);
    return () => clearTimeout(id);
  }, [q]);

  return (
    <Panel className="overflow-hidden">
      <div className="space-y-3 px-4 pt-4 sm:px-5 sm:pt-5">
        <PanelHead icon="users" title={t("gx.clients")} hint={d ? t("gx.clientsHint", { total: n(d.total), reachable: n(d.reachable) }) : t("gx.clientsPick")} />
        <SearchBox value={q} onChange={setQ} placeholder={t("gx.searchClients")} />
      </div>
      <ErrorText error={res.error} />
      {!path ? <div className="p-5"><Empty compact icon="users">{t("gx.clientsPick")}</Empty></div>
        : !d ? <div className="p-5"><CardSkeletons n={3} className="space-y-2" h="h-12" /></div>
        : d.data.length === 0 ? <div className="p-5"><Empty compact icon="users">{t("gr.noCustomers")}</Empty></div> : (
          <>
            <ul className="mt-3 divide-y divide-line">
              {d.data.map((c) => (
                <li key={c.id} className="flex flex-wrap items-center gap-3 px-4 py-3 sm:px-5">
                  <Avatar name={c.name || c.phone || "?"} className="h-9 w-9 text-[13px]" />
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-1.5 text-[13.5px] font-semibold text-ink">{c.name || (c.phone ? <span dir="ltr">{c.phone}</span> : "-")}<PlatTag p={c.platform} /><Badge tone="slate">{t(`gr.seg.${c.segment}`)}</Badge></p>
                    {c.name && c.phone && <p dir="ltr" className="text-start text-[12px] tnum text-muted">{c.phone}</p>}
                    <div className="mt-1"><Reach c={c} /></div>
                  </div>
                  <p className="text-[12px] text-muted tnum">{c.orders} · {money(c.spentMinor, currency, locale)}{c.lastOrderAt ? ` · ${dateOnly(c.lastOrderAt, locale)}` : ""}</p>
                </li>
              ))}
            </ul>
            {d.total > LIMIT && (
              <div className="flex items-center justify-between gap-2 border-t border-line px-4 py-3 text-[13px] text-muted sm:px-5">
                <span className="tnum">{t("common.pageOf", { page, pages })}</span>
                <span className="flex gap-2">
                  <Button size="sm" variant="secondary" disabled={offset === 0} onClick={() => setPg({ key: filterKey, offset: Math.max(0, offset - LIMIT) })}><Icon name="chevron" className="h-4 w-4 rotate-180 flip-rtl" />{t("common.prev")}</Button>
                  <Button size="sm" variant="secondary" disabled={offset + LIMIT >= d.total} onClick={() => setPg({ key: filterKey, offset: offset + LIMIT })}>{t("common.next")}<Icon name="chevron" className="h-4 w-4 flip-rtl" /></Button>
                </span>
              </div>
            )}
          </>
        )}
    </Panel>
  );
}
