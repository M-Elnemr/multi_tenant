"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Badge, Button, Card, ErrorText, Field, Input, Loading, PageHeader, Table, Td } from "@/components/ui";

type Cat = { id: string; name: string; slug: string; isActive: boolean };

export default function Categories() {
  const t = useT();
  const { data, loading, reload, error } = useApi<Cat[]>("store/categories");
  const [name, setName] = useState("");
  const add = useAction(async () => { await api("store/categories", { body: { name } }); setName(""); await reload(); });
  const toggle = useAction(async (c: Cat) => { await api(`store/categories/${c.id}`, { method: "PATCH", body: { active: !c.isActive } }); await reload(); });
  return (
    <>
      <PageHeader title={t("nav.categories")} />
      <Card className="mb-6"><form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="flex items-end gap-3"><div className="flex-1"><Field label={t("products.name")}><Input value={name} onChange={(e) => setName(e.target.value)} required /></Field></div><Button type="submit" loading={add.loading}>{t("common.add")}</Button></form><ErrorText error={add.error} /></Card>
      <ErrorText error={error ?? toggle.error} />
      {loading && !data ? <Loading /> : (
        <Table head={[t("products.name"), "Slug", t("admin.status"), ""]}>
          {data?.map((c) => <tr key={c.id}><Td>{c.name}</Td><Td className="font-mono text-xs">{c.slug}</Td><Td><Badge tone={c.isActive ? "green" : "slate"}>{c.isActive ? t("status.ACTIVE") : t("common.hidden")}</Badge></Td><Td className="text-end"><Button size="sm" variant="ghost" onClick={() => toggle.run(c)}>{c.isActive ? t("common.hide") : t("common.show")}</Button></Td></tr>)}
        </Table>
      )}
    </>
  );
}
