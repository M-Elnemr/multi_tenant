"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/client";
import { money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { useToast } from "@/components/toast";
import { Alert, Badge, Button, Empty, Field, Input, Textarea } from "@/components/ui";
import { errText } from "@/components/dashboard/marketing/kit";

type Account = { id: string; name: string; phone: string; email?: string; bio?: string; city?: string; payoutNote?: string };
type Shop = { id: string; status: string; code: string; tenantId: string; shop: string; host: string; percent: number; windowDays: number; holdDays: number; minPayoutMinor: number; terms?: string; visits: number; orders: number; salesMinor: number; pendingMinor: number; approvedMinor: number; availableMinor: number; paidMinor: number; reversedMinor: number; unread: number };
type Listing = { id: string; name: string; host: string; percent: number; windowDays: number; holdDays: number; minPayoutMinor: number; pitch?: string; terms?: string; marketers: number; rating?: number; ratingCount: number; myStatus?: string; autoApprove: boolean };
type Stats = { orders: number; pendingMinor: number; approvedMinor: number; paidMinor: number; reversedMinor: number; clicks: number; conversionPercent: number; byCampaign: { campaign: string; orders: number; amountMinor: number }[] };
type Commission = { id: string; orderNumber: string; baseMinor: number; percent: number; amountMinor: number; status: string; campaign?: string; shop: string; createdAt: string };
type Payout = { id: string; amountMinor: number; status: string; note?: string; evidence?: string; decisionNote?: string; requestedAt: string; shop: string };
type Offer = { id: string; title: string; percent: number; endsAt?: string; shop: string };
type Notice = { id: number; kind: string; text: string; createdAt: string; readAt?: string };
type Msg = { id: number; sender: "SHOP" | "MARKETER"; body: string; createdAt: string };

const TABS = ["overview", "marketplace", "shops", "payouts", "notifications", "profile"] as const;
type Tab = (typeof TABS)[number];
const TONE: Record<string, "green" | "amber" | "red" | "blue" | "slate"> = { PENDING: "amber", APPROVED: "blue", PAID: "green", REVERSED: "red", ACTIVE: "green", REJECTED: "red", BLOCKED: "red", PAUSED: "amber", REQUESTED: "amber", CANCELLED: "slate" };

export function MarketerApp() {
  const { locale, currency } = useI18n();
  const L = (ar: string, en: string) => (locale === "ar" ? ar : en);
  const me = useApi<Account>("marketer/me");
  const [tab, setTab] = useState<Tab>("overview");
  useEffect(() => { if (me.error instanceof ApiError && me.error.status === 401) window.location.assign("/marketer"); }, [me.error]);
  const m = (v: number) => money(v, currency, locale);
  const label: Record<Tab, string> = { overview: L("نظرة عامة", "Overview"), marketplace: L("السوق", "Marketplace"), shops: L("متاجري وروابطي", "My shops & links"), payouts: L("السحب", "Payouts"), notifications: L("الإشعارات", "Notifications"), profile: L("حسابي", "Profile") };
  const notes = useApi<{ unread: number }>("marketer/notifications");
  const logout = async () => { try { await api("marketer/logout", { body: {} }); } finally { window.location.assign("/marketer"); } };
  if (!me.data) return <div className="p-10 text-center text-slate-500">…</div>;
  return (
    <div className="mx-auto max-w-6xl px-4 py-6">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3"><div><p className="text-sm text-slate-500">{L("أهلاً", "Welcome")}</p><h1 className="text-2xl font-extrabold">{me.data.name}</h1></div><Button variant="secondary" onClick={() => void logout()}>{L("خروج", "Sign out")}</Button></div>
      <nav className="mb-6 flex gap-2 overflow-x-auto pb-1" aria-label="sections">{TABS.map((t) => <button key={t} onClick={() => setTab(t)} className={`min-h-11 shrink-0 rounded-full px-4 text-sm font-bold ${tab === t ? "bg-slate-900 text-white" : "bg-white text-slate-600 ring-1 ring-slate-200"}`}>{label[t]}{t === "notifications" && (notes.data?.unread ?? 0) > 0 && <span className="ms-2 rounded-full bg-rose-500 px-1.5 text-xs text-white">{notes.data?.unread}</span>}</button>)}</nav>
      {tab === "overview" && <Overview m={m} L={L} />}
      {tab === "marketplace" && <Marketplace L={L} m={m} onApplied={() => setTab("shops")} />}
      {tab === "shops" && <Shops L={L} m={m} />}
      {tab === "payouts" && <Payouts L={L} m={m} />}
      {tab === "notifications" && <Notifications L={L} reload={notes.reload} />}
      {tab === "profile" && <Profile L={L} account={me.data} reload={me.reload} />}
      <p className="mt-10 text-center text-xs text-slate-400">{L("المنصة مش بتمسك فلوسك: كل متجر بيدفع عمولته مباشرة.", "The platform never holds your money: each shop pays its commission directly.")}</p>
    </div>
  );
}

type Fmt = (v: number) => string;
type LFn = (ar: string, en: string) => string;
const Card = ({ children, className = "" }: { children: React.ReactNode; className?: string }) => <div className={`rounded-2xl border border-slate-200 bg-white p-4 ${className}`}>{children}</div>;
const Stat = ({ label, value, hint }: { label: string; value: string; hint?: string }) => <Card><p className="text-xs font-semibold text-slate-500">{label}</p><p className="mt-1 text-2xl font-extrabold tnum">{value}</p>{hint && <p className="mt-1 text-xs text-slate-400">{hint}</p>}</Card>;
const stars = (r?: number) => (r ? `★ ${r}` : "—");

function Overview({ m, L }: { m: Fmt; L: LFn }) {
  const st = useApi<Stats>("marketer/stats");
  const offers = useApi<Offer[]>("marketer/offers");
  const list = useApi<Commission[]>("marketer/commissions?limit=30");
  const d = st.data;
  if (!d) return <p className="text-slate-500">…</p>;
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={L("نقرات", "Clicks")} value={String(d.clicks)} hint={`${L("تحويل", "Conversion")} ${d.conversionPercent}%`} /><Stat label={L("طلبات", "Orders")} value={String(d.orders)} />
        <Stat label={L("معلّقة", "Pending")} value={m(d.pendingMinor)} hint={L("لحد التسليم وفترة الانتظار", "until delivery and the hold")} /><Stat label={L("معتمدة", "Approved")} value={m(d.approvedMinor)} />
        <Stat label={L("اتدفع", "Paid")} value={m(d.paidMinor)} /><Stat label={L("ملغاة", "Reversed")} value={m(d.reversedMinor)} />
      </div>
      {(offers.data ?? []).length > 0 && <Card><h2 className="mb-2 font-bold">{L("عروض ليك الآن", "Offers for you now")}</h2><ul className="space-y-1 text-sm">{offers.data!.map((o) => <li key={o.id}><b>{o.shop}</b>: {o.title} — <b>{o.percent}%</b></li>)}</ul></Card>}
      <Card><h2 className="mb-3 font-bold">{L("الحملات", "Campaigns")}</h2>{d.byCampaign.length === 0 ? <Empty compact>{L("لسه مفيش طلبات", "No orders yet")}</Empty> : <ul className="divide-y divide-slate-100 text-sm">{d.byCampaign.map((c) => <li key={c.campaign} className="flex justify-between py-2"><span dir="ltr">{c.campaign}</span><span>{c.orders} · {m(c.amountMinor)}</span></li>)}</ul>}</Card>
      <Card><h2 className="mb-3 font-bold">{L("آخر العمولات", "Latest commissions")}</h2>{(list.data ?? []).length === 0 ? <Empty compact>{L("لسه مفيش عمولات", "No commissions yet")}</Empty> : <ul className="divide-y divide-slate-100 text-sm">{list.data!.map((c) => <li key={c.id} className="flex flex-wrap items-center justify-between gap-2 py-2"><span><b dir="ltr">{c.orderNumber}</b> · {c.shop}{c.campaign ? ` · ${c.campaign}` : ""}</span><span className="flex items-center gap-2">{m(c.amountMinor)} <Badge tone={TONE[c.status]}>{c.status}</Badge></span></li>)}</ul>}</Card>
    </div>
  );
}

