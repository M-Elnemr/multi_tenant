"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Alert, Avatar, Badge, Button, Empty, ErrorText, Field, Input, Pager, Table, Td, Toolbar } from "@/components/ui";
import { SearchBox } from "@/components/dashboard/filters";
import { CardSkeletons, errText } from "@/components/dashboard/marketing/kit";
import { dateTime, money } from "@/lib/format";
import { AccountStatus, CountryFlag, ProviderMark, Row, type Account, type CatalogItem } from "./common";

type Job = { id: number; type: string; status: string; attempts: number; lastError?: string; createdAt: string; finishedAt?: string };

function Activity({ id }: { id: string }) {
  const { t, locale } = useI18n();
  const { data } = useApi<Job[]>(`store/integrations/accounts/${id}/activity`);
  if (!data) return <div className="skeleton h-24 rounded-[14px]" />;
  if (data.length === 0) return <p className="text-sm text-muted">{t("ch.activity.none")}</p>;
  return (
    <ul className="max-h-72 divide-y divide-line overflow-y-auto text-sm">
      {data.map((j) => (
        <li key={j.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
          <span><b>{t(`ch.job.${j.type}`)}</b> <span className="text-xs text-faint">{dateTime(j.createdAt, locale)}</span>{j.lastError && <span className="block text-xs text-red-600">{j.lastError}</span>}</span>
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
  const toast = useToast();
  const save = useAction(async () => {
    const filled = Object.fromEntries(Object.entries(cred).filter(([, v]) => v.trim()));
    await api(`store/integrations/accounts/${a.id}`, { method: "PATCH", body: { label, credentials: Object.keys(filled).length ? filled : undefined } });
    toast(t("common.saved"));
    onDone();
  });
  return (
    <Sheet open onClose={onClose} title={t("ch.editAccess")}
      footer={<div className="flex justify-end gap-2"><Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button variant="ink" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button></div>}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
        <Field label={t("ch.accountName")}><Input value={label} onChange={(e) => setLabel(e.target.value)} /></Field>
        <p className="text-sm text-muted">{t("ch.editAccess.hint")}</p>
        {def?.fields.map((f) => (
          <Field key={f.key} label={locale === "ar" ? f.labelAr : f.labelEn}>
            <Input type={f.secret && !f.multiline ? "password" : "text"} dir="ltr" autoComplete="off" placeholder="••••••••" value={cred[f.key] ?? ""} onChange={(e) => setCred({ ...cred, [f.key]: e.target.value })} />
          </Field>
        ))}
        <ErrorText error={save.error} />
      </form>
    </Sheet>
  );
}

export function AccountsView({ accounts, canManage, onChanged, onPick }: { accounts: Account[]; canManage: boolean; onChanged: () => void; onPick: (id: string) => void }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [edit, setEdit] = useState<Account | null>(null);
  const [confirm, setConfirm] = useState<Account | null>(null);
  const [logOf, setLogOf] = useState<string | null>(null);
  const sync = useAction(async (id: string) => { try { await api(`store/integrations/accounts/${id}/sync`, { body: { what: "all" } }); toast(t("ch.sync.queued")); onChanged(); } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); } });
  const test = useAction(async (id: string) => {
    try {
      const r = await api<{ ok: boolean; message?: string }>(`store/integrations/accounts/${id}/test`, { body: {} });
      toast(r.ok ? t("ch.test.ok") : r.message ? `${t("ch.test.fail")}: ${r.message}` : t("ch.test.fail"), { tone: r.ok ? "success" : "error" }); onChanged();
    } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  const remove = useAction(async (id: string) => { try { await api(`store/integrations/accounts/${id}`, { method: "DELETE" }); toast(t("chx.disconnected")); setConfirm(null); onChanged(); } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); } });
  if (accounts.length === 0) return <Empty icon="link" title={t("ch.none")} action={<Link href="/dashboard/channels/connect" className="inline-flex h-10 items-center rounded-[10px] bg-ink px-4 text-sm font-semibold text-surface">{t("ch.connectFirst")}</Link>} />;
  return (
    <div className="space-y-4">
      {accounts.map((a) => (
        <section key={a.id} className="space-y-4 rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
          <div className="flex flex-wrap items-center gap-3">
            <ProviderMark provider={a.provider} name={a.name} className="h-12 w-12" />
            <div className="min-w-0 flex-1">
              <p className="flex flex-wrap items-center gap-2 font-display text-[16px] font-semibold text-ink">{a.name} <CountryFlag code={a.country} /> <AccountStatus status={a.status} providerStatus={a.providerStatus} /></p>
              <p className="truncate text-[13px] text-muted">{a.label}</p>
            </div>
            {a.adminUrl && <a href={a.adminUrl} target="_blank" rel="noreferrer" className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-3 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-surface-2">{t("ch.openAdmin", { name: a.name })}<Icon name="external" className="h-3.5 w-3.5" /></a>}
          </div>
          {a.status === "ERROR" && a.lastError && <Alert tone="red">{a.lastError}</Alert>}
          <dl className="grid gap-x-8 rounded-[14px] bg-canvas px-4 ring-1 ring-line sm:grid-cols-2">
            <Row label={t("ch.listings")}><span className="tnum">{a.listings}</span></Row>
            <Row label={t("ch.ordersCount")}><span className="inline-flex items-center gap-2"><span className="tnum">{a.orders}</span>{a.newOrders > 0 && <Badge tone="amber">{t("ch.insights.new", { n: a.newOrders })}</Badge>}</span></Row>
            <Row label={t("ch.synced")}>{a.lastSyncAt ? dateTime(a.lastSyncAt, locale) : t("ch.insights.neverSynced")}</Row>
            <Row label={t("ch.connectedOn")}>{dateTime(a.createdAt, locale)}</Row>
          </dl>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="ink" onClick={() => onPick(a.id)}><Icon name="chart" className="h-4 w-4" />{t("ch.viewInsights")}</Button>
            {canManage && <>
              <Button size="sm" variant="secondary" loading={sync.loading} onClick={() => void sync.run(a.id)}><Icon name="return" className="h-4 w-4" />{t("ch.syncNow")}</Button>
              <Button size="sm" variant="secondary" loading={test.loading} onClick={() => void test.run(a.id)}><Icon name="pulse" className="h-4 w-4" />{t("ch.testConn")}</Button>
              <Button size="sm" variant="ghost" onClick={() => setEdit(a)}>{t("ch.editAccess")}</Button>
              <Button size="sm" variant="ghost" onClick={() => setLogOf(logOf === a.id ? null : a.id)}>{t("ch.activity")}</Button>
              <Button size="sm" variant="ghost" className="ms-auto text-red-600" onClick={() => setConfirm(a)}>{t("ch.disconnect")}</Button>
            </>}
          </div>
          {logOf === a.id && <div className="rounded-[14px] p-4 ring-1 ring-line"><p className="mb-2 font-display text-[14px] font-semibold text-ink">{t("ch.activity")}</p><Activity id={a.id} /></div>}
        </section>
      ))}
      {edit && <EditCredentials a={edit} onClose={() => setEdit(null)} onDone={() => { setEdit(null); onChanged(); }} />}
      <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} danger loading={remove.loading} onConfirm={() => confirm && void remove.run(confirm.id)}
        title={t("ch.disconnect")} body={confirm ? t("ch.disconnect.body", { name: confirm.name }) : undefined} confirmLabel={t("ch.disconnect")} cancelLabel={t("common.cancel")} />
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
      <Toolbar><SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("ch.customers.search")} /></Toolbar>
      <ErrorText error={error} />
      {loading && !data ? <CardSkeletons n={6} className="space-y-2" h="h-14" /> : data && data.data.length === 0 ? <Empty icon="users" title={t("ch.customers.none")} /> : (
        <>
          <Table head={[t("orders.customer"), t("ch.phone"), t("ch.email"), t("ch.ordersCount"), t("ch.spent"), t("ch.lastOrder")]}>
            {data?.data.map((c) => (
              <tr key={c.ref}>
                <Td><span className="flex items-center gap-3"><Avatar name={c.name || "?"} className="h-8 w-8 text-[12px]" /><span className="font-semibold text-ink">{c.name || "—"}</span></span></Td>
                <Td><span dir="ltr" className="tnum">{c.phone || "—"}</span></Td>
                <Td><span dir="ltr" className="text-xs text-muted">{c.email || "—"}</span></Td>
                <Td><span className="tnum">{c.orders}</span></Td>
                <Td className="font-semibold tnum">{money(c.spentMinor, c.currency, locale)}</Td>
                <Td><span className="text-muted">{dateTime(c.lastOrderAt, locale)}</span></Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </div>
  );
}
