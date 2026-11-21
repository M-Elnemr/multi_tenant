"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { useToast } from "@/components/toast";
import { Alert, Badge, Button, Card, Empty, ErrorText, Field, Input, KpiStrip, PageHeader, Section, Table, Textarea } from "@/components/ui";
import { errText } from "@/components/dashboard/marketing/kit";
import { Applications, DisputesReviews, Messages, Offers, Payouts, useL } from "@/components/dashboard/affiliate-extra";

type Program = { enabled: boolean; percent: number; windowDays: number; holdDays: number; minPayoutMinor: number; terms: string; listed: boolean; autoApprove: boolean; pitch: string };
type Partner = { id: string; name: string; phone?: string; code: string; percent: number | null; status: "ACTIVE" | "PAUSED"; visits: number; orders: number; salesMinor: number; pendingMinor: number; owedMinor: number; paidMinor: number };
type Summary = { orders: number; salesMinor: number; pendingMinor: number; owedMinor: number; paidMinor: number; reversedMinor: number; partners: number; visits: number };
type Overview = { program: Program; summary: Summary; partners: Partner[] };
type Commission = { id: string; orderNumber: string; baseMinor: number; percent: number; amountMinor: number; status: string; createdAt: string; partnerName: string; code: string };

const TONE: Record<string, "green" | "amber" | "red" | "blue" | "slate"> = { PENDING: "amber", APPROVED: "blue", PAID: "green", REVERSED: "red" };

