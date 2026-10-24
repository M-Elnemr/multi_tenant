"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Loading, Stat } from "../ui";

type Action = { kind: string; title: string; detail: string };
type Reorder = { name: string; stock: number; coverDays: number; suggestedOrderQuantity: number; urgent: boolean };
type Idea = { name: string; idea: "DISCOUNT" | "RAISE_PRICE"; percent: number; from: number; to: number; sold: number; stock?: number; cover?: number };
type Week = { orders: number; sales: number; previousSales: number; salesChangePercent: number | null; currency: string };
type Overview = { available: boolean; actions: Action[]; reorder: Reorder[]; prices: Idea[]; week: Week };
type Msg = { role: "user" | "assistant"; content: string };

const TONE: Record<string, "green" | "amber" | "blue"> = { RESTOCK: "amber", PROMOTE: "green", FOLLOW_UP: "blue" };

/** "Ask your business": the three things to do next, stock and price ideas, and a chat that answers from the shop's own numbers. */
export default function CopilotView() {
  const { t, locale } = useI18n();
  const ov = useApi<Overview>(`store/copilot/overview?lang=${locale}`);
  const hist = useApi<Msg[]>("store/copilot/messages");
  const [local, setLocal] = useState<Msg[] | null>(null);
  const [text, setText] = useState("");
  const msgs = local ?? hist.data ?? [];
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => { end.current?.scrollIntoView({ block: "nearest" }); }, [msgs.length]);
  const ask = useAction(async (q: string) => {
    const base = local ?? hist.data ?? [];
    setLocal([...base, { role: "user", content: q }]);
    setText("");
    try {
      const r = await api<{ answer: string }>("store/copilot/ask", { body: { question: q } });
      setLocal((cur) => [...(cur ?? base), { role: "assistant", content: r.answer }]);
    } catch (e) {
      setLocal(base);
      throw e;
    }
  });
  const clear = useAction(async () => { await api("store/copilot/messages", { method: "DELETE" }); setLocal([]); });
  if (ov.loading && !ov.data) return <Loading />;
  const d = ov.data;
  const chips = [t("cp.chip1"), t("cp.chip2"), t("cp.chip3"), t("cp.chip4")];
  return (
    <div className="space-y-5">
      <ErrorText error={ov.error} />
      {d && (
        <>
          <div className="grid gap-3 md:grid-cols-3">
            {d.actions.map((a, i) => (
              <Card key={i} className="space-y-2">
                <div className="flex items-center justify-between"><span className="grid h-7 w-7 place-items-center rounded-full bg-brand/10 text-sm font-bold text-brand">{i + 1}</span><Badge tone={TONE[a.kind] ?? "blue"}>{t(`cp.kind.${a.kind}`)}</Badge></div>
                <div className="font-semibold">{a.title}</div>
                <p className="text-sm text-slate-600">{a.detail}</p>
              </Card>
            ))}
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            <Stat label={t("cp.week.orders")} value={d.week.orders} icon="box" />
            <Stat label={t("cp.week.sales")} value={money(Math.round(d.week.sales * 100), d.week.currency, locale)} icon="billing" tone="green" />
            <Stat label={t("cp.week.change")} value={<span dir="ltr">{d.week.salesChangePercent === null ? "-" : `${d.week.salesChangePercent > 0 ? "+" : ""}${d.week.salesChangePercent}%`}</span>} icon="trending" tone={d.week.salesChangePercent !== null && d.week.salesChangePercent < 0 ? "red" : "brand"} />
          </div>
        </>
      )}
      <div className="grid gap-5 lg:grid-cols-5">
        <Card className="flex h-[34rem] flex-col lg:col-span-3">
          <div className="mb-2 flex items-center justify-between"><h2 className="font-medium">{t("cp.chat")}</h2>{msgs.length > 0 && <button type="button" onClick={() => void clear.run()} className="text-xs text-slate-500 hover:text-slate-800">{t("cp.clear")}</button>}</div>
          {d && !d.available && <Alert tone="amber">{t("cp.unavailable")}</Alert>}
          <div className="min-h-0 flex-1 space-y-2 overflow-y-auto py-2">
            {msgs.length === 0 && (
              <div className="grid h-full place-items-center text-center text-sm text-slate-500"><div><p className="mb-3">{t("cp.empty")}</p><div className="flex flex-wrap justify-center gap-2">{chips.map((c) => <button key={c} type="button" disabled={ask.loading} onClick={() => void ask.run(c)} className="rounded-full border bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">{c}</button>)}</div></div></div>
            )}
            {msgs.map((m, i) => (
              <div key={i} className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}>
                <div className={`max-w-[85%] whitespace-pre-wrap rounded-2xl px-3.5 py-2 text-sm ${m.role === "user" ? "bg-brand text-white" : "bg-slate-100 text-slate-800"}`}>{m.content}</div>
              </div>
            ))}
            {ask.loading && <div className="text-xs text-slate-500">{t("cp.thinking")}</div>}
            <div ref={end} />
          </div>
          <ErrorText error={ask.error} />
          <form className="mt-2 flex gap-2" onSubmit={(e) => { e.preventDefault(); if (text.trim() && !ask.loading) void ask.run(text.trim()); }}>
            <input value={text} onChange={(e) => setText(e.target.value)} maxLength={600} placeholder={t("cp.placeholder")} className="min-w-0 flex-1 rounded-xl border px-3 py-2 text-sm outline-none focus:border-brand" />
            <Button loading={ask.loading} disabled={!text.trim() || (d ? !d.available : false)}>{t("cp.send")}</Button>
          </form>
        </Card>
        <div className="space-y-5 lg:col-span-2">
          <Card className="space-y-3">
            <h2 className="font-medium">{t("cp.reorder")}</h2>
            {d && d.reorder.length === 0 ? <p className="text-sm text-slate-500">{t("cp.reorder.none")}</p> : d?.reorder.map((r) => (
              <div key={r.name} className="flex items-start justify-between gap-3 border-t pt-2 text-sm first:border-0 first:pt-0">
                <div><div className="font-medium">{r.name}</div><div className="text-xs text-slate-500">{r.stock} · {r.coverDays} {t("cp.days")}</div></div>
                <Badge tone={r.urgent ? "red" : "amber"}>{t("cp.order")} {r.suggestedOrderQuantity}</Badge>
              </div>
            ))}
          </Card>
          <Card className="space-y-3">
            <h2 className="font-medium">{t("cp.prices")}</h2>
            {d && d.prices.length === 0 ? <p className="text-sm text-slate-500">{t("cp.prices.none")}</p> : d?.prices.map((p) => (
              <div key={p.name + p.idea} className="border-t pt-2 text-sm first:border-0 first:pt-0">
                <div className="flex items-center justify-between gap-2"><span className="font-medium">{p.name}</span><Badge tone={p.idea === "DISCOUNT" ? "amber" : "green"}><span dir="ltr">{p.idea === "DISCOUNT" ? `-${p.percent}%` : `+${p.percent}%`}</span></Badge></div>
                <div className="text-xs text-slate-500" dir="ltr">{p.from} → {p.to}</div>
                <div className="text-xs text-slate-500">{p.idea === "DISCOUNT" ? t("cp.why.slow").replace("{sold}", String(p.sold)).replace("{stock}", String(p.stock ?? 0)) : t("cp.why.fast").replace("{sold}", String(p.sold)).replace("{cover}", String(p.cover ?? 0))}</div>
              </div>
            ))}
          </Card>
          {d && d.actions.length === 0 && <Empty>{t("cp.empty")}</Empty>}
        </div>
      </div>
    </div>
  );
}