function Marketplace({ L, m, onApplied }: { L: LFn; m: Fmt; onApplied: () => void }) {
  const toast = useToast();
  const [q, setQ] = useState("");
  const res = useApi<{ data: Listing[]; total: number; closed?: boolean }>(`marketer/marketplace?size=30&q=${encodeURIComponent(q)}`);
  const [msg, setMsg] = useState<Record<string, string>>({});
  const apply = async (l: Listing) => { try { await api(`marketer/shops/${l.id}/apply`, { body: { message: msg[l.id] ?? "" } }); toast(L("اتبعت طلب التقديم", "Application sent")); await res.reload(); onApplied(); } catch (e) { toast(errText(e, L("مش قادر أقدّم", "Could not apply")), { tone: "error" }); } };
  return (
    <div className="space-y-4">
      <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={L("ابحث عن متجر", "Search shops")} />
      {res.data?.closed && <Alert tone="amber">{L("السوق مقفول مؤقتاً", "The marketplace is closed for now")}</Alert>}
      {(res.data?.data ?? []).length === 0 && !res.loading && <Empty>{L("مفيش متاجر مفتوحة للمسوّقين لسه", "No shops are taking marketers yet")}</Empty>}
      <div className="grid gap-4 md:grid-cols-2">{(res.data?.data ?? []).map((l) => (
        <Card key={l.id}>
          <div className="flex items-start justify-between gap-3"><div><h3 className="text-lg font-bold">{l.name}</h3><p className="text-xs text-slate-500" dir="ltr">{l.host}</p></div><div className="text-end"><p className="text-2xl font-extrabold text-emerald-600">{l.percent}%</p><p className="text-xs text-slate-500">{stars(l.rating)} ({l.ratingCount})</p></div></div>
          {l.pitch && <p className="mt-2 text-sm text-slate-700">{l.pitch}</p>}
          <p className="mt-2 text-xs text-slate-500">{L("تذكّر الرابط", "Link memory")} {l.windowDays} {L("يوم", "d")} · {L("انتظار بعد التسليم", "hold")} {l.holdDays} {L("يوم", "d")} · {L("أقل سحب", "min payout")} {m(l.minPayoutMinor)} · {l.marketers} {L("مسوّق", "marketers")}</p>
          {l.terms && <details className="mt-2 text-xs text-slate-600"><summary className="cursor-pointer font-semibold">{L("شروط المتجر", "Shop terms")}</summary><p className="mt-1 whitespace-pre-line">{l.terms}</p></details>}
          {l.myStatus ? <p className="mt-3"><Badge tone={TONE[l.myStatus]}>{l.myStatus}</Badge></p> : (
            <div className="mt-3 space-y-2"><Textarea rows={2} maxLength={500} placeholder={L("رسالة للمتجر (اختياري)", "A note to the shop (optional)")} value={msg[l.id] ?? ""} onChange={(e) => setMsg({ ...msg, [l.id]: e.target.value })} /><Button onClick={() => void apply(l)}>{l.autoApprove ? L("انضم الآن", "Join now") : L("قدّم", "Apply")}</Button></div>
          )}
        </Card>
      ))}</div>
    </div>
  );
}

