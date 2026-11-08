"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { useToast } from "@/components/toast";
import { Button, ErrorText, Input, Skeleton, StatusBadge, cx } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { POINT_PRICES } from "@/lib/pricing";
import { AI_GRADIENT } from "./kit";

type Wallet = { balanceMinor: number; topups: { id: string; amountMinor: number; status: string; createdAt: string }[] };

/** The shop's credit in EGP (1 point = 1 EGP): balance, what each thing costs, and a top-up request the platform team approves after payment. */
export function PointsWallet({ write }: { write: boolean }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<Wallet>("store/ai/whatsapp");
  const [amount, setAmount] = useState(10000);
  const topup = useAction(async () => { await api("store/ai/whatsapp/topups", { body: { amountMinor: amount } }); await reload(); toast(t("kit.topupSent")); });
  if (!data) return <Skeleton className="h-56 w-full !rounded-[18px]" />;
  const points = data.balanceMinor / 100;
  const low = data.balanceMinor < POINT_PRICES.aiConversation * 100;
  const conversations = Math.floor(points / POINT_PRICES.aiConversation);
  return (
    <section className="overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
      {/* balance band */}
      <div className="relative overflow-hidden bg-ink p-5 text-surface">
        <span aria-hidden className="pointer-events-none absolute -end-16 -top-20 h-48 w-48 rounded-full opacity-30 blur-2xl" style={{ background: AI_GRADIENT }} />
        <div className="relative flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="flex items-center gap-2 text-[12.5px] font-medium opacity-70"><Icon name="wallet" className="h-4 w-4" />{t("pts.title")}</p>
            <p className={cx("mt-2 font-display text-[2rem] font-semibold leading-none tnum", low && "text-rose-400")}>
              {t("pts.balance", { n: points.toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 }) })}
            </p>
            {!low && <p className="mt-2 text-[12px] opacity-60 tnum">{t("kit.aboutConversations", { n: conversations.toLocaleString(locale === "ar" ? "ar-EG" : "en-US") })}</p>}
          </div>
          <span className={cx("inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px] font-semibold", low ? "bg-rose-500/20 text-rose-200" : "bg-emerald-400/15 text-emerald-200")}>
            <span className={cx("h-1.5 w-1.5 rounded-full", low ? "bg-rose-400" : "bg-emerald-400 live-dot")} />{low ? t("kit.creditEmpty") : t("kit.creditOk")}
          </span>
        </div>
      </div>

      <div className="space-y-4 p-5">
        {low && <p className="rounded-[12px] bg-rose-50 px-3.5 py-2.5 text-[13px] text-rose-700 ring-1 ring-inset ring-rose-600/15">{t("pts.empty")}</p>}
        <ul className="grid gap-2 text-[13px] sm:grid-cols-2">
          <li className="flex items-center gap-2.5 rounded-[12px] bg-surface-2 px-3.5 py-2.5 text-ink-2"><Icon name="sparkles" className="h-4 w-4 shrink-0 text-violet-600" />{t("pts.rateAi")}</li>
          <li className="flex items-center gap-2.5 rounded-[12px] bg-surface-2 px-3.5 py-2.5 text-ink-2"><Icon name="chat" className="h-4 w-4 shrink-0 text-emerald-600" />{t("pts.rateWa")}</li>
        </ul>

        {write && (
          <div className="rounded-[14px] p-4 ring-1 ring-line">
            <p className="mb-3 text-[13px] font-semibold text-ink">{t("wa.requestTopup")}</p>
            <div className="flex flex-wrap gap-2">
              {[5000, 10000, 20000, 50000].map((a) => (
                <button key={a} type="button" onClick={() => setAmount(a)} aria-pressed={amount === a}
                  className={cx("h-9 rounded-full px-3.5 text-[13px] font-semibold ring-1 transition tnum", amount === a ? "bg-ink text-surface ring-ink" : "bg-surface text-ink-2 ring-line-strong hover:ring-ink/30")}>{money(a, "EGP", locale)}</button>
              ))}
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <div className="relative">
                <Input className="w-32 pe-12 tnum" type="number" min={10} value={Math.round(amount / 100)} onChange={(e) => setAmount(Math.max(0, Number(e.target.value) || 0) * 100)} aria-label={t("wa.amount")} />
                <span className="pointer-events-none absolute inset-y-0 end-3 grid place-items-center text-xs font-semibold text-faint">EGP</span>
              </div>
              <Button onClick={() => void topup.run()} loading={topup.loading} disabled={amount < 1000}><Icon name="plus" className="h-4 w-4" />{t("wa.requestTopup")}</Button>
            </div>
            <p className="mt-2.5 text-xs leading-relaxed text-muted">{t("wa.topupHint")}</p>
          </div>
        )}
        {!write && <p className="text-xs text-muted">{t("wa.topupHint")}</p>}
        <ErrorText error={topup.error} />

        {data.topups.length > 0 && (
          <div>
            <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wide text-faint">{t("kit.topupHistory")}</p>
            <ul className="divide-y divide-line rounded-[14px] ring-1 ring-line">
              {data.topups.map((r) => (
                <li key={r.id} className="flex items-center justify-between gap-3 px-3.5 py-2.5 text-[13px]">
                  <span className="min-w-0"><span className="font-semibold text-ink tnum">{money(r.amountMinor, "EGP", locale)}</span><span className="ms-2 text-xs text-muted">{dateTime(r.createdAt, locale)}</span></span>
                  <StatusBadge status={r.status} />
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </section>
  );
}
