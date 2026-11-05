"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Button, ErrorText, Input, StatusBadge } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { POINT_PRICES } from "@/lib/pricing";

type Wallet = { balanceMinor: number; topups: { id: string; amountMinor: number; status: string; createdAt: string }[] };

/** The shop's points (1 point = 1 EGP): balance, what each thing costs, and a top-up request the platform team approves after payment. */
export function PointsWallet({ write }: { write: boolean }) {
  const { t, locale } = useI18n();
  const { data, reload } = useApi<Wallet>("store/ai/whatsapp");
  const [amount, setAmount] = useState(10000);
  const topup = useAction(async () => { await api("store/ai/whatsapp/topups", { body: { amountMinor: amount } }); await reload(); });
  if (!data) return null;
  const points = data.balanceMinor / 100;
  const low = data.balanceMinor < POINT_PRICES.aiConversation * 100;
  return (
    <section className="space-y-3 rounded-xl border bg-white p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="flex items-center gap-2 font-semibold"><Icon name="wallet" className="h-5 w-5 text-brand" />{t("pts.title")}</h3>
        <p className={`text-2xl font-extrabold tnum ${low ? "text-rose-600" : "text-brand"}`}>{t("pts.balance", { n: points.toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { maximumFractionDigits: 1 }) })}</p>
      </div>
      {low && <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{t("pts.empty")}</p>}
      <ul className="grid gap-2 text-sm sm:grid-cols-2">
        <li className="rounded-lg bg-slate-50 px-3 py-2">{t("pts.rateAi")}</li>
        <li className="rounded-lg bg-slate-50 px-3 py-2">{t("pts.rateWa")}</li>
      </ul>
      {write && (
        <div className="flex flex-wrap items-end gap-2">
          {[5000, 10000, 20000, 50000].map((a) => <button key={a} type="button" onClick={() => setAmount(a)} className={`rounded-full border px-3 py-1.5 text-sm font-semibold ${amount === a ? "border-brand bg-brand-soft text-brand" : ""}`}>{money(a, "EGP", locale)}</button>)}
          <Input className="w-28" type="number" min={10} value={Math.round(amount / 100)} onChange={(e) => setAmount(Math.max(0, Number(e.target.value) || 0) * 100)} aria-label={t("wa.amount")} />
          <Button onClick={() => void topup.run()} loading={topup.loading} disabled={amount < 1000}>{t("wa.requestTopup")}</Button>
        </div>
      )}
      <ErrorText error={topup.error} />
      <p className="text-xs text-slate-500">{t("wa.topupHint")}</p>
      {data.topups.length > 0 && <ul className="space-y-1 text-sm">{data.topups.map((r) => <li key={r.id} className="flex items-center justify-between gap-2 rounded-lg bg-slate-50 px-3 py-2"><span>{money(r.amountMinor, "EGP", locale)} · <span className="text-xs text-slate-500">{dateTime(r.createdAt, locale)}</span></span><StatusBadge status={r.status} /></li>)}</ul>}
    </section>
  );
}
