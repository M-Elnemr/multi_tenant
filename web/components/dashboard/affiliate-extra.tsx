"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { useToast } from "@/components/toast";
import { Alert, Badge, Button, Empty, Field, Input, Section, Table } from "@/components/ui";
import { errText } from "@/components/dashboard/marketing/kit";

type App = { id: string; name: string; phone?: string; code: string; status: string; message?: string; city?: string; bio?: string; rating?: number; createdAt: string };
type Offer = { id: string; title: string; percent: number; endsAt?: string; active: boolean; partnerName?: string; partnerId?: string };
type Payout = { id: string; amountMinor: number; status: string; note?: string; evidence?: string; decisionNote?: string; requestedAt: string; name: string; payoutNote?: string };
type Inbox = { partnerId: string; name: string; lastBody: string; unread: number };
type Msg = { id: number; sender: "SHOP" | "MARKETER"; body: string };
type Dispute = { id: string; openedBy: string; reason: string; status: string; resolution?: string; name: string };
type Review = { id: string; direction: string; rating: number; comment?: string; name: string };
type PartnerLite = { id: string; name: string };

const TONE: Record<string, "green" | "amber" | "red" | "blue" | "slate"> = { PENDING: "amber", ACTIVE: "green", REJECTED: "red", BLOCKED: "red", PAUSED: "amber", REQUESTED: "amber", PAID: "green", CANCELLED: "slate", OPEN: "amber", RESOLVED: "green" };
type LFn = (ar: string, en: string) => string;

export function useL(): LFn { const { locale } = useI18n(); return (ar, en) => (locale === "ar" ? ar : en); }

export function Applications() {
  const L = useL(); const toast = useToast();
  const list = useApi<App[]>("store/affiliate/applications");
  const act = async (a: App, decision: string) => { try { await api(`store/affiliate/partners/${a.id}/decision`, { body: { decision } }); await list.reload(); } catch (e) { toast(errText(e, L("ما اتنفّذش", "Failed")), { tone: "error" }); } };
  const rate = async (a: App) => { const v = Number(window.prompt(L("تقييم من ١ لـ ٥", "Rating 1 to 5"), "5")); if (!v) return; try { await api(`store/affiliate/partners/${a.id}/rating`, { body: { rating: v } }); toast(L("اتحفظ", "Saved")); } catch (e) { toast(errText(e, L("ما اتحفظش", "Not saved")), { tone: "error" }); } };
  return (
    <Section title={L("طلبات المسوّقين", "Marketer applications")}>
      {(list.data ?? []).length === 0 ? <Empty>{L("مفيش طلبات", "No applications")}</Empty> : (
        <div className="space-y-3">{list.data!.map((a) => (
          <div key={a.id} className="rounded-2xl border border-line p-3"><div className="flex flex-wrap items-center justify-between gap-2"><div><b>{a.name}</b> <Badge tone={TONE[a.status]}>{a.status}</Badge> {a.rating ? <span className="text-xs">★ {a.rating}</span> : null}<p className="text-xs text-muted" dir="ltr">{a.phone}{a.city ? ` · ${a.city}` : ""}</p></div>
            <div className="flex flex-wrap gap-2">{a.status === "PENDING" && <><Button size="sm" onClick={() => void act(a, "APPROVE")}>{L("قبول", "Approve")}</Button><Button size="sm" variant="secondary" onClick={() => void act(a, "REJECT")}>{L("رفض", "Reject")}</Button></>}
              {a.status === "ACTIVE" && <Button size="sm" variant="secondary" onClick={() => void act(a, "BLOCK")}>{L("حظر", "Block")}</Button>}{a.status === "BLOCKED" && <Button size="sm" variant="secondary" onClick={() => void act(a, "UNBLOCK")}>{L("إلغاء الحظر", "Unblock")}</Button>}
              <Button size="sm" variant="ghost" onClick={() => void rate(a)}>{L("قيّم", "Rate")}</Button></div></div>
            {a.message && <p className="mt-2 text-sm">“{a.message}”</p>}{a.bio && <p className="mt-1 text-xs text-muted">{a.bio}</p>}</div>
        ))}</div>
      )}
    </Section>
  );
}

