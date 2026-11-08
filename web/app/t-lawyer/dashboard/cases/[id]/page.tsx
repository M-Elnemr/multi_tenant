"use client";

import Link from "next/link";
import { use, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { dateOnly, dateTime, fromMinor, money, toMinor } from "@/lib/format";
import { PAY_METHODS, statusTone, type Action, type CaseDetail, type Step } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Menu } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { uploadFile } from "@/components/uploader";
import { CaseModal } from "@/components/lawyer/case-form";
import { StepFields } from "@/components/lawyer/step-fields";
import { BRASS, BRASS_LIGHT, ContactButtons, DateTile, DotsButton, Eyebrow, Fact, IVORY, NAVY, PaidBar, Rise, useIntl } from "@/components/lawyer/kit";
import { Avatar, Badge, Button, Empty, ErrorText, Field, Input, Loading, Modal, PageHeader, Select, Tabs, Textarea, cx } from "@/components/ui";

type Tab = "overview" | "timeline" | "actions" | "payments" | "documents";
type Del = { kind: "step" | "action" | "payment" | "document" | "case"; id: string; label?: string } | null;
const pad = (n: number) => String(n).padStart(2, "0");
const localNow = () => { const d = new Date(Date.now() + 86400000); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T10:00`; };

export default function CasePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale, currency } = useI18n();
  const intl = useIntl();
  const toast = useToast();
  const router = useRouter();
  const { canWrite, can } = useMe();
  const { data, loading, error, reload } = useApi<CaseDetail>(`lawyer/cases/${id}`);
  const [tab, setTab] = useState<Tab>("overview");
  const [editing, setEditing] = useState(false);
  const [stepEdit, setStepEdit] = useState<Step | "new" | null>(null);
  const [actionEdit, setActionEdit] = useState<Action | "new" | null>(null);
  const [paying, setPaying] = useState(false);
  const [del, setDel] = useState<Del>(null);
  const write = canWrite("cases");
  const fin = can("legal.finance.manage");
  const fmt = (m: number) => money(m, currency, locale);
  const status = useAction(async (body: { status?: string; outcome?: string }) => { await api(`lawyer/cases/${id}/status`, { method: "PUT", body }); await reload(); toast(t("lw.toast.caseUpdated")); });
  const delStep = useAction(async (sid: string) => { await api(`lawyer/cases/${id}/steps/${sid}`, { method: "DELETE" }); await reload(); });
  const toggleStep = useAction(async (s: Step) => { await api(`lawyer/cases/${id}/steps/${s.id}`, { method: "PUT", body: { ...s, done: !s.done } }); await reload(); });
  const delAction = useAction(async (aid: string) => { await api(`lawyer/cases/${id}/actions/${aid}`, { method: "DELETE" }); await reload(); });
  const delPay = useAction(async (pid: string) => { await api(`lawyer/cases/${id}/payments/${pid}`, { method: "DELETE" }); await reload(); });
  const delDoc = useAction(async (did: string) => { await api(`lawyer/cases/${id}/documents/${did}`, { method: "DELETE" }); await reload(); });
  const delCase = useAction(async () => { await api(`lawyer/cases/${id}`, { method: "DELETE" }); router.replace("/dashboard/cases"); });
  const [now] = useState(() => Date.now());
  const fileRef = useRef<HTMLInputElement>(null);
  const upload = useAction(async (f: File) => { const fileId = await uploadFile(f, "LEGAL_DOCUMENT", "TENANT_INTERNAL"); await api(`lawyer/cases/${id}/documents`, { body: { fileId, name: f.name } }); await reload(); toast(t("lw.toast.docAdded")); });
  const confirmDelete = async () => {
    if (!del) return;
    if (del.kind === "case") { await delCase.run(); return; }
    const run = { step: delStep, action: delAction, payment: delPay, document: delDoc }[del.kind];
    await run.run(del.id);
    setDel(null);
    toast(t("lw.toast.deleted"));
  };

  if (loading && !data) return <Loading variant="detail" />;
  if (!data) return <ErrorText error={error} />;
  const k = data;
  const nextStep = k.steps.find((s) => !s.done && new Date(s.startsAt).getTime() > now);
  const paidPct = k.billedMinor > 0 ? Math.min(100, Math.round((k.paidMinor / k.billedMinor) * 100)) : 0;
  const info: [string, string | null | undefined][] = [
    [t("lw.case.type"), t(`lw.type.${k.caseType}`)], [t("lw.case.court"), k.court], [t("lw.case.circuit"), k.circuit], [t("lw.case.courtNo"), k.courtCaseNo],
    [t("lw.case.clientRole"), k.clientRole ? t(`lw.role.${k.clientRole}`) : ""], [t("lw.case.opponent"), k.opponent], [t("lw.case.opponentLawyer"), k.opponentLawyer],
    [t("lw.case.openedOn"), dateOnly(k.openedOn, locale)], [t("lw.case.closedOn"), k.closedOn ? dateOnly(k.closedOn, locale) : ""],
  ];
  const err = status.error ?? delStep.error ?? toggleStep.error ?? delAction.error ?? delPay.error ?? delDoc.error ?? upload.error ?? delCase.error;
  const delBusy = delStep.loading || delAction.loading || delPay.loading || delDoc.loading || delCase.loading;
  const ledger = [
    ...k.actions.filter((a) => a.feeMinor > 0).map((a) => ({ key: "a" + a.id, date: a.actionDate, label: a.title, amount: a.feeMinor, charge: true as const })),
    ...k.payments.map((p) => ({ key: "p" + p.id, date: p.paidOn, label: `${t(`lw.pm.${p.method}`)}${p.clientName ? ` · ${p.clientName}` : ""}${p.note ? ` · ${p.note}` : ""}`, amount: p.amountMinor, charge: false as const, id: p.id })),
  ].sort((a, b) => b.date.localeCompare(a.date));
  const caseMenu = write ? [
    { label: t("lw.case.edit"), icon: "edit", onSelect: () => setEditing(true) },
    "divider" as const,
    { label: t("lw.case.delete"), icon: "close", danger: true, onSelect: () => setDel({ kind: "case", id }) },
  ] : [];
  return (
    <div className="space-y-5">
      <Link href="/dashboard/cases" className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-muted transition hover:text-ink"><Icon name="arrow" className="h-3.5 w-3.5 rotate-180 flip-rtl" />{t("lw.nav.cases")}</Link>
      <PageHeader eyebrow={<Eyebrow>{`#${k.number}${k.courtCaseNo ? ` · ${k.courtCaseNo}` : ""}`}</Eyebrow>} title={k.title} subtitle={`${t(`lw.type.${k.caseType}`)}${k.court ? ` · ${k.court}` : ""}`}
        badge={<><Badge tone={statusTone[k.status]}>{t(`lw.status.${k.status}`)}</Badge>{k.outcome !== "NONE" && <Badge tone={statusTone[k.outcome]}>{t(`lw.outcome.${k.outcome}`)}</Badge>}</>}
        actions={write && <>
          <Button onClick={() => { setTab("timeline"); setStepEdit("new"); }}><Icon name="calendar" className="h-4 w-4" />{t("lw.step.add")}</Button>
          <Menu items={caseMenu} label={t("common.more")} trigger={({ toggle, open }) => <Button variant="secondary" onClick={toggle} aria-expanded={open}><Icon name="edit" className="h-4 w-4" />{t("common.more")}</Button>} />
        </>} />

      {/* Case file summary: next date in the diary, fees and balance */}
      <div className="grid gap-3 md:grid-cols-3">
        <div className="relative overflow-hidden rounded-[18px] p-5" style={{ background: NAVY, color: IVORY }}>
          <span className="pointer-events-none absolute inset-x-0 bottom-0 h-px" style={{ background: `linear-gradient(90deg, transparent, ${BRASS}, transparent)` }} aria-hidden />
          <p className="text-[11px] font-semibold uppercase tracking-[0.12em]" style={{ color: BRASS_LIGHT }}>{t("lw.case.nextStep")}</p>
          {nextStep ? <><p className="mt-2 font-display text-[1.25rem] font-semibold leading-tight">{dateTime(nextStep.startsAt, locale)}</p><p className="mt-1 truncate text-[13px] opacity-80">{t(`lw.cal.${nextStep.kind}`)} · {nextStep.description}</p></> : <p className="mt-2 text-sm opacity-80">{t("lw.case.noNext")}</p>}
        </div>
        <div className="rounded-[18px] bg-surface p-5 shadow-card">
          <p className="text-[12.5px] font-medium text-muted">{t("lw.fin.billed")}</p>
          <p className="mt-2 font-display text-[1.45rem] font-semibold leading-none text-ink tnum">{fmt(k.billedMinor)}</p>
          <PaidBar pct={paidPct} className="mt-3" />
          <p className="mt-1.5 text-xs text-muted tnum">{t("lw.fin.paidOf", { p: paidPct, a: fmt(k.paidMinor) })}</p>
        </div>
        <div className={cx("rounded-[18px] p-5 shadow-card", k.balanceMinor > 0 ? "bg-rose-50 dark:bg-rose-500/10" : "bg-emerald-50 dark:bg-emerald-500/10")}>
          <p className="text-[12.5px] font-medium text-muted">{t("lw.fin.balance")}</p>
          <p className={cx("mt-2 font-display text-[1.45rem] font-semibold leading-none tnum", k.balanceMinor > 0 ? "text-rose-700" : "text-emerald-700")}>{fmt(k.balanceMinor)}</p>
          {fin && <button type="button" onClick={() => setPaying(true)} className="mt-3 inline-flex items-center gap-1 text-[13px] font-semibold text-ink-2 hover:text-ink"><Icon name="plus" className="h-3.5 w-3.5" />{t("lw.pay.add")}</button>}
        </div>
      </div>

      <div className="overflow-x-auto"><Tabs value={tab} onChange={setTab} tabs={[
        { key: "overview", label: t("lw.tab.overview") }, { key: "timeline", label: t("lw.tab.timeline"), count: k.steps.length }, { key: "actions", label: t("lw.tab.actions"), count: k.actions.length },
        { key: "payments", label: t("lw.tab.payments"), count: k.payments.length }, { key: "documents", label: t("lw.tab.documents"), count: k.documents.length },
      ]} /></div>
      <ErrorText error={err} />

      {tab === "overview" && (
        <Rise className="grid gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
          <div className="space-y-5">
            <section className="rounded-[18px] bg-surface p-5 shadow-card">
              <dl className="grid gap-x-6 gap-y-4 sm:grid-cols-2">{info.filter(([, v]) => v).map(([l, v]) => <Fact key={l} label={l}>{v}</Fact>)}</dl>
              {k.description && <p className="mt-5 whitespace-pre-line border-t border-line pt-4 text-sm leading-relaxed text-ink-2">{k.description}</p>}
            </section>
            {write && (
              <section className="rounded-[18px] bg-surface p-5 shadow-card">
                <p className="mb-3 text-[14.5px] font-semibold text-ink">{t("lw.case.changeStatus")}</p>
                <div className="flex flex-wrap gap-2">
                  {(["UNDER_STUDY", "OPEN"] as const).map((s) => <button key={s} type="button" disabled={k.status === s} onClick={() => status.run({ status: s, outcome: "NONE" })} className={cx("chip", k.status === s && "chip-on")}>{t(`lw.status.${s}`)}</button>)}
                  <button type="button" onClick={() => status.run({ status: "CLOSED", outcome: "WON" })} className={cx("chip", k.outcome === "WON" && "chip-on")}>{t("lw.quick.won")}</button>
                  <button type="button" onClick={() => status.run({ status: "CLOSED", outcome: "LOST" })} className={cx("chip", k.outcome === "LOST" && "chip-on")}>{t("lw.quick.lost")}</button>
                  <button type="button" onClick={() => status.run({ status: "CLOSED", outcome: "SETTLED" })} className={cx("chip", k.outcome === "SETTLED" && "chip-on")}>{t("lw.outcome.SETTLED")}</button>
                  <button type="button" onClick={() => status.run({ status: "CLOSED", outcome: "NONE" })} className={cx("chip", k.status === "CLOSED" && k.outcome === "NONE" && "chip-on")}>{t("lw.status.CLOSED")}</button>
                </div>
              </section>
            )}
          </div>
          <div className="space-y-5">
            <section className="rounded-[18px] bg-surface p-5 shadow-card">
              <p className="mb-3 text-[14.5px] font-semibold text-ink">{t("lw.case.clients")}</p>
              {k.clients.length === 0 ? <p className="text-sm text-muted">{t("lw.case.noClients")}</p> : (
                <ul className="space-y-2">{k.clients.map((c) => (
                  <li key={c.id} className="flex items-center gap-3 rounded-[12px] bg-surface-2 p-3">
                    <Avatar name={c.name} className="h-9 w-9 text-xs" />
                    <Link href={`/dashboard/clients/${c.id}`} className="min-w-0 flex-1"><p className="truncate text-[13.5px] font-semibold text-ink">{c.name}</p><p dir="ltr" className="text-start text-xs text-muted tnum">{c.phone}</p></Link>
                    <ContactButtons phone={c.phone} size="sm" />
                  </li>
                ))}</ul>
              )}
            </section>
            {k.notes.length > 0 && <section className="rounded-[18px] bg-surface p-5 shadow-card"><p className="mb-3 text-[14.5px] font-semibold text-ink">{t("lw.case.notes")}</p>
              <ul className="space-y-2">{k.notes.map((n) => <li key={n.id} className="rounded-[12px] border-s-[3px] bg-surface-2 p-3" style={{ borderColor: BRASS }}><p className="text-xs font-semibold text-muted tnum">{dateTime(n.startsAt, locale)}</p><p className="mt-0.5 text-[13.5px] font-semibold text-ink">{n.title}</p>{n.body && <p className="text-sm text-ink-2">{n.body}</p>}</li>)}</ul></section>}
          </div>
        </Rise>
      )}

      {tab === "timeline" && (
        <Rise className="space-y-4">
          {write && <Button onClick={() => setStepEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("lw.step.add")}</Button>}
          {k.steps.length === 0 ? <Empty icon="calendar" title={t("lw.step.none")} /> : (
            <ol className="relative ms-3 space-y-4 border-s-2 ps-7" style={{ borderColor: "color-mix(in srgb, #b08d57 35%, transparent)" }}>
              {k.steps.map((s) => {
                const past = new Date(s.startsAt).getTime() < now;
                const late = past && !s.done;
                const menu = write ? [{ label: t("offer.edit"), icon: "edit", onSelect: () => setStepEdit(s) }, { label: t("offer.delete"), icon: "close", danger: true, onSelect: () => setDel({ kind: "step", id: s.id, label: s.description }) }] : [];
                return (
                  <li key={s.id} className="relative">
                    <button type="button" disabled={!write} onClick={() => toggleStep.run(s)} aria-label={t("lw.step.toggle")} aria-pressed={s.done}
                      className={cx("absolute -start-[41px] top-4 grid h-6 w-6 place-items-center rounded-full ring-4 ring-[var(--page-bg,var(--surface))] transition", s.done ? "bg-emerald-500 text-white" : late ? "bg-rose-500 text-white" : "text-[#14213d]")} style={!s.done && !late ? { background: BRASS_LIGHT } : undefined}>
                      {s.done && <Icon name="check" className="h-3.5 w-3.5" />}
                    </button>
                    <div className={cx("rounded-[16px] bg-surface p-4 shadow-card", s.done && "opacity-75")}>
                      <div className="flex items-start gap-3">
                        <DateTile date={new Date(s.startsAt)} tone={s.done ? "slate" : late ? "amber" : "navy"} size="sm" />
                        <div className="min-w-0 flex-1">
                          <p className="flex flex-wrap items-center gap-x-2 text-[11.5px] font-semibold text-muted"><span style={{ color: "var(--rail-accent-ink)" }}>{t(`lw.cal.${s.kind}`)}</span><span className="tnum">{new Date(s.startsAt).toLocaleTimeString(intl, { hour: "numeric", minute: "2-digit" })}</span>{late && <Badge tone="red">{t("lw.step.overdue")}</Badge>}</p>
                          <p className={cx("mt-0.5 text-[14px] font-semibold text-ink", s.done && "line-through")}>{s.description}</p>
                          {s.location && <p className="mt-0.5 flex items-center gap-1 text-xs text-muted"><Icon name="pin" className="h-3.5 w-3.5" />{s.location}</p>}
                          {s.result && <p className="mt-2 rounded-[10px] bg-surface-2 px-3 py-2 text-[13px] text-ink-2"><b className="font-semibold">{t("lw.step.result")}:</b> {s.result}</p>}
                        </div>
                        {menu.length > 0 && <Menu items={menu} label={t("common.more")} trigger={({ toggle, open }) => <DotsButton onClick={toggle} open={open} label={t("common.more")} />} />}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ol>
          )}
        </Rise>
      )}

      {tab === "actions" && (
        <Rise className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            {write && <Button onClick={() => setActionEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("lw.action.add")}</Button>}
            <p className="text-[13px] text-muted tnum">{t("lw.action.total", { a: fmt(k.actionFeesMinor), f: fmt(k.agreedFeeMinor) })}</p>
          </div>
          {k.actions.length === 0 ? <Empty icon="file" title={t("lw.action.none")} /> : (
            <ul className="space-y-2.5">{k.actions.map((a) => {
              const menu = write ? [{ label: t("offer.edit"), icon: "edit", onSelect: () => setActionEdit(a) }, { label: t("offer.delete"), icon: "close", danger: true, onSelect: () => setDel({ kind: "action", id: a.id, label: a.title }) }] : [];
              return (
                <li key={a.id} className="flex items-start gap-3 rounded-[16px] bg-surface p-4 shadow-card">
                  <DateTile date={new Date(a.actionDate)} tone="slate" size="sm" />
                  <div className="min-w-0 flex-1"><p className="text-[14px] font-semibold text-ink">{a.title}</p>{a.notes && <p className="mt-0.5 whitespace-pre-line text-[13px] leading-relaxed text-muted">{a.notes}</p>}</div>
                  {a.feeMinor > 0 && <p className="shrink-0 text-[14px] font-semibold text-ink tnum">{fmt(a.feeMinor)}</p>}
                  {menu.length > 0 && <Menu items={menu} label={t("common.more")} trigger={({ toggle, open }) => <DotsButton onClick={toggle} open={open} label={t("common.more")} />} />}
                </li>
              );
            })}</ul>
          )}
        </Rise>
      )}

      {tab === "payments" && (
        <Rise className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            {fin ? <Button onClick={() => setPaying(true)}><Icon name="plus" className="h-4 w-4" />{t("lw.pay.add")}</Button> : <span />}
          </div>
          {/* fees ledger */}
          <section className="overflow-hidden rounded-[18px] bg-surface shadow-card">
            <div className="grid grid-cols-3 text-center" style={{ background: NAVY, color: IVORY }}>
              {[[t("lw.fin.billed"), k.billedMinor], [t("lw.fin.collected"), k.paidMinor], [t("lw.fin.balance"), k.balanceMinor]].map(([l, v], i) => (
                <div key={i} className="px-2 py-3.5 [&:not(:first-child)]:border-s [&:not(:first-child)]:border-white/10">
                  <p className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: BRASS_LIGHT }}>{l}</p>
                  <p className="mt-1 font-display text-[15px] font-semibold tnum sm:text-[18px]">{fmt(v as number)}</p>
                </div>
              ))}
            </div>
            <ul className="divide-y divide-line">
              {k.agreedFeeMinor > 0 && (
                <li className="flex items-center gap-3 px-4 py-3">
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[10px] bg-surface-2 text-muted"><Icon name="receipt" className="h-4 w-4" /></span>
                  <span className="min-w-0 flex-1 text-[13.5px] font-semibold text-ink">{t("lw.case.feeAgreed")}</span>
                  <span className="text-[14px] font-semibold text-ink tnum">{fmt(k.agreedFeeMinor)}</span>
                  <span className="w-9 shrink-0" />
                </li>
              )}
              {ledger.map((r) => (
                <li key={r.key} className="flex items-center gap-3 px-4 py-3">
                  <span className={cx("grid h-9 w-9 shrink-0 place-items-center rounded-[10px]", r.charge ? "bg-surface-2 text-muted" : "bg-emerald-50 text-emerald-700")}><Icon name={r.charge ? "receipt" : "wallet"} className="h-4 w-4" /></span>
                  <span className="min-w-0 flex-1"><span className="block truncate text-[13.5px] font-semibold text-ink">{r.charge ? r.label : t("lw.pay.received")}</span><span className="block truncate text-xs text-muted">{dateOnly(r.date, locale)}{r.charge ? "" : ` · ${r.label}`}</span></span>
                  <span className={cx("text-[14px] font-semibold tnum", r.charge ? "text-ink" : "text-emerald-700")}>{r.charge ? "" : "−"}{fmt(r.amount)}</span>
                  {!r.charge && fin ? <Menu items={[{ label: t("offer.delete"), icon: "close", danger: true, onSelect: () => setDel({ kind: "payment", id: r.id!, label: fmt(r.amount) }) }]} label={t("common.more")} trigger={({ toggle, open }) => <DotsButton onClick={toggle} open={open} label={t("common.more")} />} /> : <span className="w-9 shrink-0" />}
                </li>
              ))}
            </ul>
            {ledger.length === 0 && k.agreedFeeMinor === 0 && <div className="p-4"><Empty compact icon="wallet" title={t("lw.pay.none")} /></div>}
          </section>
        </Rise>
      )}

      {tab === "documents" && (
        <Rise className="space-y-4">
          {write && <div className="flex flex-wrap items-center gap-3"><input ref={fileRef} type="file" accept="image/jpeg,image/png,application/pdf" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload.run(f); e.target.value = ""; }} />
            <Button loading={upload.loading} onClick={() => fileRef.current?.click()}><Icon name="plus" className="h-4 w-4" />{t("lw.doc.upload")}</Button><p className="text-xs text-muted">{t("lw.doc.hint")}</p></div>}
          {k.documents.length === 0 ? <Empty icon="folder" title={t("lw.doc.none")} /> : (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{k.documents.map((d) => {
              const pdf = d.contentType.includes("pdf");
              return (
                <div key={d.id} className="flex items-center gap-3 rounded-[16px] bg-surface p-4 shadow-card">
                  <span className={cx("grid h-11 w-11 shrink-0 place-items-center rounded-[12px]", pdf ? "bg-rose-50 text-rose-700" : "bg-sky-50 text-sky-700")}><Icon name={pdf ? "file" : "camera"} className="h-5 w-5" /></span>
                  <a href={`/api/bff${d.url.replace("/api/v1", "")}`} target="_blank" rel="noopener noreferrer" className="min-w-0 flex-1"><p className="truncate text-[13.5px] font-semibold text-ink hover:text-brand">{d.name}</p><p className="text-xs text-muted tnum">{dateOnly(d.createdAt, locale)}</p></a>
                  {write && <Menu items={[{ label: t("offer.delete"), icon: "close", danger: true, onSelect: () => setDel({ kind: "document", id: d.id, label: d.name }) }]} label={t("common.more")} trigger={({ toggle, open }) => <DotsButton onClick={toggle} open={open} label={t("common.more")} />} />}
                </div>
              );
            })}</div>
          )}
        </Rise>
      )}

      {editing && <CaseModal open initial={k} onClose={() => setEditing(false)} onSaved={async () => { setEditing(false); toast(t("common.saved")); await reload(); }} />}
      {stepEdit && <StepModal caseId={id} step={stepEdit === "new" ? null : stepEdit} onClose={() => setStepEdit(null)} onSaved={async () => { setStepEdit(null); toast(t("common.saved")); await reload(); }} />}
      {actionEdit && <ActionModal caseId={id} action={actionEdit === "new" ? null : actionEdit} onClose={() => setActionEdit(null)} onSaved={async () => { setActionEdit(null); toast(t("common.saved")); await reload(); }} />}
      {paying && <PaymentModal k={k} onClose={() => setPaying(false)} onSaved={async () => { setPaying(false); toast(t("lw.toast.paymentAdded")); await reload(); }} />}
      <ConfirmDialog open={!!del} onClose={() => setDel(null)} onConfirm={() => void confirmDelete()} loading={delBusy} danger
        title={del?.kind === "case" ? t("lw.case.deleteTitle") : t("lw.confirm.deleteTitle")} body={del?.kind === "case" ? t("lw.case.deleteBody") : del?.label} confirmLabel={del?.kind === "case" ? t("lw.case.delete") : t("offer.delete")} cancelLabel={t("common.cancel")} />
    </div>
  );
}

