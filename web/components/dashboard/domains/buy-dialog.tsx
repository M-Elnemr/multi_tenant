"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Sheet } from "../../overlay";
import { Alert, Button, ErrorText, Field, Select } from "../../ui";
import { RegistrantContactPicker, useRegistrantContacts } from "./registrant-contacts";
import { WALLET_TOPUP_LINK, type OrderListItem, type SearchResult } from "./types";

const YEARS = [1, 2, 3, 4, 5];

/** The actual form, remounted (via the `key` the caller gives it) whenever a different result is opened, so its
 *  years/contact state always starts fresh — no effect-based reset needed. */
function BuyForm({ result, onOrdered }: { result: SearchResult; onOrdered: (order: OrderListItem) => void }) {
  const { t, locale, currency } = useI18n();
  const contacts = useRegistrantContacts();
  const wallet = useApi<{ balanceMinor: number }>("store/ai/whatsapp");
  const [years, setYears] = useState(1);
  const [contactId, setContactId] = useState<string | null>(null);

  const buy = useAction(async () => {
    if (!contactId) return;
    const order = await api<OrderListItem>("store/domains/orders", { body: { quoteId: result.quoteId, years, registrantContactId: contactId } });
    onOrdered(order);
  });

  const total = result.priceMinor + (years - 1) * result.renewalPriceMinor;
  const balance = wallet.data?.balanceMinor ?? 0;
  const low = wallet.data !== null && total > balance;

  return (
    <>
      <div className="space-y-4">
        <p className="font-display text-[16px] font-semibold text-ink" dir="ltr">{result.domain}</p>

        <Field label={t("domainBuy.years")}>
          <Select value={years} onChange={(e) => setYears(Number(e.target.value))}>
            {YEARS.map((y) => <option key={y} value={y}>{t("domainBuy.yearsN", { n: y })}</option>)}
          </Select>
        </Field>

        <div className="space-y-1.5 rounded-[12px] bg-canvas p-3.5 text-[13px] ring-1 ring-line">
          <div className="flex justify-between"><span className="text-muted">{t("domainBuy.estTotal")}</span><span className="font-semibold text-ink tnum">{money(total, result.currency, locale)}</span></div>
          <div className="flex justify-between"><span className="text-muted">{t("domainBuy.walletBalance")}</span><span className="tnum">{money(balance, currency, locale)}</span></div>
          <p className="text-[11.5px] text-faint">{t("domainBuy.estHint")}</p>
        </div>

        {low && <Alert tone="amber">{t("domainBuy.insufficientBalance")} <Link href={WALLET_TOPUP_LINK} className="font-semibold underline">{t("domainBuy.topUp")}</Link></Alert>}

        <div>
          <p className="mb-1.5 text-[13px] font-semibold text-ink-2">{t("domainBuy.contact.title")}</p>
          <RegistrantContactPicker contacts={contacts} value={contactId} onChange={setContactId} />
        </div>

        <ErrorText error={buy.error} />
      </div>
      <div className="mt-4 flex items-center justify-between gap-3 border-t border-line pt-4">
        <div className="text-[13px] text-muted">{t("domainBuy.total")}: <span className="font-semibold text-ink tnum">{money(total, result.currency, locale)}</span></div>
        <Button loading={buy.loading} disabled={!contactId || low} onClick={() => void buy.run()}>{t("domainBuy.confirmBuy")}</Button>
      </div>
    </>
  );
}

/** The "Buy" confirm panel: pick (or add) a registrant contact, choose the term, see the price and wallet balance,
 *  and either place the order or get sent to top up. Shown as a Sheet (the amount of content doesn't fit a small dialog). */
export function BuyDialog({ result, onClose, onOrdered }: { result: SearchResult | null; onClose: () => void; onOrdered: (order: OrderListItem) => void }) {
  const { t } = useI18n();
  return (
    <Sheet open={!!result} onClose={onClose} title={t("domainBuy.buyTitle")}>
      {result && <BuyForm key={result.domain} result={result} onOrdered={onOrdered} />}
    </Sheet>
  );
}