function Shops({ L, m }: { L: LFn; m: Fmt }) {
  const toast = useToast();
  const shops = useApi<Shop[]>("marketer/shops");
  const [open, setOpen] = useState<string | null>(null);
  const [campaign, setCampaign] = useState("");
  const link = async (s: Shop) => {
    try { const r = await api<{ url: string }>(`marketer/shops/${s.id}/link${campaign ? `?campaign=${encodeURIComponent(campaign)}` : ""}`); try { await navigator.clipboard.writeText(r.url); toast(L("اتنسخ الرابط", "Link copied")); } catch { toast(r.url); } }
    catch (e) { toast(errText(e, L("الرابط مش جاهز", "Link not ready")), { tone: "error" }); }
  };
  const request = async (s: Shop) => { try { await api(`marketer/shops/${s.id}/payouts`, { body: {} }); toast(L("اتبعت طلب السحب للمتجر", "Payout request sent to the shop")); await shops.reload(); } catch (e) { toast(errText(e, L("مش قادر أطلب", "Could not request")), { tone: "error" }); } };
  if (!shops.data) return <p className="text-slate-500">…</p>;
  if (shops.data.length === 0) return <Empty>{L("لسه مقدّمتش على أي متجر. افتح «السوق».", "You have not applied anywhere yet. Open the Marketplace.")}</Empty>;
  return (
    <div className="space-y-4">
      <Field label={L("اسم الحملة للرابط (اختياري، حروف وأرقام)", "Campaign name for the link (optional, letters and digits)")}><Input value={campaign} onChange={(e) => setCampaign(e.target.value)} maxLength={40} dir="ltr" placeholder="insta-story" /></Field>
      {shops.data.map((s) => (
        <Card key={s.id}>
          <div className="flex flex-wrap items-center justify-between gap-2"><div><h3 className="text-lg font-bold">{s.shop} <Badge tone={TONE[s.status]}>{s.status}</Badge></h3><p className="text-xs text-slate-500">{s.percent}% · {s.visits} {L("نقرة", "clicks")} · {s.orders} {L("طلب", "orders")} · {L("مبيعات", "sales")} {m(s.salesMinor)}</p></div>
            <div className="flex flex-wrap gap-2">{s.status === "ACTIVE" && <Button size="sm" onClick={() => void link(s)}>{L("انسخ رابطي", "Copy my link")}</Button>}<Button size="sm" variant="secondary" onClick={() => setOpen(open === s.id ? null : s.id)}>{L("تواصل / تقييم", "Chat / rate")}{s.unread > 0 ? ` (${s.unread})` : ""}</Button></div></div>
          <div className="mt-3 grid grid-cols-2 gap-2 text-sm sm:grid-cols-4"><div><p className="text-xs text-slate-500">{L("معلّقة", "Pending")}</p><b>{m(s.pendingMinor)}</b></div><div><p className="text-xs text-slate-500">{L("متاح للسحب", "Available")}</p><b>{m(s.availableMinor)}</b></div><div><p className="text-xs text-slate-500">{L("اتدفع", "Paid")}</p><b>{m(s.paidMinor)}</b></div><div><p className="text-xs text-slate-500">{L("ملغاة", "Reversed")}</p><b>{m(s.reversedMinor)}</b></div></div>
          {s.availableMinor > 0 && s.availableMinor >= s.minPayoutMinor && s.status === "ACTIVE" && <Button className="mt-3" size="sm" variant="secondary" onClick={() => void request(s)}>{L("اطلب سحب", "Request payout")} {m(s.availableMinor)}</Button>}
          {s.availableMinor > 0 && s.availableMinor < s.minPayoutMinor && <p className="mt-2 text-xs text-amber-600">{L("أقل مبلغ للسحب", "Minimum payout")} {m(s.minPayoutMinor)}</p>}
          {open === s.id && <ShopPanel L={L} s={s} />}
        </Card>
      ))}
    </div>
  );
}