const toLocal = (iso: string) => { const d = new Date(iso); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };

function StepModal({ caseId, step, onClose, onSaved }: { caseId: string; step: Step | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const fe = useFieldErrors();
  const [f, setF] = useState({ kind: step?.kind ?? "HEARING", startsAt: step ? toLocal(step.startsAt) : localNow(), durationMinutes: String(step?.durationMinutes ?? 60), description: step?.description ?? "", location: step?.location ?? "", result: step?.result ?? "", done: step?.done ?? false });
  const save = useAction(async () => {
    if (!fe.check({ description: !f.description.trim() && t("srv.Describe this step"), startsAt: !f.startsAt && t("srv.Choose the date and time") })) return;
    const body = { kind: f.kind, startsAt: new Date(f.startsAt).toISOString(), durationMinutes: Number(f.durationMinutes), description: f.description, location: f.location, result: f.result, done: f.done };
    try { if (step) await api(`lawyer/cases/${caseId}/steps/${step.id}`, { method: "PUT", body }); else await api(`lawyer/cases/${caseId}/steps`, { body }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });
  return (
    <Modal open onClose={onClose} title={step ? t("lw.step.edit") : t("lw.step.add")}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
        <StepFields f={f} set={(x) => { setF((o) => ({ ...o, ...x })); for (const k of Object.keys(x)) fe.clear(k); }} err={fe.err} />
        {step && <><Field label={t("lw.step.result")}><Textarea rows={2} value={f.result} onChange={(e) => setF({ ...f, result: e.target.value })} placeholder={t("lw.step.resultPh")} /></Field>
          <label className="flex items-center gap-2 text-sm font-semibold"><input type="checkbox" checked={f.done} onChange={(e) => setF({ ...f, done: e.target.checked })} className="h-5 w-5 accent-[var(--brand)]" />{t("lw.step.done")}</label></>}
        <p className="rounded-[12px] bg-surface-2 px-3 py-2 text-xs font-medium text-ink-2 ring-1 ring-line">{t("lw.step.remindNote")}</p>
        {!fe.any && <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}

function ActionModal({ caseId, action, onClose, onSaved }: { caseId: string; action: Action | null; onClose: () => void; onSaved: () => void }) {
  const { t, currency } = useI18n();
  const fe = useFieldErrors();
  const today = new Date();
  const [f, setF] = useState({ actionDate: action?.actionDate ?? `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`, title: action?.title ?? "", notes: action?.notes ?? "", fee: action?.feeMinor ? fromMinor(action.feeMinor) : "" });
  const save = useAction(async () => {
    if (!fe.check({ title: !f.title.trim() && t("srv.Describe what was done") })) return;
    const body = { actionDate: f.actionDate, title: f.title, notes: f.notes, feeMinor: f.fee ? toMinor(f.fee) : 0 };
    try { if (action) await api(`lawyer/cases/${caseId}/actions/${action.id}`, { method: "PUT", body }); else await api(`lawyer/cases/${caseId}/actions`, { body }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });
  return (
    <Modal open onClose={onClose} title={action ? t("lw.action.edit") : t("lw.action.add")}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
        <div className="flex flex-wrap gap-1.5">{["lw.action.s1", "lw.action.s2", "lw.action.s3", "lw.action.s4", "lw.action.s5"].map((s) => <button key={s} type="button" className="chip" onClick={() => setF({ ...f, title: t(s) })}>{t(s)}</button>)}</div>
        <Field name="title" error={fe.err("title")} label={t("lw.action.title")}><Input value={f.title} onChange={(e) => { setF({ ...f, title: e.target.value }); fe.clear("title"); }} /></Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("lw.action.date")}><Input type="date" value={f.actionDate} onChange={(e) => setF({ ...f, actionDate: e.target.value })} /></Field>
          <Field name="feeMinor" error={fe.err("feeMinor")} label={t("lw.action.fee", { c: currency })} hint={t("offer.optional")}><Input dir="ltr" inputMode="decimal" value={f.fee} onChange={(e) => setF({ ...f, fee: e.target.value })} /></Field>
        </div>
        <Field label={t("lw.action.notes")}><Textarea rows={4} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></Field>
        {!fe.any && <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}

function PaymentModal({ k, onClose, onSaved }: { k: CaseDetail; onClose: () => void; onSaved: () => void }) {
  const { t, currency } = useI18n();
  const fe = useFieldErrors();
  const today = new Date();
  const [f, setF] = useState({ amount: k.balanceMinor > 0 ? fromMinor(k.balanceMinor) : "", paidOn: `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`, method: "CASH", note: "", clientId: k.clients[0]?.id ?? "" });
  const save = useAction(async () => {
    if (!fe.check({ amountMinor: !(Number(f.amount) > 0) && t("srv.Enter the amount received") })) return;
    try { await api(`lawyer/cases/${k.id}/payments`, { body: { amountMinor: toMinor(f.amount), paidOn: f.paidOn, method: f.method, note: f.note, clientId: f.clientId || null } }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });
  return (
    <Modal open onClose={onClose} title={t("lw.pay.add")}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
        <Field name="amountMinor" error={fe.err("amountMinor")} label={t("lw.pay.amount", { c: currency })}><Input dir="ltr" inputMode="decimal" className="text-lg font-bold" value={f.amount} onChange={(e) => { setF({ ...f, amount: e.target.value }); fe.clear("amountMinor"); }} /></Field>
        <div className="flex flex-wrap gap-1.5">{PAY_METHODS.map((m) => <button key={m} type="button" onClick={() => setF({ ...f, method: m })} className={`chip ${f.method === m ? "chip-on" : ""}`}>{t(`lw.pm.${m}`)}</button>)}</div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("lw.pay.date")}><Input type="date" value={f.paidOn} onChange={(e) => setF({ ...f, paidOn: e.target.value })} /></Field>
          {k.clients.length > 1 && <Field label={t("lw.pay.from")}><Select value={f.clientId} onChange={(e) => setF({ ...f, clientId: e.target.value })}>{k.clients.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select></Field>}
        </div>
        <Field label={t("lw.pay.note")}><Input value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} /></Field>
        {!fe.any && <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}
