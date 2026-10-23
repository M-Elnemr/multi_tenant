"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Field, Input, Loading, Modal, Pager, Section, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { AccountStatus, CountryFlag, ProviderMark, Row, type Account, type CatalogItem } from "./common";

type Job = { id: number; type: string; status: string; attempts: number; lastError?: string; createdAt: string; finishedAt?: string };

function Activity({ id }: { id: string }) {
  const { t, locale } = useI18n();
  const { data } = useApi<Job[]>(`store/integrations/accounts/${id}/activity`);
  if (!data) return <Loading />;
  if (data.length === 0) return <p className="text-sm text-slate-500">{t("ch.activity.none")}</p>;
  return (
    <ul className="max-h-72 divide-y divide-slate-100 overflow-y-auto text-sm">
      {data.map((j) => (
        <li key={j.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
          <span><b>{t(`ch.job.${j.type}`)}</b> <span className="text-xs text-slate-400">{dateTime(j.createdAt, locale)}</span>{j.lastError && <span className="block text-xs text-red-600">{j.lastError}</span>}</span>
          <Badge tone={j.status === "DONE" ? "green" : j.status === "FAILED" ? "red" : "blue"}>{t(`ch.jobstatus.${j.status}`)}</Badge>
        </li>
      ))}
    </ul>
  );
}

function EditCredentials({ a, onClose, onDone }: { a: Account; onClose: () => void; onDone: () => void }) {
  const { t, locale } = useI18n();
  const cat = useApi<CatalogItem[]>(`store/integrations/catalog?country=${a.country}`);
  const def = cat.data?.find((c) => c.id === a.provider);
  const [label, setLabel] = useState(a.label);
  const [cred, setCred] = useState<Record<string, string>>({});
  const save = useAction(async () => {
    const filled = Object.fromEntries(Object.entries(cred).filter(([, v]) => v.trim()));
    await api(`store/integrations/accounts/${a.id}`, { method: "PATCH", body: { label, credentials: Object.keys(filled).length ? filled : undefined } });
    onDone();
  });
  return (
    <Modal open onClose={onClose} title={t("ch.editAccess")}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
        <Field label={t("ch.accountName")}><Input value={label} onChange={(e) => setLabel(e.target.value)} /></Field>
        <p className="text-sm text-slate-500">{t("ch.editAccess.hint")}</p>
        {def?.fields.map((f) => (
          <Field key={f.key} label={locale === "ar" ? f.labelAr : f.labelEn}>
            <Input type={f.secret && !f.multiline ? "password" : "text"} dir="ltr" autoComplete="off" placeholder="••••••••" value={cred[f.key] ?? ""} onChange={(e) => setCred({ ...cred, [f.key]: e.target.value })} />
          </Field>
        ))}
        <ErrorText error={save.error} />
        <div className="flex justify-end gap-2"><Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button type="submit" loading={save.loading}>{t("common.save")}</Button></div>
      </form>
    </Modal>
  );
}

export function AccountsView({ accounts, canManage, onChanged, onPick }: { accounts: Account[]; canManage: boolean; onChanged: () => void; onPick: (id: string) => void }) {
  const { t, locale } = useI18n();
  const [msg, setMsg] = useState<{ tone: "green" | "red"; text: string } | null>(null);
  const [edit, setEdit] = useState<Account | null>(null);
  const [confirm, setConfirm] = useState<Account | null>(null);
  const [logOf, setLogOf] = useState<string | null>(null);
  const sync = useAction(async (id: string) => { await api(`store/integrations/accounts/${id}/sync`, { body: { what: "all" } }); setMsg({ tone: "green", text: t("ch.sync.queued") }); onChanged(); });
  const test = useAction(async (id: string) => {
    const r = await api<{ ok: boolean; message?: string }>(`store/integrations/accounts/${id}/test`, { body: {} });
    setMsg({ tone: r.ok ? "green" : "red", text: r.ok ? t("ch.test.ok") : r.message ? `${t("ch.test.fail")}: ${r.message}` : t("ch.test.fail") }); onChanged();
  });
  const remove = useAction(async (id: string) => { await api(`store/integrations/accounts/${id}`, { method: "DELETE" }); setConfirm(null); onChanged(); });
  if (accounts.length === 0) return <Empty>{t("ch.none")} <Link href="/dashboard/channels/connect" className="font-semibold text-brand hover:underline">{t("ch.connectFirst")}</Link></Empty>;
  return (
    <div className="space-y-4">
      {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
      <ErrorText error={sync.error ?? test.error ?? remove.error} />
      {accounts.map((a) => (
        <Card key={a.id} className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <ProviderMark provider={a.provider} name={a.name} className="h-12 w-12" />
            <div className="min-w-0 flex-1">
              <p className="flex flex-wrap items-center gap-2 text-lg font-bold">{a.name} <CountryFlag code={a.country} /> <AccountStatus status={a.status} providerStatus={a.providerStatus} /></p>
              <p className="truncate text-sm text-slate-500">{a.label}</p>
            </div>
            {a.adminUrl && <a href={a.adminUrl} target="_blank" rel="noreferrer" className="rounded-xl bg-brand-soft px-4 py-2 text-sm font-semibold text-brand-dark hover:opacity-90">{t("ch.openAdmin", { name: a.name })} ↗</a>}
          </div>
          {a.status === "ERROR" && a.lastError && <Alert tone="red">{a.lastError}</Alert>}
          <dl className="grid gap-x-8 sm:grid-cols-2">
            <Row label={t("ch.listings")}>{a.listings}</Row>
            <Row label={t("ch.ordersCount")}>{a.orders}{a.newOrders > 0 && <Badge tone="amber">{t("ch.insights.new", { n: a.newOrders })}</Badge>}</Row>
            <Row label={t("ch.synced")}>{a.lastSyncAt ? dateTime(a.lastSyncAt, locale) : t("ch.insights.neverSynced")}</Row>
            <Row label={t("ch.connectedOn")}>{dateTime(a.createdAt, locale)}</Row>
          </dl>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="secondary" onClick={() => onPick(a.id)}>{t("ch.viewInsights")}</Button>
            {canManage && <>
              <Button size="sm" variant="secondary" loading={sync.loading} onClick={() => void sync.run(a.id)}>{t("ch.syncNow")}</Button>
              <Button size="sm" variant="secondary" loading={test.loading} onClick={() => void test.run(a.id)}>{t("ch.testConn")}</Button>
              <Button size="sm" variant="ghost" onClick={() => setEdit(a)}>{t("ch.editAccess")}</Button>
              <Button size="sm" variant="ghost" onClick={() => setLogOf(logOf === a.id ? null : a.id)}>{t("ch.activity")}</Button>
              <Button size="sm" variant="danger" onClick={() => setConfirm(a)}>{t("ch.disconnect")}</Button>
            </>}
          </div>
          {logOf === a.id && <Section title={t("ch.activity")}><Activity id={a.id} /></Section>}
        </Card>
      ))}
      {edit && <EditCredentials a={edit} onClose={() => setEdit(null)} onDone={() => { setEdit(null); onChanged(); }} />}
      {confirm && (
        <Modal open onClose={() => setConfirm(null)} title={t("ch.disconnect")}>
          <p className="mb-4 text-sm">{t("ch.disconnect.body", { name: confirm.name })}</p>
          <div className="flex justify-end gap-2"><Button variant="secondary" onClick={() => setConfirm(null)}>{t("common.cancel")}</Button><Button variant="danger" loading={remove.loading} onClick={() => void remove.run(confirm.id)}>{t("ch.disconnect")}</Button></div>
        </Modal>
      )}
    </div>
  );
}

type Customer = { ref: string; name?: string; phone?: string; email?: string; orders: number; spentMinor: number; currency: string; lastOrderAt?: string; channels: string[] };

export function CustomersView({ accountId }: { accountId: string | null }) {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const qs = new URLSearchParams({ page: String(page) });
  if (q.trim()) qs.set("q", q.trim());
  if (accountId) qs.set("accountId", accountId);
  const { data, loading, error } = useApi<Page<Customer>>(`store/integrations/customers?${qs}`);
  return (
    <div className="space-y-4">
      <Input className="max-w-sm" placeholder={t("ch.customers.search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? <Empty>{t("ch.customers.none")}</Empty> : (
        <>
          <Table head={[t("orders.customer"), t("ch.phone"), t("ch.email"), t("ch.ordersCount"), t("ch.spent"), t("ch.lastOrder")]}>
            {data?.data.map((c) => (
              <tr key={c.ref}>
                <Td className="font-medium">{c.name || "—"}</Td>
                <Td><span dir="ltr">{c.phone || "—"}</span></Td>
                <Td><span dir="ltr" className="text-xs">{c.email || "—"}</span></Td>
                <Td>{c.orders}</Td>
                <Td className="font-semibold">{money(c.spentMinor, c.currency, locale)}</Td>
                <Td>{dateTime(c.lastOrderAt, locale)}</Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </div>
  );
}
