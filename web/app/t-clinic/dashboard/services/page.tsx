"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money, toMinor } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Button, Card, ErrorText, Field, Input, Loading, PageHeader, Table, Td } from "@/components/ui";

type Service = { id: string; name: string; description?: string; durationMinutes: number; priceMinor?: number; currency: string; isActive: boolean };

export default function Services() {
  const { t, locale } = useI18n();
  const { can } = useMe();
  const { data, loading, reload, error } = useApi<Service[]>("clinic/services");
  const [f, setF] = useState({ name: "", durationMinutes: "30", price: "" });
  const add = useAction(async () => {
    await api("clinic/services", { body: { name: f.name, durationMinutes: Number(f.durationMinutes), priceMinor: f.price ? toMinor(f.price) : undefined } });
    setF({ name: "", durationMinutes: "30", price: "" });
    await reload();
  });
  const toggle = useAction(async (s: Service) => { await api(`clinic/services/${s.id}/active`, { method: "PUT", body: { active: !s.isActive } }); await reload(); });
  return (
    <>
      <PageHeader title={t("nav.services")} subtitle={t("services.hint")} />
      {can("schedule.manage") && (
        <Card className="mb-6">
          <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="grid gap-3 sm:grid-cols-4">
            <Field label={t("products.name")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>
            <Field label={t("services.duration")}><Input type="number" min={5} value={f.durationMinutes} onChange={(e) => setF({ ...f, durationMinutes: e.target.value })} dir="ltr" required /></Field>
            <Field label={t("products.price")} hint={t("services.priceHint")}><Input value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} inputMode="decimal" dir="ltr" /></Field>
            <div className="flex items-end"><Button type="submit" loading={add.loading} className="w-full">{t("common.add")}</Button></div>
          </form>
          <ErrorText error={add.error} />
        </Card>
      )}
      <ErrorText error={error ?? toggle.error} />
      {loading && !data ? <Loading /> : (
        <Table head={[t("products.name"), t("services.duration"), t("products.price"), t("admin.status"), ""]}>
          {data?.map((s) => (
            <tr key={s.id}>
              <Td>{s.name}</Td><Td>{s.durationMinutes} {t("clinic.minutes")}</Td><Td>{s.priceMinor ? money(s.priceMinor, s.currency, locale) : "-"}</Td>
              <Td><Badge tone={s.isActive ? "green" : "slate"}>{s.isActive ? t("status.ACTIVE") : t("common.hidden")}</Badge></Td>
              <Td className="text-end">{can("schedule.manage") && <Button size="sm" variant="ghost" onClick={() => toggle.run(s)}>{s.isActive ? t("common.hide") : t("common.show")}</Button>}</Td>
            </tr>
          ))}
        </Table>
      )}
    </>
  );
}
