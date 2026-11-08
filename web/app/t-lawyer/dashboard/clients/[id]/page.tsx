"use client";

import Link from "next/link";
import { use, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { dateOnly, dateTime, money } from "@/lib/format";
import { statusTone, type CaseDetail, type Client } from "@/lib/lawyer";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { CaseModal } from "@/components/lawyer/case-form";
import { ClientModal } from "@/components/lawyer/client-modal";
import { ClientAppCard } from "@/components/lawyer/client-app-card";
import { ConfirmDialog } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { BRASS, BRASS_LIGHT, ContactButtons, IVORY, NAVY, caseAccent } from "@/components/lawyer/kit";
import { Avatar, Badge, Button, Empty, ErrorText, KpiStrip, Loading, Modal } from "@/components/ui";

type Detail = Client & { cases: { id: string; number: string; title: string; caseType: string; status: string; outcome: string; openedOn: string; court: string; billedMinor: number; paidMinor: number; nextStepAt?: string | null }[];
  bookings: { id: string; code: string; startsAt: string; status: string; topic: string }[]; billedMinor: number; paidMinor: number; balanceMinor: number };
type Statement = { client: Detail; cases: CaseDetail[]; generatedAt: string };

export default function ClientPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale, currency, siteName } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const { canWrite, can } = useMe();
  const { data, loading, error, reload } = useApi<Detail>(`lawyer/clients/${id}`);
  const [editing, setEditing] = useState(false);
  const [newCase, setNewCase] = useState(false);
  const [stmt, setStmt] = useState(false);
  const [del, setDel] = useState(false);
  const statement = useApi<Statement>(stmt ? `lawyer/clients/${id}/statement` : null);
  const remove = useAction(async () => { await api(`lawyer/clients/${id}`, { method: "DELETE" }); router.replace("/dashboard/clients"); });
  const fmt = (m: number) => money(m, currency, locale);
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  const c = data;
  return (
    <div className="space-y-5">
      <Link href="/dashboard/clients" className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-muted transition hover:text-ink print:hidden"><Icon name="arrow" className="h-3.5 w-3.5 rotate-180 flip-rtl" />{t("lw.nav.clients")}</Link>
      <section className="relative overflow-hidden rounded-[18px] p-5 print:hidden sm:p-6" style={{ background: NAVY, color: IVORY }}>
        <span className="pointer-events-none absolute inset-x-0 bottom-0 h-px" style={{ background: `linear-gradient(90deg, transparent, ${BRASS}, transparent)` }} aria-hidden />
        <div className="flex flex-wrap items-center gap-4">
          <Avatar name={c.name} className="h-16 w-16 text-xl" />
          <div className="min-w-0 flex-1">
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em]" style={{ color: BRASS_LIGHT }}>{t(`lw.client.kind.${c.kind}`)}</p>
            <h1 className="mt-0.5 break-words font-display text-[1.6rem] font-semibold leading-tight">{c.name}</h1>
            <p className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-[13px] opacity-80">{c.phone && <span dir="ltr" className="tnum">{c.phone}</span>}{c.email && <span dir="ltr">{c.email}</span>}{c.nationalId && <span dir="ltr" className="tnum">{c.nationalId}</span>}</p>
            {c.tags.length > 0 && <div className="mt-2 flex flex-wrap gap-1">{c.tags.map((tg) => <span key={tg} className="rounded-full px-2 py-0.5 text-[11.5px] font-semibold" style={{ background: "rgb(217 185 127 / .16)", color: BRASS_LIGHT }}>#{tg}</span>)}</div>}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <ContactButtons phone={c.phone} />
            {canWrite("clients") && <Button size="sm" variant="secondary" onClick={() => setEditing(true)}><Icon name="edit" className="h-3.5 w-3.5" />{t("lw.client.edit")}</Button>}
            {can("legal.finance.read") && <Button size="sm" variant="secondary" onClick={() => setStmt(true)}><Icon name="print" className="h-3.5 w-3.5" />{t("lw.client.statement")}</Button>}
          </div>
        </div>
      </section>
      <div className="print:hidden"><KpiStrip items={[
        { label: t("lw.fin.billed"), value: fmt(c.billedMinor), icon: "receipt", tone: "slate" },
        { label: t("lw.fin.collected"), value: fmt(c.paidMinor), icon: "wallet", tone: "green" },
        { label: t("lw.fin.balance"), value: fmt(c.balanceMinor), icon: "alert", tone: c.balanceMinor > 0 ? "red" : "green" },
      ]} /></div>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] print:hidden">
        <section>
          <div className="mb-3 flex items-center justify-between gap-2"><h2 className="font-display text-[16px] font-semibold text-ink">{t("lw.nav.cases")} <span className="text-muted tnum">({c.cases.length})</span></h2>{canWrite("cases") && <Button size="sm" onClick={() => setNewCase(true)}><Icon name="plus" className="h-3.5 w-3.5" />{t("lw.case.new")}</Button>}</div>
          {c.cases.length === 0 ? <Empty compact icon="briefcase" title={t("lw.client.noCases")} /> : (
            <ul className="space-y-2.5">{c.cases.map((k) => (
              <li key={k.id}><Link href={`/dashboard/cases/${k.id}`} className="relative flex items-center gap-3 overflow-hidden rounded-[16px] bg-surface p-4 shadow-card transition hover:shadow-[0_0_0_1px_var(--line-strong)]">
                <span className="absolute inset-y-0 start-0 w-1" style={{ background: caseAccent(k.status, k.outcome) }} aria-hidden />
                <div className="min-w-0 flex-1"><p className="font-mono text-[11.5px] text-muted">#{k.number}</p><p className="truncate text-[14px] font-semibold text-ink">{k.title}</p><p className="truncate text-xs text-muted">{t(`lw.type.${k.caseType}`)}{k.court ? ` · ${k.court}` : ""}{k.nextStepAt ? ` · ${dateTime(k.nextStepAt, locale)}` : ""}</p></div>
                <div className="flex flex-col items-end gap-1"><Badge tone={statusTone[k.status]}>{t(`lw.status.${k.status}`)}</Badge>{k.outcome !== "NONE" && <Badge tone={statusTone[k.outcome]}>{t(`lw.outcome.${k.outcome}`)}</Badge>}
                  {k.billedMinor - k.paidMinor > 0 && <span className="text-xs font-semibold text-rose-700 tnum">{fmt(k.billedMinor - k.paidMinor)}</span>}</div>
              </Link></li>
            ))}</ul>
          )}
        </section>
        <section className="space-y-5">
          <ClientAppCard clientId={c.id} phone={c.phone} enabled={!!c.portalEnabled} lastLogin={c.portalLastLoginAt} write={canWrite("clients")} onChanged={() => void reload()} />
          {c.notes && <div className="rounded-[18px] bg-surface p-5 shadow-card"><p className="mb-2 text-[14.5px] font-semibold text-ink">{t("lw.client.notes")}</p><p className="whitespace-pre-line text-sm leading-relaxed text-ink-2">{c.notes}</p></div>}
          <div className="rounded-[18px] bg-surface p-5 shadow-card">
            <p className="mb-3 text-[14.5px] font-semibold text-ink">{t("lw.client.bookings")}</p>
            {c.bookings.length === 0 ? <p className="text-sm text-muted">—</p> : <ul className="space-y-2">{c.bookings.map((b) => <li key={b.id} className="flex items-center justify-between gap-2 text-sm text-ink-2"><span className="tnum">{dateTime(b.startsAt, locale)}</span><Badge tone={statusTone[b.status]}>{t(`lw.bks.${b.status}`)}</Badge></li>)}</ul>}
          </div>
          {canWrite("clients") && <Button variant="ghost" size="sm" className="!text-red-600" onClick={() => setDel(true)}>{t("lw.client.delete")}</Button>}
        </section>
      </div>
      {editing && <ClientModal initial={c} onClose={() => setEditing(false)} onSaved={async () => { setEditing(false); toast(t("common.saved")); await reload(); }} />}
      {newCase && <NewCaseFor client={c} onClose={() => setNewCase(false)} onSaved={(k) => router.push(`/dashboard/cases/${k.id}`)} />}
      <ConfirmDialog open={del} onClose={() => setDel(false)} onConfirm={() => void remove.run()} loading={remove.loading} danger title={t("lw.client.deleteTitle")} body={<>{t("lw.client.deleteBody")}<ErrorText error={remove.error} /></>} confirmLabel={t("offer.delete")} cancelLabel={t("common.cancel")} />
      {stmt && (
        <Modal open onClose={() => setStmt(false)} title={t("lw.client.statement")} wide>
          {!statement.data ? <Loading /> : (
            <div className="space-y-4">
              <div id="statement" className="space-y-4 text-sm">
                <div className="flex items-start justify-between border-b border-slate-200 pb-3"><div><p className="text-lg font-extrabold">{siteName}</p><p className="text-slate-500">{t("lw.client.statement")}</p></div><div className="text-end"><p className="font-bold">{c.name}</p><p className="text-slate-500">{dateOnly(statement.data.generatedAt, locale)}</p></div></div>
                {statement.data.cases.map((k) => (
                  <div key={k.id} className="rounded-xl border border-slate-200 p-3">
                    <p className="font-bold">#{k.number} · {k.title}</p>
                    <table className="mt-2 w-full text-xs"><tbody>
                      {k.agreedFeeMinor > 0 && <tr><td className="py-1">{t("lw.case.feeAgreed")}</td><td className="text-end">{fmt(k.agreedFeeMinor)}</td></tr>}
                      {k.actions.filter((a) => a.feeMinor > 0).map((a) => <tr key={a.id}><td className="py-1">{dateOnly(a.actionDate, locale)} · {a.title}</td><td className="text-end">{fmt(a.feeMinor)}</td></tr>)}
                      {k.payments.map((p) => <tr key={p.id} className="text-emerald-700"><td className="py-1">{dateOnly(p.paidOn, locale)} · {t("lw.pay.received")} ({t(`lw.pm.${p.method}`)})</td><td className="text-end">−{fmt(p.amountMinor)}</td></tr>)}
                      <tr className="border-t border-slate-200 font-bold"><td className="py-1">{t("lw.fin.balance")}</td><td className="text-end">{fmt(k.balanceMinor)}</td></tr>
                    </tbody></table>
                  </div>
                ))}
                <p className="text-end text-base font-extrabold">{t("lw.fin.totalDue")}: {fmt(statement.data.cases.reduce((s, k) => s + k.balanceMinor, 0))}</p>
              </div>
              <Button onClick={() => { const w = window.open("", "_blank"); const el = document.getElementById("statement"); if (!w || !el) return; w.document.write(`<html dir="${locale === "ar" ? "rtl" : "ltr"}"><head><title>${c.name}</title><style>body{font-family:system-ui,Cairo,sans-serif;padding:24px}table{width:100%;border-collapse:collapse}td{padding:4px 0}.text-end{text-align:end}div{margin-bottom:12px}</style></head><body>${el.innerHTML}</body></html>`); w.document.close(); w.print(); }}><Icon name="print" className="h-4 w-4" />{t("lw.print")}</Button>
            </div>
          )}
        </Modal>
      )}
    </div>
  );
}

function NewCaseFor({ client, onClose, onSaved }: { client: Client; onClose: () => void; onSaved: (k: CaseDetail) => void }) {
  return <CaseModal open onClose={onClose} onSaved={onSaved} clients={[{ id: client.id, name: client.name }]} />;
}