function ShopPanel({ L, s }: { L: LFn; s: Shop }) {
  const toast = useToast();
  const thread = useApi<Msg[]>(`marketer/shops/${s.id}/messages`);
  const [body, setBody] = useState(""), [rating, setRating] = useState(5), [comment, setComment] = useState(""), [reason, setReason] = useState("");
  const send = async () => { try { await api(`marketer/shops/${s.id}/messages`, { body: { body } }); setBody(""); await thread.reload(); } catch (e) { toast(errText(e, L("ما اتبعتش", "Not sent")), { tone: "error" }); } };
  const rate = async () => { try { await api(`marketer/shops/${s.id}/rating`, { body: { rating, comment } }); toast(L("شكراً على تقييمك", "Thanks for rating")); } catch (e) { toast(errText(e, L("ما اتحفظش", "Not saved")), { tone: "error" }); } };
  const dispute = async () => { try { await api(`marketer/shops/${s.id}/disputes`, { body: { reason } }); setReason(""); toast(L("اتفتح نزاع", "Dispute opened")); } catch (e) { toast(errText(e, L("ما اتفتحش", "Not opened")), { tone: "error" }); } };
  return (
    <div className="mt-4 grid gap-4 border-t border-slate-100 pt-4 lg:grid-cols-2">
      <div><h4 className="mb-2 font-bold">{L("المحادثة", "Chat")}</h4><div className="mb-2 max-h-56 space-y-1 overflow-y-auto rounded-xl bg-slate-50 p-2 text-sm">{[...(thread.data ?? [])].reverse().map((x) => <p key={x.id} className={`max-w-[85%] rounded-xl px-3 py-1.5 ${x.sender === "MARKETER" ? "ms-auto bg-emerald-100" : "bg-white ring-1 ring-slate-200"}`}>{x.body}</p>)}{(thread.data ?? []).length === 0 && <p className="text-slate-400">{L("لسه مفيش رسائل", "No messages yet")}</p>}</div>
        <div className="flex gap-2"><Input value={body} onChange={(e) => setBody(e.target.value)} maxLength={1000} placeholder={L("اكتب رسالة", "Write a message")} /><Button disabled={!body.trim()} onClick={() => void send()}>{L("إرسال", "Send")}</Button></div></div>
      <div className="space-y-4">
        <div><h4 className="mb-2 font-bold">{L("قيّم المتجر", "Rate the shop")}</h4><div className="flex flex-wrap items-center gap-2"><select className="min-h-11 rounded-xl border border-slate-200 px-3" value={rating} onChange={(e) => setRating(Number(e.target.value))}>{[5, 4, 3, 2, 1].map((n) => <option key={n} value={n}>{"★".repeat(n)}</option>)}</select><Input value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500} placeholder={L("تعليق (اختياري)", "Comment (optional)")} className="flex-1" /><Button variant="secondary" onClick={() => void rate()}>{L("حفظ", "Save")}</Button></div><p className="mt-1 text-xs text-slate-400">{L("التقييم متاح بعد أول طلب معتمد", "Rating opens after your first approved order")}</p></div>
        <div><h4 className="mb-2 font-bold">{L("نزاع على عمولة", "Dispute")}</h4><div className="flex gap-2"><Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={600} placeholder={L("اشرح المشكلة", "Explain the problem")} /><Button variant="secondary" disabled={!reason.trim()} onClick={() => void dispute()}>{L("افتح", "Open")}</Button></div></div>
      </div>
    </div>
  );
}