export function Offers({ partners }: { partners: PartnerLite[] }) {
  const L = useL(); const toast = useToast();
  const list = useApi<Offer[]>("store/affiliate/offers");
  const [f, setF] = useState({ title: "", percent: "", partnerId: "", endsAt: "" });
  const add = async () => { try { await api("store/affiliate/offers", { body: { title: f.title, percent: Number(f.percent), partnerId: f.partnerId || undefined, endsAt: f.endsAt ? new Date(f.endsAt).toISOString() : undefined } }); setF({ title: "", percent: "", partnerId: "", endsAt: "" }); await list.reload(); } catch (e) { toast(errText(e, L("ما اتحفظش", "Not saved")), { tone: "error" }); } };
  const toggle = async (o: Offer) => { try { await api(`store/affiliate/offers/${o.id}`, { method: "PATCH", body: { active: !o.active } }); await list.reload(); } catch (e) { toast(errText(e, L("ما اتنفّذش", "Failed")), { tone: "error" }); } };
  return (
    <Section title={L("عروض ونسب خاصة", "Offers and special rates")} subtitle={L("نسبة أعلى لمسوّق معيّن أو للكل لفترة محدّدة. بتتطبّق على الطلبات الجديدة بس.", "A higher rate for one marketer or for everybody for a limited time. Applies to new orders only.")}>
      <div className="mb-4 grid gap-3 sm:grid-cols-[1.4fr_.6fr_1fr_1fr_auto] sm:items-end">
        <Field label={L("اسم العرض", "Offer name")}><Input value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} /></Field>
        <Field label="%"><Input type="number" min={0} max={50} step="0.5" value={f.percent} onChange={(e) => setF({ ...f, percent: e.target.value })} /></Field>
        <Field label={L("لمسوّق", "For")}><select className="min-h-11 w-full rounded-xl border border-line bg-transparent px-3" value={f.partnerId} onChange={(e) => setF({ ...f, partnerId: e.target.value })}><option value="">{L("كل المسوّقين", "Everybody")}</option>{partners.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></Field>
        <Field label={L("ينتهي", "Ends")}><Input type="date" value={f.endsAt} onChange={(e) => setF({ ...f, endsAt: e.target.value })} /></Field>
        <Button disabled={!f.title.trim() || !f.percent} onClick={() => void add()}>{L("إضافة", "Add")}</Button>
      </div>
      {(list.data ?? []).length === 0 ? <Empty>{L("مفيش عروض", "No offers")}</Empty> : <Table head={[L("العرض", "Offer"), "%", L("لمسوّق", "For"), L("ينتهي", "Ends"), ""]}>{list.data!.map((o) => <tr key={o.id} className="border-t border-line"><td className="p-3 font-semibold">{o.title}</td><td className="p-3 tnum">{o.percent}</td><td className="p-3">{o.partnerName ?? L("الكل", "Everybody")}</td><td className="p-3">{o.endsAt ? new Date(o.endsAt).toLocaleDateString() : "—"}</td><td className="p-3"><Button size="sm" variant={o.active ? "secondary" : "primary"} onClick={() => void toggle(o)}>{o.active ? L("إيقاف", "Turn off") : L("تشغيل", "Turn on")}</Button></td></tr>)}</Table>}
    </Section>
  );
}

export function Payouts() {
  const L = useL(); const toast = useToast(); const { locale, currency } = useI18n();
  const list = useApi<Payout[]>("store/affiliate/payouts");
  const [ev, setEv] = useState<Record<string, string>>({});
  const m = (v: number) => money(v, currency, locale);
  const paid = async (p: Payout) => { try { await api(`store/affiliate/payouts/${p.id}/paid`, { body: { evidence: ev[p.id] ?? "" } }); await list.reload(); toast(L("اتسجّل الدفع", "Payment recorded")); } catch (e) { toast(errText(e, L("اكتب إثبات الدفع", "Write the proof of payment")), { tone: "error" }); } };
  const reject = async (p: Payout) => { const note = window.prompt(L("سبب الرفض (اختياري)", "Reason (optional)")) ?? undefined; try { await api(`store/affiliate/payouts/${p.id}/reject`, { body: { note } }); await list.reload(); } catch (e) { toast(errText(e, L("ما اتنفّذش", "Failed")), { tone: "error" }); } };
  return (
    <Section title={L("طلبات السحب", "Payout requests")}>
      <Alert tone="blue">{L("انت اللي بتدفع للمسوّق بنفسك (تحويل، InstaPay…) وبعدين تسجّل إثبات الدفع هنا. المنصة مش بتحوّل فلوس.", "You pay the marketer yourself (transfer, InstaPay…) and then record the proof here. The platform does not transfer money.")}</Alert>
      <div className="mt-3 space-y-3">{(list.data ?? []).length === 0 ? <Empty>{L("مفيش طلبات سحب", "No payout requests")}</Empty> : list.data!.map((p) => (
        <div key={p.id} className="rounded-2xl border border-line p-3"><div className="flex flex-wrap items-center justify-between gap-2"><div><b>{p.name}</b> · <b>{m(p.amountMinor)}</b> <Badge tone={TONE[p.status]}>{p.status}</Badge>{p.payoutNote && <p className="text-xs text-muted">{L("طريقة الدفع المفضّلة", "Preferred payment")}: {p.payoutNote}</p>}{p.note && <p className="text-xs">“{p.note}”</p>}</div></div>
          {p.status === "REQUESTED" && <div className="mt-3 flex flex-wrap gap-2"><Input className="min-w-56 flex-1" placeholder={L("إثبات الدفع (رقم عملية أو رابط إيصال)", "Proof of payment (reference or receipt link)")} value={ev[p.id] ?? ""} onChange={(e) => setEv({ ...ev, [p.id]: e.target.value })} maxLength={500} /><Button disabled={!(ev[p.id] ?? "").trim()} onClick={() => void paid(p)}>{L("تم الدفع", "Mark paid")}</Button><Button variant="secondary" onClick={() => void reject(p)}>{L("رفض", "Decline")}</Button></div>}
          {p.evidence && <p className="mt-2 text-sm text-emerald-700">{L("الإثبات", "Proof")}: {p.evidence}</p>}{p.decisionNote && <p className="mt-1 text-sm">{p.decisionNote}</p>}</div>
      ))}</div>
    </Section>
  );
}

