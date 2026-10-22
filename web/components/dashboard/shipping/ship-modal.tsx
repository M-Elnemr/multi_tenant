"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, toMinor } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input, Loading, Modal } from "@/components/ui";
import { CountryFlag, ProviderMark, type Account } from "../channels/common";

/** Ship a shop order or a channel order with a connected carrier; the carrier's tracking number lands on the order. */
export function ShipModal({ orderId, channelOrderId, country, codMinor, currency, onClose, onDone }: { orderId?: string; channelOrderId?: string; country?: string; codMinor?: number; currency?: string; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const carriers = useApi<Account[]>("store/shipping/carriers");
  const list = (carriers.data ?? []).filter((c) => c.status !== "PAUSED");
  const ordered = [...list].sort((a, b) => Number(b.country === country) - Number(a.country === country));
  const [accountId, setAccountId] = useState("");
  const [f, setF] = useState({ weight: "1", pieces: "1", description: "", notes: "", cod: codMinor !== undefined ? fromMinor(codMinor) : "", markShipped: true });
  const picked = accountId || ordered[0]?.id || "";
  const ship = useAction(async () => {
    await api("store/shipping/shipments", { body: { orderId, channelOrderId, accountId: picked, weightKg: Number(f.weight) || 1, pieces: Number(f.pieces) || 1, description: f.description || undefined, notes: f.notes || undefined, codMinor: f.cod === "" ? undefined : toMinor(f.cod), markShipped: f.markShipped } });
    onDone();
  });
  return (
    <Modal open onClose={onClose} title={t("ship.shipWith")}>
      {carriers.loading && !carriers.data ? <Loading /> : ordered.length === 0 ? (
        <div className="space-y-4"><Alert tone="amber">{t("ship.noCarriers")}</Alert><Link href="/dashboard/shipping"><Button>{t("ship.connectCarrier")}</Button></Link></div>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); void ship.run(); }} className="space-y-4">
          <div role="radiogroup" className="space-y-2">
            {ordered.map((c) => (
              <label key={c.id} className={`flex cursor-pointer items-center gap-3 rounded-xl border p-3 transition ${picked === c.id ? "border-brand bg-brand-soft" : "border-slate-200 hover:border-slate-300"}`}>
                <input type="radio" name="carrier" checked={picked === c.id} onChange={() => setAccountId(c.id)} />
                <ProviderMark provider={c.provider} name={c.name} className="h-9 w-9 text-xs" />
                <span className="flex-1 font-semibold">{c.name} <CountryFlag code={c.country} /><span className="block text-xs font-normal text-slate-500">{c.label}</span></span>
              </label>
            ))}
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("ship.weight")}><Input inputMode="decimal" value={f.weight} onChange={(e) => setF({ ...f, weight: e.target.value })} /></Field>
            <Field label={t("ship.pieces")}><Input inputMode="numeric" value={f.pieces} onChange={(e) => setF({ ...f, pieces: e.target.value })} /></Field>
          </div>
          <Field label={`${t("ship.cod")}${currency ? ` (${currency})` : ""}`} hint={t("ship.codHint")}><Input inputMode="decimal" value={f.cod} onChange={(e) => setF({ ...f, cod: e.target.value })} /></Field>
          <Field label={t("ship.description")}><Input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
          <Field label={t("ship.notes")}><Input value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></Field>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.markShipped} onChange={(e) => setF({ ...f, markShipped: e.target.checked })} />{t("ship.markShipped")}</label>
          <ErrorText error={ship.error} />
          <div className="flex justify-end gap-2"><Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button type="submit" loading={ship.loading}>{t("ship.create")}</Button></div>
        </form>
      )}
    </Modal>
  );
}
