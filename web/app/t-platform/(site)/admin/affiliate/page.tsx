"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { useToast } from "@/components/toast";
import { Badge, Button, Empty, ErrorText, Input, KpiStrip, PageHeader, Section, Table } from "@/components/ui";
import { errText } from "@/components/dashboard/marketing/kit";

type Paged<T> = { data: T[]; total: number; page: number; size: number };
type Row = Record<string, string | number | boolean | null | undefined>;
type Overview = Record<string, number> & { settings: { registrationOpen: boolean; marketplaceOpen: boolean; maxPercent: number } };
const TABS = ["overview", "marketers", "shops", "payouts", "flags", "disputes", "audit"] as const;
type Tab = (typeof TABS)[number];
const TONE: Record<string, "green" | "amber" | "red" | "blue" | "slate"> = { ACTIVE: "green", SUSPENDED: "red", OPEN: "amber", DISMISSED: "slate", CONFIRMED: "red", REQUESTED: "amber", PAID: "green", REJECTED: "red", CANCELLED: "slate", RESOLVED: "green", HIGH: "red", MEDIUM: "amber", LOW: "slate" };

export default function AffiliateAdmin() {
  const { locale } = useI18n();
  const L = (ar: string, en: string) => (locale === "ar" ? ar : en);
  const [tab, setTab] = useState<Tab>("overview");
  const names: Record<Tab, string> = { overview: L("نظرة عامة", "Overview"), marketers: L("المسوّقون", "Marketers"), shops: L("المتاجر", "Shops"), payouts: L("السحب", "Payouts"), flags: L("اشتباه احتيال", "Fraud flags"), disputes: L("النزاعات", "Disputes"), audit: L("سجل التدقيق", "Audit trail") };
  return (
    <>
      <PageHeader title={L("برنامج المسوّقين", "Affiliate program")} subtitle={L("إشراف على كل المتاجر والمسوّقين والدفعات والاحتيال", "Oversight of every shop, marketer, payout and fraud signal")} />
      <div className="mb-5 flex gap-2 overflow-x-auto pb-1">{TABS.map((k) => <button key={k} onClick={() => setTab(k)} className={`min-h-11 shrink-0 rounded-full px-4 text-sm font-bold ${tab === k ? "bg-ink text-white" : "bg-soft text-muted"}`}>{names[k]}</button>)}</div>
      {tab === "overview" && <OverviewTab L={L} />}
      {tab === "marketers" && <Marketers L={L} />}
      {tab === "shops" && <List L={L} path="shops" head={[L("المتجر", "Shop"), L("شغّال", "On"), L("معروض", "Listed"), "%", L("مسوّقون", "Marketers"), L("طلبات", "Orders"), L("مستحق", "Owed"), L("اتدفع", "Paid"), L("سحب مفتوح", "Open payouts")]} cols={["name", "enabled", "listed", "percent", "marketers", "orders", "owedMinor", "paidMinor", "payoutsOpen"]} search />}
      {tab === "payouts" && <List L={L} path="payouts" statuses={["", "REQUESTED", "PAID", "REJECTED", "CANCELLED"]} head={[L("المتجر", "Shop"), L("المسوّق", "Marketer"), L("المبلغ", "Amount"), L("الحالة", "Status"), L("العمر (يوم)", "Age (d)"), L("الإثبات", "Proof")]} cols={["shop", "marketer", "amountMinor", "status", "ageDays", "evidence"]} search />}
      {tab === "flags" && <Flags L={L} />}
      {tab === "disputes" && <List L={L} path="disputes" statuses={["", "OPEN", "RESOLVED"]} head={[L("المتجر", "Shop"), L("المسوّق", "Marketer"), L("فتحه", "Opened by"), L("السبب", "Reason"), L("الحالة", "Status")]} cols={["shop", "marketer", "openedBy", "reason", "status"]} />}
      {tab === "audit" && <List L={L} path="audit" head={[L("الوقت", "When"), L("الفاعل", "Actor"), L("الإجراء", "Action"), L("المتجر", "Shop"), L("المسوّق", "Marketer"), L("تفاصيل", "Detail")]} cols={["createdAt", "actorType", "action", "shop", "marketer", "detail"]} search />}
    </>
  );
}