export function Messages() {
  const L = useL(); const toast = useToast();
  const inbox = useApi<Inbox[]>("store/affiliate/inbox");
  const [open, setOpen] = useState<string | null>(null);
  const thread = useApi<Msg[]>(open ? `store/affiliate/partners/${open}/messages` : null);
  const [body, setBody] = useState("");
  const send = async () => { if (!open) return; try { await api(`store/affiliate/partners/${open}/messages`, { body: { body } }); setBody(""); await thread.reload(); await inbox.reload(); } catch (e) { toast(errText(e, L("ما اتبعتش", "Not sent")), { tone: "error" }); } };
  return (
    <Section title={L("رسائل المسوّقين", "Marketer messages")}>
      <div className="grid gap-4 md:grid-cols-[.8fr_1.2fr]">
        <div className="space-y-2">{(inbox.data ?? []).length === 0 ? <Empty compact>{L("مفيش رسائل", "No messages")}</Empty> : inbox.data!.map((i) => <button key={i.partnerId} onClick={() => setOpen(i.partnerId)} className={`block min-h-11 w-full rounded-xl border p-3 text-start ${open === i.partnerId ? "border-brand" : "border-line"}`}><b>{i.name}</b>{i.unread > 0 && <span className="ms-2 rounded-full bg-rose-500 px-1.5 text-xs text-white">{i.unread}</span>}<p className="truncate text-xs text-muted">{i.lastBody}</p></button>)}</div>
        <div>{open ? <><div className="mb-2 max-h-72 space-y-1 overflow-y-auto rounded-xl bg-soft p-2 text-sm">{[...(thread.data ?? [])].reverse().map((x) => <p key={x.id} className={`max-w-[85%] rounded-xl px-3 py-1.5 ${x.sender === "SHOP" ? "ms-auto bg-brand-soft" : "bg-white ring-1 ring-line"}`}>{x.body}</p>)}</div><div className="flex gap-2"><Input value={body} maxLength={1000} onChange={(e) => setBody(e.target.value)} placeholder={L("اكتب رد", "Write a reply")} /><Button disabled={!body.trim()} onClick={() => void send()}>{L("إرسال", "Send")}</Button></div></> : <Empty compact>{L("اختار محادثة", "Pick a conversation")}</Empty>}</div>
      </div>
    </Section>
  );
}

export function DisputesReviews() {
  const L = useL(); const toast = useToast();
  const disputes = useApi<Dispute[]>("store/affiliate/disputes");
  const reviews = useApi<Review[]>("store/affiliate/reviews");
  const resolve = async (d: Dispute) => { const r = window.prompt(L("القرار / الرد", "Resolution")); if (!r) return; try { await api(`store/affiliate/disputes/${d.id}/resolve`, { body: { resolution: r } }); await disputes.reload(); } catch (e) { toast(errText(e, L("ما اتنفّذش", "Failed")), { tone: "error" }); } };
  return (
    <div className="space-y-5">
      <Section title={L("النزاعات", "Disputes")}>{(disputes.data ?? []).length === 0 ? <Empty compact>{L("مفيش نزاعات", "No disputes")}</Empty> : disputes.data!.map((d) => <div key={d.id} className="mb-2 rounded-2xl border border-line p-3 text-sm"><b>{d.name}</b> <Badge tone={TONE[d.status]}>{d.status}</Badge> <span className="text-xs text-muted">{d.openedBy}</span><p>{d.reason}</p>{d.resolution && <p className="text-emerald-700">{d.resolution}</p>}{d.status === "OPEN" && <Button className="mt-2" size="sm" onClick={() => void resolve(d)}>{L("حلّ النزاع", "Resolve")}</Button>}</div>)}</Section>
      <Section title={L("التقييمات", "Reviews")}>{(reviews.data ?? []).length === 0 ? <Empty compact>{L("مفيش تقييمات", "No reviews")}</Empty> : <ul className="divide-y divide-line text-sm">{reviews.data!.map((r) => <li key={r.id} className="py-2"><b>{r.name}</b> {"★".repeat(r.rating)} <span className="text-xs text-muted">{r.direction === "MARKETER_TO_SHOP" ? L("تقييمهم لمتجرك", "their rating of your shop") : L("تقييمك لهم", "your rating of them")}</span>{r.comment && <p>{r.comment}</p>}</li>)}</ul>}</Section>
    </div>
  );
}
