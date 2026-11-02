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
import { Avatar, Badge, Button, ErrorText, Loading, Modal, Stat } from "@/components/ui";

type Detail = Client & { cases: { id: string; number: string; title: string; caseType: string; status: string; outcome: string; openedOn: string; court: string; billedMinor: number; paidMinor: number; nextStepAt?: string | null }[];
  bookings: { id: string; code: string; startsAt: string; status: string; topic: string }[]; billedMinor: number; paidMinor: number; balanceMinor: number };
type Statement = { client: Detail; cases: CaseDetail[]; generatedAt: string };

export default function ClientPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale, currency, siteName } = useI18n();
  const router = useRouter();
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
    <>
      <div className="mb-3 print:hidden"><Link href="/dashboard/clients" className="text-sm font-semibold text-slate-500 hover:text-brand">← {t("lw.nav.clients")}</Link></div>
      <div className="mb-6 flex flex-wrap items-center gap-4 rounded-3xl bg-white p-5 shadow-card print:hidden">
        <Avatar name={c.name} className="h-16 w-16 text-xl" />
        <div className="min-w-0 flex-1">
          <h1 className="text-2xl font-extrabold text-ink">{c.kind === "COMPANY" ? "🏢 " : ""}{c.name}</h1>
          <p className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-500">{c.phone && <span dir="ltr">📞 {c.phone}</span>}{c.email && <span>✉️ {c.email}</span>}{c.nationalId && <span>🪪 {c.nationalId}</span>}</p>
          {c.tags.length > 0 && <div className="mt-2 flex flex-wrap gap-1">{c.tags.map((tg) => <span key={tg} className="rounded-full bg-violet-50 px-2 py-0.5 text-xs font-semibold text-violet-700">#{tg}</span>)}</div>}
        </div>
        <div className="flex flex-wrap gap-2">
          {c.phone && <><a href={`tel:${c.phone}`} className="chip">📞 {t("lw.client.call")}</a><a href={`https://wa.me/${c.phone.replace(/[^0-9]/g, "")}`} target="_blank" rel="noopener noreferrer" className="chip">WhatsApp</a></>}
          {canWrite("clients") && <Button size="sm" variant="secondary" onClick={() => setEditing(true)}><Icon name="edit" className="h-3.5 w-3.5" />{t("lw.client.edit")}</Button>}
          {can("legal.finance.read") && <Button size="sm" variant="secondary" onClick={() => setStmt(true)}><Icon name="print" className="h-3.5 w-3.5" />{t("lw.client.statement")}</Button>}
        </div>
      </div>
      <div className="mb-6 grid gap-3 sm:grid-cols-3 print:hidden">
        <Stat label={t("lw.fin.billed")} value={fmt(c.billedMinor)} icon="receipt" />
        <Stat label={t("lw.fin.collected")} value={fmt(c.paidMinor)} icon="wallet" tone="green" />
        <Stat label={t("lw.fin.balance")} value={fmt(c.balanceMinor)} icon="alert" tone={c.balanceMinor > 0 ? "red" : "green"} />
      </div>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] print:hidden">
        <section>
          <div className="mb-3 flex items-center justify-between"><h2 className="font-bold text-ink">{t("lw.nav.cases")} ({c.cases.length})</h2>{canWrite("cases") && <Button size="sm" onClick={() => setNewCase(true)}><Icon name="plus" className="h-3.5 w-3.5" />{t("lw.case.new")}</Button>}</div>
          {c.cases.length === 0 ? <p className="rounded-2xl border border-dashed border-slate-300 bg-white/60 p-6 text-center text-sm text-slate-500">{t("lw.client.noCases")}</p> : (
            <ul className="space-y-2">{c.cases.map((k) => (
              <li key={k.id}><Link href={`/dashboard/cases/${k.id}`} className="flex items-center gap-3 rounded-2xl bg-white p-4 shadow-card transition hover:shadow-lift">
                <div className="min-w-0 flex-1"><p className="font-mono text-xs text-slate-400">#{k.number}</p><p className="truncate font-bold text-ink">{k.title}</p><p className="text-xs text-slate-500">{t(`lw.type.${k.caseType}`)}{k.court ? ` · ${k.court}` : ""}{k.nextStepAt ? ` · 📅 ${dateTime(k.nextStepAt, locale)}` : ""}</p></div>
                <div className="flex flex-col items-end gap-1"><Badge tone={statusTone[k.status]}>{t(`lw.status.${k.status}`)}</Badge>{k.outcome !== "NONE" && <Badge tone={statusTone[k.outcome]}>{t(`lw.outcome.${k.outcome}`)}</Badge>}
                  {k.billedMinor - k.paidMinor > 0 && <span className="text-xs font-bold text-red-600">{fmt(k.billedMinor - k.paidMinor)}</span>}</div>
              </Link></li>
            ))}</ul>
          )}
        </section>
        <section className="space-y-5">
          <ClientAppCard clientId={c.id} phone={c.phone} enabled={!!c.portalEnabled} lastLogin={c.portalLastLoginAt} write={canWrite("clients")} onChanged={() => void reload()} />
          {c.notes && <div className="rounded-2xl bg-white p-5 shadow-card"><p className="mb-2 text-sm font-bold text-slate-700">{t("lw.client.notes")}</p><p className="whitespace-pre-line text-sm text-slate-700">{c.notes}</p></div>}
          <div className="rounded-2xl bg-white p-5 shadow-card">
            <p className="mb-2 text-sm font-bold text-slate-700">{t("lw.client.bookings")}</p>
            {c.bookings.length === 0 ? <p className="text-sm text-slate-500">—</p> : <ul className="space-y-2">{c.bookings.map((b) => <li key={b.id} className="flex items-center justify-between gap-2 text-sm"><span>{dateTime(b.startsAt, locale)}</span><Badge tone={statusTone[b.status]}>{t(`lw.bks.${b.status}`)}</Badge></li>)}</ul>}
          </div>
          {canWrite("clients") && <button type="button" onClick={() => setDel(true)} className="text-sm font-semibold text-red-600">{t("lw.client.delete")}</button>}
        </section>
      </div>
      {editing && <ClientModal initial={c} onClose={() => setEditing(false)} onSaved={async () => { setEditing(false); await reload(); }} />}
      {newCase && <NewCaseFor client={c} onClose={() => setNewCase(false)} onSaved={(k) => router.push(`/dashboard/cases/${k.id}`)} />}
      <Modal open={del} onClose={() => setDel(false)} title={t("lw.client.deleteTitle")}><p className="text-sm text-slate-600">{t("lw.client.deleteBody")}</p><ErrorText error={remove.error} />
        <div className="mt-5 flex gap-2"><Button variant="danger" loading={remove.loading} onClick={() => remove.run()}>{t("offer.delete")}</Button><Button variant="secondary" onClick={() => setDel(false)}>{t("common.cancel")}</Button></div></Modal>
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
    </>
  );
}

function NewCaseFor({ client, onClose, onSaved }: { client: Client; onClose: () => void; onSaved: (k: CaseDetail) => void }) {
  return <CaseModal open onClose={onClose} onSaved={onSaved} clients={[{ id: client.id, name: client.name }]} />;
}
