"use client";

import { localPhone } from "@/lib/phone";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ContactButtons, Fact, PeriodBar, TypeTag, useTypeLabel } from "@/components/admin/kit";
import { use, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { Alert, Button, Card, ErrorText, Field, Input, Loading, Modal, Select, StatusBadge } from "@/components/ui";
import { PhoneInput } from "@/components/inputs";
import { Icon, type IconName } from "@/components/icons";
import { Avatar } from "@/components/ui";
import { dateOnly, dateTime, money } from "@/lib/format";

type Detail = {
  id: string; slug: string; name: string; tenantType: string; status: string; createdAt: string; staffCount: number;
  domains: { host: string; kind: string; primary: boolean; verified: boolean; sslStatus: string }[];
  subscription: { status?: string; planCode?: string; planName?: string; priceMinor?: number; currentPeriodStart?: string; currentPeriodEnd?: string; createdAt?: string };
  owner: { firstName?: string; lastName?: string; phone?: string; email?: string };
  billingState: { state: string; daysLeft?: number };
  payRequests: { id: string; planCode: string; amountMinor: number; currency: string; phone: string; status: string; createdAt: string }[];
  events: { eventType: string; createdAt: string }[];
  usage: { metric: string; period: string; value: number }[];
};

const daysTo = (iso?: string) => (iso ? Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000) : null);

function H({ icon, children }: { icon: IconName; children: React.ReactNode }) {
  return <h2 className="mb-3 flex items-center gap-2 font-bold text-ink"><span className="flex h-8 w-8 items-center justify-center rounded-[9px] bg-brand-soft text-brand"><Icon name={icon} className="h-4 w-4" /></span>{children}</h2>;
}

