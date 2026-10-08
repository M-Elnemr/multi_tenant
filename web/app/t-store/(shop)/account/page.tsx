"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { ChangePasswordCard } from "@/components/change-password";
import { LogoutButton } from "@/components/logout-button";
import { Button, Card, Empty, ErrorText, Field, Input, Loading, PageHeader } from "@/components/ui";
import Link from "next/link";
import { PhoneInput } from "@/components/inputs";

type Addr = { id: string; title?: string; recipientName: string; phone: string; addressLine1: string; city: string; isDefaultShipping: boolean };
type Wish = { id: string; name: string; slug: string };

export default function Account() {
  const t = useT();
  const { me, ready } = useMe();
  const addrs = useApi<Addr[]>("shop/addresses");
  const wish = useApi<Wish[]>("shop/wishlist");
  const [f, setF] = useState({ title: "", recipientName: "", phone: "", addressLine1: "", city: "" });
  const add = useAction(async () => {
    await api("shop/addresses", { body: { title: f.title || undefined, makeDefault: (addrs.data?.length ?? 0) === 0, address: { recipientName: f.recipientName, phone: f.phone, addressLine1: f.addressLine1, city: f.city } } });
    setF({ title: "", recipientName: "", phone: "", addressLine1: "", city: "" });
    await addrs.reload();
  });
  const unwish = useAction(async (id: string) => { await api(`shop/wishlist/${id}`, { method: "DELETE" }); await wish.reload(); });
  if (!ready) return <Loading />;
  return (
    <>
      <PageHeader title={t("account.title")} subtitle={me ? `${me.firstName} ${me.lastName} · ${me.phone ?? ""}` : ""} actions={<><Link href="/orders" className="rounded-lg border bg-white px-3 py-2 text-sm">{t("orders.mine")}</Link><LogoutButton /></>} />
      <div className="mb-6"><ChangePasswordCard /></div>
      <div className="grid gap-6 md:grid-cols-2">
        <Card>
          <h2 className="mb-3 font-medium">{t("account.addresses")}</h2>
          {addrs.data?.length === 0 && <p className="mb-3 text-sm text-slate-500">{t("account.noAddresses")}</p>}
          <ul className="mb-4 space-y-2 text-sm">{addrs.data?.map((a) => <li key={a.id} className="rounded-lg border p-3"><b>{a.title ?? a.recipientName}</b><br />{a.addressLine1}, {a.city}<br /><span dir="ltr">{a.phone}</span></li>)}</ul>
          <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="space-y-3 border-t pt-4">
            <div className="grid grid-cols-2 gap-3">
              <Field label={t("checkout.recipient")}><Input value={f.recipientName} onChange={(e) => setF({ ...f, recipientName: e.target.value })} required /></Field>
              <Field label={t("register.phone")}><PhoneInput value={f.phone} onValue={(phone) => setF({ ...f, phone })} required /></Field>
            </div>
            <Field label={t("checkout.address")}><Input value={f.addressLine1} onChange={(e) => setF({ ...f, addressLine1: e.target.value })} required /></Field>
            <Field label={t("checkout.city")}><Input value={f.city} onChange={(e) => setF({ ...f, city: e.target.value })} required /></Field>
            <ErrorText error={add.error} />
            <Button type="submit" loading={add.loading} size="sm">{t("account.addAddress")}</Button>
          </form>
        </Card>
        <Card>
          <h2 className="mb-3 font-medium">{t("shop.wishlist")}</h2>
          {wish.data?.length === 0 ? <Empty>{t("account.noWishlist")}</Empty> : (
            <ul className="space-y-2 text-sm">{wish.data?.map((w) => <li key={w.id} className="flex items-center justify-between rounded-lg border p-3"><Link href={`/products/${w.slug}`} className="font-medium hover:text-brand">{w.name}</Link><button onClick={() => unwish.run(w.id)} className="text-red-600">{t("common.delete")}</button></li>)}</ul>
          )}
        </Card>
      </div>
    </>
  );
}