function cell(k: string, v: Row[string], m: (n: number) => string): React.ReactNode {
  if (v === null || v === undefined || v === "") return "—";
  if (typeof v === "boolean") return v ? "✓" : "—";
  if (k.endsWith("Minor") && typeof v === "number") return m(v);
  if (k === "createdAt" && typeof v === "string") return new Date(v).toLocaleString();
  if (["status", "severity"].includes(k) && typeof v === "string") return <Badge tone={TONE[v]}>{v}</Badge>;
  return String(v);
}

function List({ L, path, head, cols, statuses, search }: { L: (a: string, e: string) => string; path: string; head: string[]; cols: string[]; statuses?: string[]; search?: boolean }) {
  const { locale, currency } = useI18n();
  const m = (n: number) => money(n, currency, locale);
  const [q, setQ] = useState(""), [status, setStatus] = useState(""), [page, setPage] = useState(0);
  const res = useApi<Paged<Row>>(`platform/affiliate/${path}?page=${page}&size=20&q=${encodeURIComponent(q)}${status ? `&status=${status}` : ""}`);
  const d = res.data;
  return (
    <Section title="">
      <div className="mb-3 flex flex-wrap gap-2">{search && <Input className="max-w-xs" value={q} placeholder={L("بحث", "Search")} onChange={(e) => { setQ(e.target.value); setPage(0); }} />}{(statuses ?? []).map((s) => <Button key={s || "all"} size="sm" variant={status === s ? "primary" : "secondary"} onClick={() => { setStatus(s); setPage(0); }}>{s || L("الكل", "All")}</Button>)}</div>
      <ErrorText error={res.error} />
      {d && d.data.length === 0 ? <Empty>{L("مفيش نتائج", "No results")}</Empty> : d && (
        <>
          <Table head={head}>{d.data.map((r, i) => <tr key={String(r.id ?? i)} className="border-t border-line">{cols.map((c) => <td key={c} className="max-w-xs truncate p-3">{cell(c, r[c], m)}</td>)}</tr>)}</Table>
          <div className="mt-3 flex items-center justify-between text-sm text-muted"><span>{d.total} {L("نتيجة", "results")}</span><span className="flex gap-2"><Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>‹</Button><Button size="sm" variant="secondary" disabled={(page + 1) * d.size >= d.total} onClick={() => setPage(page + 1)}>›</Button></span></div>
        </>
      )}
    </Section>
  );
}

