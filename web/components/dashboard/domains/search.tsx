"use client";

import { useState } from "react";
import { money } from "@/lib/format";
import { useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { Alert, Badge, Button, Card, Empty, ErrorText, Input, Loading } from "../../ui";
import { BuyDialog } from "./buy-dialog";
import type { OrderListItem, SearchRes, SearchResult } from "./types";

/** "Find a domain": a name, a card per TLD with its price, and a Buy button that opens the confirm sheet. */
export default function FindDomainScreen({ onOrdered }: { onOrdered: (order: OrderListItem) => void }) {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const [query, setQuery] = useState<string | null>(null);
  const search = useApi<SearchRes>(query !== null ? `store/domains/search?q=${encodeURIComponent(query)}` : null);
  const [buying, setBuying] = useState<SearchResult | null>(null);

  const run = () => { const v = q.trim(); if (v.length >= 2) setQuery(v); };

  return (
    <div className="space-y-5">
      <form onSubmit={(e) => { e.preventDefault(); run(); }} className="flex flex-wrap gap-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">
        <div className="relative min-w-0 flex-1">
          <Icon name="search" className="pointer-events-none absolute inset-y-0 start-3 my-auto h-4 w-4 text-faint" />
          <Input dir="ltr" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("domainBuy.searchPlaceholder")} className="ps-9" />
        </div>
        <Button type="submit" loading={search.loading}>{t("domainBuy.search")}</Button>
      </form>

      {query === null && <Empty icon="globe" title={t("domainBuy.searchEmptyTitle")}>{t("domainBuy.searchEmptyBody")}</Empty>}
      {query !== null && search.loading && !search.data && <Loading variant="cards" />}
      <ErrorText error={search.error} />

      {search.data && search.data.results.length === 0 && <Empty icon="globe" title={t("domainBuy.noResultsTitle")}>{t("domainBuy.noResultsBody")}</Empty>}
      {search.data && !search.data.registrarEnabled && <Alert tone="amber">{t("domainBuy.notOpenYet")}</Alert>}

      {search.data && search.data.results.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {search.data.results.map((r) => (
            <Card key={r.domain} className="space-y-3">
              <div className="flex items-start justify-between gap-2">
                <p className="truncate font-display text-[15px] font-semibold text-ink" dir="ltr">{r.domain}</p>
                {r.premium && <Badge tone="violet">{t("domainBuy.premium")}</Badge>}
              </div>
              {r.available ? (
                <>
                  <p className="font-display text-[1.3rem] font-semibold leading-none text-ink tnum">
                    {money(r.priceMinor, r.currency, locale)}<span className="ms-1 text-[12px] font-normal text-muted">/{t("domainBuy.perYear")}</span>
                  </p>
                  <p className="text-[12px] text-muted">{t("domainBuy.renewsAt", { price: money(r.renewalPriceMinor, r.currency, locale) })}</p>
                  <Button className="w-full" disabled={!search.data!.registrarEnabled} onClick={() => setBuying(r)}><Icon name="plus" className="h-4 w-4" />{t("domainBuy.buy")}</Button>
                </>
              ) : (
                <p className="rounded-[10px] bg-canvas px-3 py-2 text-[13px] font-medium text-muted">{t("domainBuy.taken")}</p>
              )}
            </Card>
          ))}
        </div>
      )}

      <BuyDialog result={buying} onClose={() => setBuying(null)} onOrdered={(order) => { setBuying(null); onOrdered(order); }} />
    </div>
  );
}
