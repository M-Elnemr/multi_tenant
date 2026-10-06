"use client";

import Link from "next/link";
import { useState } from "react";
import { api, type ApiError } from "@/lib/client";
import { toMinor } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, Loading, PageHeader, Select, Table, Td } from "@/components/ui";
import { money } from "@/lib/format";

type Coupon = { id: string; code: string; discountType: string; value: number; minOrderMinor: number; maxRedemptions?: number; perCustomerLimit?: number; redemptions: number; isActive: boolean };

export default function Coupons() {
  const { t, locale, currency } = useI18n();
  const { data, loading, reload, error } = useApi<Coupon[]>("store/coupons");
  const [f, setF] = useState({ code: "", discountType: "PERCENT", value: "", minOrder: "", maxRedemptions: "", perCustomerLimit: "" });
  const add = useAction(async () => {
    await api("store/coupons", { body: { code: f.code, discountType: f.discountType, value: f.discountType === "PERCENT" ? Number(f.value) : toMinor(f.value), minOrderMinor: f.minOrder ? toMinor(f.minOrder) : 0, maxRedemptions: f.maxRedemptions ? Number(f.maxRedemptions) : undefined, perCustomerLimit: f.perCustomerLimit ? Number(f.perCustomerLimit) : undefined } });
    setF({ code: "", discountType: "PERCENT", value: "", minOrder: "", maxRedemptions: "", perCustomerLimit: "" });
    await reload();
  });
  const gated = ((error ?? add.error) as ApiError | undefined)?.code === "FEATURE_NOT_AVAILABLE";
  return (
    <>
      <PageHeader title={t("nav.coupons")} />
      {gated && <div className="mb-4"><Alert tone="amber">{t("domains.upgrade")} <Link href="/dashboard/billing" className="font-medium underline">{t("nav.billing")}</Link></Alert></div>}
      <Card className="mb-6">
        <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="grid gap-3 sm:grid-cols-3">
          <Field label={t("coupons.code")}><Input value={f.code} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} dir="ltr" required /></Field>
          <Field label={t("coupons.type")}><Select value={f.discountType} onChange={(e) => setF({ ...f, discountType: e.target.value })}><option value="PERCENT">{t("coupons.percent")}</option><option value="FIXED">{t("coupons.fixed")}</option></Select></Field>
          <Field label={f.discountType === "PERCENT" ? "%" : t("billing.amount")}><Input value={f.value} onChange={(e) => setF({ ...f, value: e.target.value })} inputMode="decimal" dir="ltr" required /></Field>
          <Field label={t("coupons.minOrder")}><Input value={f.minOrder} onChange={(e) => setF({ ...f, minOrder: e.target.value })} inputMode="decimal" dir="ltr" /></Field>
          <Field label={t("coupons.maxRedemptions")}><Input value={f.maxRedemptions} onChange={(e) => setF({ ...f, maxRedemptions: e.target.value })} inputMode="numeric" dir="ltr" /></Field>
          <Field label={t("coupons.perCustomer")}><Input value={f.perCustomerLimit} onChange={(e) => setF({ ...f, perCustomerLimit: e.target.value })} inputMode="numeric" dir="ltr" /></Field>
          <div className="sm:col-span-3"><ErrorText error={add.error} /><Button type="submit" loading={add.loading} className="mt-2">{t("common.add")}</Button></div>
        </form>
      </Card>
      {loading && !data ? <Loading /> : (
        <Table head={[t("coupons.code"), t("coupons.discount"), t("coupons.used"), t("coupons.minOrder")]}>
          {data?.map((c) => <tr key={c.id}><Td className="font-mono font-medium">{c.code}</Td><Td>{c.discountType === "PERCENT" ? `${c.value}%` : money(c.value, currency, locale)}</Td><Td>{c.redemptions}{c.maxRedemptions ? ` / ${c.maxRedemptions}` : ""}</Td><Td>{money(c.minOrderMinor, currency, locale)}</Td></tr>)}
        </Table>
      )}
    </>
  );
}