function OverviewTab({ L }: { L: (a: string, e: string) => string }) {
  const { locale, currency } = useI18n(); const toast = useToast();
  const m = (n: number) => money(n, currency, locale);
  const ov = useApi<Overview>("platform/affiliate/overview");
  const d = ov.data;
  const [cap, setCap] = useState("");
  const set = async (body: object) => { try { await api("platform/affiliate/settings", { method: "PUT", body }); await ov.reload(); toast(L("اتحفظ", "Saved")); } catch (e) { toast(errText(e, L("ما اتحفظش", "Not saved")), { tone: "error" }); } };
  if (!d) return <ErrorText error={ov.error} />;
  return (
    <div className="space-y-5">
      <KpiStrip items={[{ label: L("مسوّقون", "Marketers"), value: String(d.marketers) }, { label: L("متاجر شغّالة / معروضة", "Shops on / listed"), value: `${d.shopsEnabled} / ${d.shopsListed}` }, { label: L("طلبات بعمولة", "Orders with commission"), value: String(d.orders) }, { label: L("مبيعات", "Sales"), value: m(d.salesMinor) }, { label: L("اشتباه مفتوح", "Open flags"), value: String(d.flagsOpen) }]} />
      <KpiStrip items={[{ label: L("معلّقة", "Pending"), value: m(d.pendingMinor) }, { label: L("معتمدة (مستحقة على المتاجر)", "Approved (owed by shops)"), value: m(d.approvedMinor) }, { label: L("اتدفعت", "Paid"), value: m(d.paidMinor) }, { label: L("ملغاة", "Reversed"), value: m(d.reversedMinor) }, { label: L("سحب مفتوح", "Open payouts"), value: `${d.payoutsOpen} · ${m(d.payoutsOpenMinor)}` }]} />
      <Section title={L("مفاتيح البرنامج", "Program switches")} subtitle={L("المنصة مش بتمسك أي فلوس: دي إعدادات فقط", "The platform holds no money: these are only settings")}>
        <div className="flex flex-wrap items-center gap-3">
          <Button variant={d.settings.registrationOpen ? "primary" : "secondary"} onClick={() => void set({ registrationOpen: !d.settings.registrationOpen })}>{L("تسجيل المسوّقين", "Marketer registration")}: {d.settings.registrationOpen ? L("مفتوح", "open") : L("مقفول", "closed")}</Button>
          <Button variant={d.settings.marketplaceOpen ? "primary" : "secondary"} onClick={() => void set({ marketplaceOpen: !d.settings.marketplaceOpen })}>{L("السوق", "Marketplace")}: {d.settings.marketplaceOpen ? L("مفتوح", "open") : L("مقفول", "closed")}</Button>
          <span className="flex items-center gap-2">{L("أعلى نسبة عمولة %", "Max commission %")}: <b>{d.settings.maxPercent}</b><Input className="w-24" type="number" min={1} max={50} value={cap} onChange={(e) => setCap(e.target.value)} /><Button size="sm" variant="secondary" disabled={!cap} onClick={() => void set({ maxPercent: Number(cap) })}>{L("حفظ", "Save")}</Button></span>
        </div>
      </Section>
    </div>
  );
}