export default function TenantDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const router = useRouter();
  const { can } = useMe();
  const typeLabel = useTypeLabel();
  const { data, error, loading, reload } = useApi<Detail>(`platform/tenants/${id}`);
  const reason = "";
  const [planCode, setPlanCode] = useState("");
  const [days, setDays] = useState("");   // the days left after the change (final, not added); empty = keep the current end date
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const setStatus = useAction(async (status: string) => { await api(`platform/tenants/${id}/status`, { body: { status, reason } }); await reload(); });
  const changePlan = useAction(async () => { await api(`platform/tenants/${id}/plan`, { body: { planCode, days: Number(days) || undefined } }); await reload(); });
  const [modal, setModal] = useState<"" | "pin" | "owner" | "rename">("");
  const [pin, setPin] = useState("");
  const [ownerPhone, setOwnerPhone] = useState("");
  const [ownerEmail, setOwnerEmail] = useState("");
  const [newName, setNewName] = useState("");
  const [newSlug, setNewSlug] = useState("");
  const resetPw = useAction(async () => { const r = await api<{ pin: string }>(`platform/tenants/${id}/owner/reset-password`, { body: {} }); setPin(r.pin); setModal("pin"); });
  const saveOwner = useAction(async () => { await api(`platform/tenants/${id}/owner`, { method: "PATCH", body: { phone: ownerPhone || undefined, email: ownerEmail || undefined } }); setModal(""); await reload(); });
  const saveRename = useAction(async () => { await api(`platform/tenants/${id}`, { method: "PATCH", body: { name: newName || undefined, slug: newSlug || undefined } }); setModal(""); await reload(); });
  const support = useAction(async () => {
    const r = await api<{ ticket: string; host: string }>(`platform/tenants/${id}/support-access`, { body: {} });
    const port = window.location.port ? `:${window.location.port}` : "";
    window.location.assign(`${window.location.protocol}//${r.host}${port}/api/sso?ticket=${encodeURIComponent(r.ticket)}&next=${encodeURIComponent("/dashboard")}`);
  });
  const purge = useAction(async () => { await api(`platform/tenants/${id}`, { method: "DELETE", body: { confirmSlug: typed } }); router.replace("/admin/tenants"); });
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  const store = data.tenantType === "STORE" || data.tenantType === "SERVICE";
  const plans = store ? ["STORE_STANDARD", "STORE_PRO", "STORE_STANDARD_YEARLY", "STORE_PRO_YEARLY"] : ["CLINIC_PRO", "CLINIC_PRO_YEARLY"];
  const chosen = planCode || data.subscription.planCode?.replace("_FREE", store ? "_STANDARD" : "_PRO") || plans[0];
  const o = data.owner;
  const left = daysTo(data.subscription.currentPeriodEnd);
  const sub = data.subscription;
  return (
    <>
      <nav aria-label="breadcrumb" className="mb-3 flex items-center gap-1.5 text-[12.5px] text-muted">
        <Link href="/admin/tenants" className="font-medium hover:text-ink">{t("admin.tenants")}</Link>
        <Icon name="chevron" className="h-3 w-3 flip-rtl" />
        <span className="truncate text-ink-2">{data.name}</span>
      </nav>
      <header className="mb-5 flex flex-wrap items-center justify-between gap-4 rounded-[14px] bg-surface p-4 shadow-card sm:p-5">
        <div className="flex min-w-0 items-center gap-3.5">
          <Avatar name={data.name} className="h-14 w-14 text-lg" />
          <div className="min-w-0">
            <h1 className="truncate text-xl font-bold text-ink">{data.name}</h1>
            <p className="truncate text-[13px] text-muted" dir="ltr">{data.domains.find((d) => d.primary)?.host ?? data.slug}</p>
            <div className="mt-1.5 flex flex-wrap items-center gap-2.5"><StatusBadge status={data.status} /><TypeTag type={data.tenantType} /><span className="sr-only">{typeLabel(data.tenantType)}</span></div>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {can("platform.support.access") && <Button loading={support.loading} onClick={() => support.run()}><Icon name="shield" className="h-4 w-4" />{t("admin.openSupport")}</Button>}
          {data.status !== "SUSPENDED" ? <Button variant="secondary" loading={setStatus.loading} onClick={() => setStatus.run("SUSPENDED")}>{t("admin.disable")}</Button> : <Button loading={setStatus.loading} onClick={() => setStatus.run("ACTIVE")}>{t("admin.enable")}</Button>}
        </div>
        {!!(setStatus.error || support.error) &&<div className="w-full"><ErrorText error={setStatus.error ?? support.error} /></div>}
      </header>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <div className="min-w-0 space-y-5">
          <Card>
            <H icon="billing">{t("admin.subscription")}</H>
            <div className="flex items-center justify-between gap-3">
              <div><p className="text-xl font-bold text-ink">{planLabel(t, sub.planCode)}</p><p className="tnum text-sm text-muted">{money(sub.priceMinor, "EGP", locale)}</p></div>
              {sub.status && <StatusBadge status={sub.status} />}
            </div>
            <dl className="mt-4 grid grid-cols-2 gap-4 rounded-[12px] bg-surface-2 p-4">
              <Fact label={t("admin.subscribedSince")}>{dateOnly(sub.currentPeriodStart ?? sub.createdAt, locale)}</Fact>
              <Fact label={t("admin.endsOn")}>{dateOnly(sub.currentPeriodEnd, locale)}</Fact>
            </dl>
            <PeriodBar start={sub.currentPeriodStart} end={sub.currentPeriodEnd} className="mt-3" />
            <div className="mt-5 border-t border-line pt-4">
              <p className="mb-2 text-sm font-semibold text-ink">{t("admin.changePlan")}</p>
              <div className="grid gap-2 sm:grid-cols-2">
                <Select value={chosen} onChange={(e) => setPlanCode(e.target.value)}>{plans.map((p) => <option key={p} value={p}>{planLabel(t, p)}</option>)}</Select>
                <Input type="number" min={1} max={3650} value={days} onChange={(e) => setDays(e.target.value)} placeholder={left !== null && left > 0 ? t("admin.daysKeep", { days: String(left) }) : t("admin.days")} dir="ltr" />
              </div>
              <ErrorText error={changePlan.error} />
              <Button className="mt-3" loading={changePlan.loading} onClick={() => changePlan.run()}>{t("admin.apply")}</Button>
            </div>
          </Card>

          <Card>
            <H icon="wallet">{t("admin.payRequests")}</H>
            {data.payRequests.length === 0 ? <p className="text-sm text-muted">{t("admin.noRequests")}</p> : (
              <ul className="divide-y divide-line">{data.payRequests.map((r) => (
                <li key={r.id} className="flex flex-wrap items-center justify-between gap-2 py-2.5 text-sm first:pt-0 last:pb-0"><span className="tnum font-semibold text-ink">{planLabel(t, r.planCode)} · {money(r.amountMinor, r.currency, locale)}</span><span className="flex items-center gap-2"><StatusBadge status={r.status} /><span className="tnum text-xs text-muted">{dateTime(r.createdAt, locale)}</span></span></li>
              ))}</ul>
            )}
          </Card>

          <Card>
            <H icon="clock">{t("admin.history")}</H>
            <ol className="relative max-h-80 space-y-3 overflow-y-auto overflow-x-hidden border-s-2 border-line ps-4">
              {data.events.map((e, i) => <li key={i} className="relative flex flex-wrap items-baseline gap-x-2 text-sm"><span className="absolute -start-[1.4rem] top-1.5 h-2.5 w-2.5 rounded-full bg-brand" /><span className="font-mono text-xs font-semibold text-ink-2">{e.eventType}</span> <span className="tnum text-faint">· {dateTime(e.createdAt, locale)}</span></li>)}
            </ol>
          </Card>
        </div>

        <div className="min-w-0 space-y-5">
          <Card>
            <H icon="users">{t("admin.owner")}</H>
            <p className="font-semibold text-ink">{`${o.firstName ?? ""} ${o.lastName ?? ""}`.trim() || "-"}</p>
            {o.phone && (
              <div className="mt-2 flex items-center justify-between gap-3 rounded-[12px] bg-surface-2 p-3">
                <a className="tnum text-lg font-bold text-brand" dir="ltr" href={`tel:${o.phone}`}>{localPhone(o.phone)}</a>
                <ContactButtons phone={o.phone} name={data.name} size="md" />
              </div>
            )}
            {o.email && <p className="mt-2 flex items-center gap-2 text-sm text-muted" dir="ltr"><Icon name="mail" className="h-4 w-4 text-faint" /><a className="truncate text-brand" href={`mailto:${o.email}`}>{o.email}</a></p>}
            <div className="mt-4 flex flex-wrap gap-2">
              <Button variant="secondary" size="sm" loading={resetPw.loading} onClick={() => resetPw.run()}>{t("admin.resetPassword")}</Button>
              <Button variant="secondary" size="sm" onClick={() => { setOwnerPhone(localPhone(o.phone)); setOwnerEmail(o.email ?? ""); setModal("owner"); }}>{t("admin.editOwner")}</Button>
              <Button variant="secondary" size="sm" onClick={() => { setNewName(data.name); setNewSlug(data.slug); setModal("rename"); }}>{t("admin.editBusiness")}</Button>
            </div>
            <ErrorText error={resetPw.error} />
            <p className="mt-4 text-xs text-muted">{t("admin.signedUp")}: {dateOnly(data.createdAt, locale)} · {t("admin.staff")}: <b className="tnum">{data.staffCount}</b></p>
          </Card>

          <Card>
            <H icon="globe">{t("admin.domains")}</H>
            <ul className="space-y-1.5 text-sm text-ink-2" dir="ltr">{data.domains.map((d) => <li key={d.host} className="flex items-center gap-2"><span className={`h-2 w-2 shrink-0 rounded-full ${d.verified ? "bg-emerald-500" : "bg-amber-500"}`} /><span className="min-w-0 truncate">{d.host}</span>{d.primary && <span className="rounded bg-brand-soft px-1.5 text-[10px] font-bold text-brand">PRIMARY</span>}</li>)}</ul>
            <h3 className="mb-2 mt-5 text-sm font-bold text-ink">{t("admin.usage")}</h3>
            {data.usage.length === 0 ? <p className="text-sm text-muted">-</p> : data.usage.map((u) => <p key={u.metric + u.period} className="text-sm text-muted">{u.metric} ({u.period}): <b className="tnum text-ink">{u.value}</b></p>)}
          </Card>

          {can("platform.tenant.purge") && (
            <Card className="border border-red-200 dark:border-red-500/30">
              <H icon="alert">{t("admin.dangerZone")}</H>
              <p className="mb-3 text-sm text-muted">{t("admin.dangerBody")}</p>
              <Button variant="danger" onClick={() => { setTyped(""); setConfirmOpen(true); }}>{t("admin.deleteNow")}</Button>
            </Card>
          )}
        </div>
      </div>
      <Modal open={modal === "pin"} onClose={() => setModal("")} title={t("admin.resetPassword")}>
        <div className="space-y-3"><Alert tone="green">{t("admin.resetPin", { pin })}</Alert><p className="text-center font-mono text-3xl font-bold tracking-widest" dir="ltr">{pin}</p></div>
      </Modal>
      <Modal open={modal === "owner"} onClose={() => setModal("")} title={t("admin.editOwner")}>
        <div className="space-y-3">
          <Field label={t("admin.ownerPhone")}><PhoneInput value={ownerPhone} onValue={setOwnerPhone} /></Field>
          <Field label={t("admin.ownerEmail")}><Input value={ownerEmail} onChange={(e) => setOwnerEmail(e.target.value)} dir="ltr" type="email" /></Field>
          <ErrorText error={saveOwner.error} />
          <Button className="w-full" loading={saveOwner.loading} onClick={() => saveOwner.run()}>{t("common.save")}</Button>
        </div>
      </Modal>
      <Modal open={modal === "rename"} onClose={() => setModal("")} title={t("admin.editBusiness")}>
        <div className="space-y-3">
          <Field label={t("admin.newName")}><Input value={newName} onChange={(e) => setNewName(e.target.value)} /></Field>
          <Field label={t("admin.newSlug")} hint={t("admin.slugWarn")}><Input value={newSlug} onChange={(e) => setNewSlug(e.target.value.toLowerCase())} dir="ltr" /></Field>
          <ErrorText error={saveRename.error} />
          <Button className="w-full" loading={saveRename.loading} onClick={() => saveRename.run()}>{t("common.save")}</Button>
        </div>
      </Modal>
      <Modal open={confirmOpen} onClose={() => setConfirmOpen(false)} title={t("admin.dangerZone")}>
        <div className="space-y-3">
          <Alert tone="red">{t("admin.dangerBody")}</Alert>
          <Field label={t("admin.typeSlug", { slug: data.slug })}><Input value={typed} onChange={(e) => setTyped(e.target.value)} dir="ltr" autoComplete="off" /></Field>
          <ErrorText error={purge.error} />
          <Button variant="danger" className="w-full" loading={purge.loading} disabled={typed.trim().toLowerCase() !== data.slug} onClick={() => purge.run()}>{t("admin.deleteNow")}</Button>
        </div>
      </Modal>
    </>
  );
}