function Payouts({ L, m }: { L: LFn; m: Fmt }) {
  const toast = useToast();
  const list = useApi<Payout[]>("marketer/payouts");
  const cancel = async (p: Payout) => { try { await api(`marketer/payouts/${p.id}/cancel`, { body: {} }); await list.reload(); } catch (e) { toast(errText(e, L("ما اتلغاش", "Not cancelled")), { tone: "error" }); } };
  return (
    <div className="space-y-3">
      <Alert tone="blue">{L("طلب السحب بيطلب من المتجر يدفعلك رصيدك المعتمد. مفيش فلوس بتتحرك من المنصة: المتجر بيدفع بنفسه ويسجّل إثبات الدفع هنا.", "A payout request asks the shop to pay your approved balance. No money moves through the platform: the shop pays you itself and records the proof here.")}</Alert>
      {(list.data ?? []).length === 0 ? <Empty>{L("مفيش طلبات سحب", "No payout requests")}</Empty> : list.data!.map((p) => (
        <Card key={p.id}><div className="flex flex-wrap items-center justify-between gap-2"><div><b>{p.shop}</b> · {m(p.amountMinor)} <Badge tone={TONE[p.status]}>{p.status}</Badge><p className="text-xs text-slate-500">{new Date(p.requestedAt).toLocaleDateString()}</p></div>{p.status === "REQUESTED" && <Button size="sm" variant="secondary" onClick={() => void cancel(p)}>{L("إلغاء الطلب", "Cancel request")}</Button>}</div>
          {p.evidence && <p className="mt-2 text-sm text-emerald-700">{L("إثبات الدفع", "Proof of payment")}: {p.evidence}</p>}{p.decisionNote && <p className="mt-2 text-sm text-slate-600">{p.decisionNote}</p>}</Card>
      ))}
    </div>
  );
}