function Marketers({ L }: { L: (a: string, e: string) => string }) {
  const { locale, currency } = useI18n(); const toast = useToast();
  const m = (n: number) => money(n, currency, locale);
  const [q, setQ] = useState(""), [status, setStatus] = useState(""), [page, setPage] = useState(0);
  const res = useApi<Paged<Row>>(`platform/affiliate/marketers?page=${page}&size=20&q=${encodeURIComponent(q)}${status ? `&status=${status}` : ""}`);
  const toggle = async (r: Row) => { try { await api(`platform/affiliate/marketers/${r.id}/status`, { body: { status: r.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE" } }); await res.reload(); } catch (e) { toast(errText(e, L("ما اتنفّذش", "Failed")), { tone: "error" }); } };
  const d = res.data;
  return (
    <Section title="">
      <div className="mb-3 flex flex-wrap gap-2"><Input className="max-w-xs" value={q} placeholder={L("اسم أو رقم", "Name or phone")} onChange={(e) => { setQ(e.target.value); setPage(0); }} />{["", "ACTIVE", "SUSPENDED"].map((s) => <Button key={s || "all"} size="sm" variant={status === s ? "primary" : "secondary"} onClick={() => { setStatus(s); setPage(0); }}>{s || L("الكل", "All")}</Button>)}</div>
      {d && d.data.length === 0 ? <Empty>{L("مفيش نتائج", "No results")}</Empty> : d && (
        <>
          <Table head={[L("الاسم", "Name"), L("الموبايل", "Phone"), L("متاجر", "Shops"), L("مفتوح", "Open"), L("اتدفع", "Paid"), L("اشتباه", "Flags"), L("الحالة", "Status"), ""]}>{d.data.map((r) => <tr key={String(r.id)} className="border-t border-line"><td className="p-3 font-semibold">{r.name}</td><td className="p-3" dir="ltr">{r.phone}</td><td className="p-3">{r.shops}</td><td className="p-3 tnum">{m(Number(r.openMinor))}</td><td className="p-3 tnum">{m(Number(r.paidMinor))}</td><td className="p-3">{Number(r.openFlags) > 0 ? <Badge tone="red">{r.openFlags}</Badge> : "—"}</td><td className="p-3"><Badge tone={TONE[String(r.status)]}>{r.status}</Badge></td><td className="p-3"><Button size="sm" variant="secondary" onClick={() => void toggle(r)}>{r.status === "ACTIVE" ? L("إيقاف", "Suspend") : L("تفعيل", "Activate")}</Button></td></tr>)}</Table>
          <div className="mt-3 flex items-center justify-between text-sm text-muted"><span>{d.total}</span><span className="flex gap-2"><Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>‹</Button><Button size="sm" variant="secondary" disabled={(page + 1) * d.size >= d.total} onClick={() => setPage(page + 1)}>›</Button></span></div>
        </>
      )}
    </Section>
  );
}

function Flags({ L }: { L: (a: string, e: string) => string }) {
  const toast = useToast();
  const [q, setQ] = useState(""), [status, setStatus] = useState("OPEN"), [kind, setKind] = useState(""), [page, setPage] = useState(0);
  const res = useApi<Paged<Row>>(`platform/affiliate/flags?page=${page}&size=20&status=${status}&kind=${kind}&q=${encodeURIComponent(q)}`);
  const review = async (r: Row, decision: "DISMISSED" | "CONFIRMED", suspend: boolean) => { const note = window.prompt(L("ملاحظة (اختياري)", "Note (optional)")) ?? ""; try { await api(`platform/affiliate/flags/${r.id}/review`, { body: { decision, note, suspendMarketer: suspend } }); await res.reload(); } catch (e) { toast(errText(e, L("ما اتنفّذش", "Failed")), { tone: "error" }); } };
  const d = res.data;
  return (
    <Section title="">
      <div className="mb-3 flex flex-wrap gap-2"><Input className="max-w-xs" value={q} placeholder={L("بحث", "Search")} onChange={(e) => { setQ(e.target.value); setPage(0); }} />{["OPEN", "CONFIRMED", "DISMISSED", ""].map((s) => <Button key={s || "all"} size="sm" variant={status === s ? "primary" : "secondary"} onClick={() => { setStatus(s); setPage(0); }}>{s || L("الكل", "All")}</Button>)}
        <select className="min-h-11 rounded-xl border border-line bg-transparent px-3" value={kind} onChange={(e) => { setKind(e.target.value); setPage(0); }}><option value="">{L("كل الأنواع", "All kinds")}</option>{["SELF_REFERRAL", "DUPLICATE_CUSTOMER", "BURST", "SHARED_ADDRESS", "HIGH_REVERSAL", "CLICK_FLOOD"].map((k) => <option key={k}>{k}</option>)}</select></div>
      {d && d.data.length === 0 ? <Empty>{L("مفيش اشتباهات", "No flags")}</Empty> : d && (
        <>
          <Table head={[L("النوع", "Kind"), L("الخطورة", "Severity"), L("المتجر", "Shop"), L("المسوّق", "Marketer"), L("التفاصيل", "Detail"), L("الحالة", "Status"), ""]}>{d.data.map((r) => <tr key={String(r.id)} className="border-t border-line"><td className="p-3 font-mono text-xs">{r.kind}</td><td className="p-3"><Badge tone={TONE[String(r.severity)]}>{r.severity}</Badge></td><td className="p-3">{r.shop}</td><td className="p-3">{r.marketer}</td><td className="max-w-xs p-3 text-sm">{r.detail}</td><td className="p-3"><Badge tone={TONE[String(r.status)]}>{r.status}</Badge></td>
            <td className="p-3">{r.status === "OPEN" && <div className="flex flex-wrap gap-1"><Button size="sm" variant="secondary" onClick={() => void review(r, "DISMISSED", false)}>{L("تجاهل", "Dismiss")}</Button><Button size="sm" onClick={() => void review(r, "CONFIRMED", false)}>{L("تأكيد", "Confirm")}</Button><Button size="sm" variant="secondary" onClick={() => void review(r, "CONFIRMED", true)}>{L("تأكيد + إيقاف", "Confirm + suspend")}</Button></div>}</td></tr>)}</Table>
          <div className="mt-3 flex items-center justify-between text-sm text-muted"><span>{d.total}</span><span className="flex gap-2"><Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>‹</Button><Button size="sm" variant="secondary" disabled={(page + 1) * d.size >= d.total} onClick={() => setPage(page + 1)}>›</Button></span></div>
        </>
      )}
    </Section>
  );
}
