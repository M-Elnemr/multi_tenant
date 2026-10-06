"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Pager, Select, Table, Td } from "@/components/ui";

type Row = { branchId: string; branchName: string; variantId: string; sku: string; productName: string; comboKey: string; quantityOnHand: number; quantityReserved: number; available: number; lowStockThreshold: number };

export default function Inventory() {
  const t = useT();
  const [low, setLow] = useState(false);
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<Row>>(`store/inventory?page=${page}&lowStock=${low}`);
  const [target, setTarget] = useState<Row | null>(null);
  const [delta, setDelta] = useState("");
  const [type, setType] = useState("PURCHASE");
  const [reason, setReason] = useState("");
  const adjust = useAction(async () => {
    if (!target) return;
    let d = Math.abs(Number(delta));
    if (type === "DAMAGE" || (type === "ADJUSTMENT" && delta.trim().startsWith("-"))) d = -d;
    await api("store/inventory/adjustments", { body: { branchId: target.branchId, variantId: target.variantId, delta: d, type, reason } });
    setTarget(null); setDelta(""); setReason("");
    await reload();
  });
  return (
    <>
      <PageHeader title={t("nav.inventory")} actions={<label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={low} onChange={(e) => { setLow(e.target.checked); setPage(1); }} />{t("inventory.lowOnly")}</label>} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("products.name"), "SKU", t("inventory.branch"), t("inventory.onHand"), t("inventory.reserved"), t("inventory.available"), ""]}>
            {data?.data.map((r) => (
              <tr key={r.branchId + r.variantId}>
                <Td>{r.productName}<div className="text-xs text-slate-500">{r.comboKey.replace(/\|/g, " / ").replace(/=/g, ": ")}</div></Td><Td className="font-mono text-xs">{r.sku}</Td><Td>{r.branchName}</Td><Td>{r.quantityOnHand}</Td><Td>{r.quantityReserved}</Td>
                <Td className={r.available <= r.lowStockThreshold ? "font-semibold text-red-600" : ""}>{r.available}</Td>
                <Td className="text-end"><Button size="sm" variant="secondary" onClick={() => setTarget(r)}>{t("inventory.adjust")}</Button></Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      <Modal open={!!target} onClose={() => setTarget(null)} title={t("inventory.adjust")}>
        <div className="space-y-3">
          <p className="text-sm text-slate-600">{target?.productName} · {target?.sku}</p>
          <Field label={t("inventory.type")}><Select value={type} onChange={(e) => setType(e.target.value)}>{["PURCHASE", "ADJUSTMENT", "RETURN", "DAMAGE"].map((x) => <option key={x} value={x}>{t(`inventory.type.${x}`)}</option>)}</Select></Field>
          <Field label={t("inventory.quantity")} hint={type === "ADJUSTMENT" ? t("inventory.adjustHint") : undefined}><Input value={delta} onChange={(e) => setDelta(e.target.value)} inputMode="numeric" dir="ltr" /></Field>
          <Field label={t("inventory.reason")}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          <ErrorText error={adjust.error} />
          <Button className="w-full" loading={adjust.loading} onClick={() => adjust.run()} disabled={!delta}>{t("common.save")}</Button>
        </div>
      </Modal>
    </>
  );
}