function Notifications({ L, reload }: { L: LFn; reload: () => Promise<void> }) {
  const list = useApi<{ data: Notice[]; unread: number }>("marketer/notifications");
  useEffect(() => { void api("marketer/notifications/read", { body: {} }).then(() => reload()).catch(() => undefined); }, [reload]);
  return (
    <div className="space-y-2">{(list.data?.data ?? []).length === 0 ? <Empty>{L("مفيش إشعارات", "No notifications")}</Empty> : list.data!.data.map((n) => <Card key={n.id} className={n.readAt ? "" : "ring-2 ring-emerald-200"}><p className="text-sm">{n.text}</p><p className="mt-1 text-xs text-slate-400">{new Date(n.createdAt).toLocaleString()}</p></Card>)}</div>
  );
}

function Profile({ L, account, reload }: { L: LFn; account: Account; reload: () => Promise<void> }) {
  const toast = useToast();
  const [f, setF] = useState({ name: account.name, email: account.email ?? "", bio: account.bio ?? "", city: account.city ?? "", payoutNote: account.payoutNote ?? "" });
  const [pw, setPw] = useState({ current: "", next: "" });
  const save = async () => { try { await api("marketer/me", { method: "PUT", body: f }); await reload(); toast(L("اتحفظ", "Saved")); } catch (e) { toast(errText(e, L("ما اتحفظش", "Not saved")), { tone: "error" }); } };
  const change = async () => { try { await api("marketer/password", { body: pw }); toast(L("اتغيّرت كلمة السر، سجّل دخول تاني", "Password changed, sign in again")); window.location.assign("/marketer"); } catch (e) { toast(errText(e, L("ما اتغيّرتش", "Not changed")), { tone: "error" }); } };
  return (
    <div className="grid gap-5 lg:grid-cols-2">
      <Card className="space-y-3"><h2 className="font-bold">{L("بياناتي", "My details")}</h2><p className="text-sm text-slate-500" dir="ltr">{account.phone}</p>
        <Field label={L("الاسم", "Name")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field><Field label={L("المدينة", "City")}><Input value={f.city} onChange={(e) => setF({ ...f, city: e.target.value })} /></Field>
        <Field label={L("نبذة عني وقنواتي", "About me and my channels")}><Textarea rows={3} maxLength={600} value={f.bio} onChange={(e) => setF({ ...f, bio: e.target.value })} /></Field>
        <Field label={L("إزاي تحب المتجر يدفعلك (مثال: رقم InstaPay أو محفظة)", "How you want shops to pay you (e.g. an InstaPay or wallet number)")}><Input value={f.payoutNote} maxLength={300} onChange={(e) => setF({ ...f, payoutNote: e.target.value })} /></Field>
        <Button onClick={() => void save()}>{L("حفظ", "Save")}</Button></Card>
      <Card className="space-y-3"><h2 className="font-bold">{L("كلمة السر", "Password")}</h2>
        <Field label={L("الحالية", "Current")}><Input type="password" value={pw.current} onChange={(e) => setPw({ ...pw, current: e.target.value })} autoComplete="current-password" /></Field>
        <Field label={L("الجديدة (٨ حروف على الأقل)", "New (8+ characters)")}><Input type="password" value={pw.next} onChange={(e) => setPw({ ...pw, next: e.target.value })} autoComplete="new-password" /></Field>
        <Button variant="secondary" disabled={pw.next.length < 8 || !pw.current} onClick={() => void change()}>{L("غيّر كلمة السر", "Change password")}</Button></Card>
    </div>
  );
}