export default function AffiliatePage() {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const ov = useApi<Overview>("store/affiliate");
  const [status, setStatus] = useState("");
  const L = useL();
  const [tab, setTab] = useState<"home" | "applications" | "offers" | "payouts" | "messages" | "disputes">("home");
  const badges = useApi<{ applications: number; payouts: number; messages: number; disputes: number }>("store/affiliate/badges");
  const list = useApi<Commission[]>(`store/affiliate/commissions?limit=100${status ? `&status=${status}` : ""}`);
  const d = ov.data;
  const [form, setForm] = useState<Program | null>(null);
  const prog = form ?? d?.program ?? null;
  const [name, setName] = useState(""), [phone, setPhone] = useState(""), [rate, setRate] = useState("");
  const m = (v: number) => money(v, currency, locale);
  const origin = typeof window === "undefined" ? "" : window.location.origin;

  const save = useAction(async () => {
    if (!prog) return;
    try { await api("store/affiliate/program", { method: "PUT", body: { ...prog, minPayoutMinor: Math.round(prog.minPayoutMinor) } }); setForm(null); await ov.reload(); toast(t("aff.saved")); }
    catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  const add = useAction(async () => {
    try { await api("store/affiliate/partners", { body: { name, phone: phone || undefined, percent: rate ? Number(rate) : undefined } }); setName(""); setPhone(""); setRate(""); await ov.reload(); }
    catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  const patch = async (p: Partner, body: object) => { try { await api(`store/affiliate/partners/${p.id}`, { method: "PATCH", body }); await ov.reload(); } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); } };
  const pay = async (p: Partner) => {
    if (!window.confirm(t("aff.payConfirm", { name: p.name, amount: m(p.owedMinor) }))) return;
    try { await api(`store/affiliate/partners/${p.id}/payout`, { body: {} }); await ov.reload(); await list.reload(); toast(t("aff.paid")); }
    catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  };
  const copy = async (p: Partner) => { try { await navigator.clipboard.writeText(`${origin}/?ref=${p.code}`); toast(t("aff.copied")); } catch { toast(`${origin}/?ref=${p.code}`); } };

  return (
    <>
      <PageHeader eyebrow={t("navgrp.marketing")} title={t("aff.title")} subtitle={t("aff.subtitle")} />
      <ErrorText error={ov.error} />
      {d && (
        <>
          <KpiStrip items={[
            { label: t("aff.kOrders"), value: String(d.summary.orders) }, { label: t("aff.kSales"), value: m(d.summary.salesMinor) },
            { label: t("aff.kPending"), value: m(d.summary.pendingMinor), hint: t("aff.kPendingHint") }, { label: t("aff.kOwed"), value: m(d.summary.owedMinor), hint: t("aff.kOwedHint") }, { label: t("aff.kPaid"), value: m(d.summary.paidMinor) },
          ]} className="mb-6" />
          <div className="mb-5 flex gap-2 overflow-x-auto pb-1">{([["home", L("المسوّقون", "Marketers"), 0], ["applications", L("الطلبات", "Applications"), badges.data?.applications ?? 0], ["offers", L("العروض", "Offers"), 0], ["payouts", L("السحب", "Payouts"), badges.data?.payouts ?? 0], ["messages", L("الرسائل", "Messages"), badges.data?.messages ?? 0], ["disputes", L("نزاعات وتقييمات", "Disputes & reviews"), badges.data?.disputes ?? 0]] as const).map(([k, lab, n]) => <button key={k} onClick={() => setTab(k)} className={`min-h-11 shrink-0 rounded-full px-4 text-sm font-bold ${tab === k ? "bg-ink text-white" : "bg-soft text-muted"}`}>{lab}{n > 0 ? <span className="ms-2 rounded-full bg-rose-500 px-1.5 text-xs text-white">{n}</span> : null}</button>)}</div>
          {tab === "applications" && <Applications />}
          {tab === "offers" && <Offers partners={d.partners.map((p) => ({ id: p.id, name: p.name }))} />}
          {tab === "payouts" && <Payouts />}
          {tab === "messages" && <Messages />}
          {tab === "disputes" && <DisputesReviews />}
          {tab === "home" && prog && (
            <Section title={t("aff.program")} subtitle={t("aff.programHint")} className="mb-6">
              <label className="mb-4 flex min-h-11 items-center gap-3 font-semibold"><input type="checkbox" className="h-5 w-5" checked={prog.enabled} onChange={(e) => setForm({ ...prog, enabled: e.target.checked })} />{t("aff.enable")}</label>
              <label className="mb-2 flex min-h-11 items-center gap-3 font-semibold"><input type="checkbox" className="h-5 w-5" checked={prog.listed} onChange={(e) => setForm({ ...prog, listed: e.target.checked })} />{L("اظهر في سوق المسوّقين (يقدر أي مسوّق يقدّم)", "List my shop in the marketer marketplace (any marketer can apply)")}</label>
              <label className="mb-4 flex min-h-11 items-center gap-3 font-semibold"><input type="checkbox" className="h-5 w-5" checked={prog.autoApprove} onChange={(e) => setForm({ ...prog, autoApprove: e.target.checked })} />{L("اقبل المسوّقين تلقائياً", "Approve marketers automatically")}</label>
              <Field label={L("سطر تعريفي في السوق", "One line shown in the marketplace")} className="mb-4"><Input value={prog.pitch ?? ""} maxLength={300} onChange={(e) => setForm({ ...prog, pitch: e.target.value })} /></Field>
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <Field label={t("aff.percent")} hint={t("aff.percentHint")}><Input type="number" min={0} max={50} step="0.5" value={prog.percent} onChange={(e) => setForm({ ...prog, percent: Number(e.target.value) })} /></Field>
                <Field label={t("aff.window")} hint={t("aff.windowHint")}><Input type="number" min={1} max={365} value={prog.windowDays} onChange={(e) => setForm({ ...prog, windowDays: Number(e.target.value) })} /></Field>
                <Field label={t("aff.hold")} hint={t("aff.holdHint")}><Input type="number" min={0} max={90} value={prog.holdDays} onChange={(e) => setForm({ ...prog, holdDays: Number(e.target.value) })} /></Field>
                <Field label={t("aff.min")} hint={t("aff.minHint")}><Input type="number" min={0} value={prog.minPayoutMinor / 100} onChange={(e) => setForm({ ...prog, minPayoutMinor: Number(e.target.value) * 100 })} /></Field>
              </div>
              <Field label={t("aff.terms")} className="mt-4"><Textarea rows={3} value={prog.terms ?? ""} onChange={(e) => setForm({ ...prog, terms: e.target.value })} /></Field>
              <div className="mt-4 flex items-center gap-3"><Button loading={save.loading} disabled={!form} onClick={() => void save.run()}>{t("aff.save")}</Button><span className="text-sm text-muted">{t("aff.noMoney")}</span></div>
            </Section>
          )}
          {tab === "home" && <><Section title={t("aff.partners")} className="mb-6">
            <div className="mb-4 grid gap-3 sm:grid-cols-[1.4fr_1fr_.7fr_auto] sm:items-end">
              <Field label={t("aff.name")}><Input value={name} onChange={(e) => setName(e.target.value)} maxLength={160} /></Field>
              <Field label={t("aff.phone")}><Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" dir="ltr" /></Field>
              <Field label={t("aff.ownRate")}><Input type="number" min={0} max={50} step="0.5" value={rate} placeholder={String(d.program.percent)} onChange={(e) => setRate(e.target.value)} /></Field>
              <Button loading={add.loading} disabled={!name.trim()} onClick={() => void add.run()}>{t("aff.add")}</Button>
            </div>
            <ErrorText error={add.error} />
            {d.partners.length === 0 ? <Empty>{t("aff.noPartners")}</Empty> : (
              <Table head={[t("aff.name"), t("aff.link"), t("aff.visits"), t("aff.orders"), t("aff.kSales"), t("aff.kOwed"), t("aff.kPaid"), ""]}>
                {d.partners.map((p) => (
                  <tr key={p.id} className="border-t border-line">
                    <td className="p-3"><b>{p.name}</b>{p.status === "PAUSED" && <> <Badge tone="amber">{t("aff.paused")}</Badge></>}<div className="text-xs text-muted" dir="ltr">{p.phone} · {p.percent ?? d.program.percent}%</div></td>
                    <td className="p-3"><button className="min-h-11 rounded-lg bg-soft px-3 font-mono text-sm" onClick={() => void copy(p)} dir="ltr">{p.code} ⧉</button></td>
                    <td className="p-3 tnum">{p.visits}</td><td className="p-3 tnum">{p.orders}</td><td className="p-3 tnum">{m(p.salesMinor)}</td><td className="p-3 tnum"><b>{m(p.owedMinor)}</b></td><td className="p-3 tnum">{m(p.paidMinor)}</td>
                    <td className="p-3"><div className="flex flex-wrap gap-2">
                      <Button size="sm" variant="secondary" disabled={p.owedMinor <= 0} onClick={() => void pay(p)}>{t("aff.markPaid")}</Button>
                      <Button size="sm" variant="ghost" onClick={() => void patch(p, { status: p.status === "ACTIVE" ? "PAUSED" : "ACTIVE" })}>{p.status === "ACTIVE" ? t("aff.pause") : t("aff.resume")}</Button>
                    </div></td>
                  </tr>
                ))}
              </Table>
            )}
          </Section>
          <Section title={t("aff.commissions")}>
            <div className="mb-3 flex flex-wrap gap-2">{["", "PENDING", "APPROVED", "PAID", "REVERSED"].map((s) => <Button key={s || "all"} size="sm" variant={status === s ? "primary" : "secondary"} onClick={() => setStatus(s)}>{s ? t(`aff.s.${s}`) : t("aff.all")}</Button>)}</div>
            {(list.data ?? []).length === 0 ? <Empty>{t("aff.noCommissions")}</Empty> : (
              <Table head={[t("aff.order"), t("aff.name"), t("aff.base"), "%", t("aff.amount"), t("aff.status")]}>
                {(list.data ?? []).map((c) => (
                  <tr key={c.id} className="border-t border-line"><td className="p-3 font-mono text-sm" dir="ltr">{c.orderNumber}</td><td className="p-3">{c.partnerName}</td><td className="p-3 tnum">{m(c.baseMinor)}</td><td className="p-3 tnum">{c.percent}</td><td className="p-3 tnum"><b>{m(c.amountMinor)}</b></td><td className="p-3"><Badge tone={TONE[c.status]}>{t(`aff.s.${c.status}`)}</Badge></td></tr>
                ))}
              </Table>
            )}
          </Section>
          <Card className="mt-6 p-4"><Alert tone="blue">{t("aff.legal")}</Alert></Card></>}
        </>
      )}
    </>
  );
}
