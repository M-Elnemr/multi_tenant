"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Empty, ErrorText, KpiStrip, Loading, Section } from "../ui";
import { Icon } from "../icons";
import { AI_GRADIENT } from "./kit";

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
              <div key={i} className="hover-lift space-y-2.5 rounded-[18px] bg-surface p-5 ring-1 ring-line" style={{ animation: `fade-up .4s ${i * 0.06}s both` }}>
                <div className="flex items-center justify-between"><span className="grid h-8 w-8 place-items-center rounded-full text-[13px] font-bold text-white" style={{ background: AI_GRADIENT }}>{i + 1}</span><Badge tone={TONE[a.kind] ?? "blue"}>{t(`cp.kind.${a.kind}`)}</Badge></div>
                <div className="font-display text-[15px] font-semibold text-ink">{a.title}</div>
                <p className="text-[13px] leading-relaxed text-muted">{a.detail}</p>
              </div>
            ))}
          </div>
          <KpiStrip items={[
            { label: t("cp.week.orders"), value: d.week.orders, icon: "box", tone: "brand" },
            { label: t("cp.week.sales"), value: money(Math.round(d.week.sales * 100), d.week.currency, locale), icon: "billing", tone: "green" },
            { label: t("cp.week.change"), value: <span dir="ltr">{d.week.salesChangePercent === null ? "-" : `${d.week.salesChangePercent > 0 ? "+" : ""}${d.week.salesChangePercent}%`}</span>, icon: "trending", tone: d.week.salesChangePercent !== null && d.week.salesChangePercent < 0 ? "red" : "violet" },
          ]} />
        </>
      )}
      <div className="grid gap-5 lg:grid-cols-5">
        <section className="flex h-[34rem] flex-col overflow-hidden rounded-[18px] bg-surface ring-1 ring-line lg:col-span-3">
          <div className="flex items-center justify-between gap-3 px-5 py-3.5 hairline-b">
            <h2 className="flex items-center gap-2.5 text-[14.5px] font-semibold text-ink"><span className="grid h-7 w-7 place-items-center rounded-[8px] text-white" style={{ background: AI_GRADIENT }}><Icon name="sparkles" className="h-4 w-4" /></span>{t("cp.chat")}</h2>
            {msgs.length > 0 && <button type="button" onClick={() => void clear.run()} className="text-xs font-semibold text-muted transition hover:text-ink">{t("cp.clear")}</button>}
          </div>
          {d && !d.available && <div className="px-5 pt-3"><Alert tone="amber">{t("cp.unavailable")}</Alert></div>}
          <div className="min-h-0 flex-1 space-y-2.5 overflow-y-auto px-5 py-4">
            {msgs.length === 0 && (
              <div className="grid h-full place-items-center text-center text-sm text-muted"><div><p className="mb-4">{t("cp.empty")}</p><div className="flex flex-wrap justify-center gap-2">{chips.map((c) => <button key={c} type="button" disabled={ask.loading} onClick={() => void ask.run(c)} className="rounded-full bg-surface px-3.5 py-2 text-[12.5px] font-medium text-ink-2 ring-1 ring-line-strong transition hover:bg-surface-2">{c}</button>)}</div></div></div>
            )}
            {msgs.map((m, i) => (
              <div key={i} className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}>
                <div className={`max-w-[85%] whitespace-pre-wrap rounded-[16px] px-3.5 py-2 text-[13.5px] leading-relaxed ${m.role === "user" ? "bg-ink text-surface" : "bg-[linear-gradient(135deg,rgb(124_92_255/.10),rgb(34_211_238/.10))] text-ink"}`}>{m.content}</div>
              </div>
            ))}
            {ask.loading && <div className="flex items-center gap-1.5 text-xs text-muted"><span className="h-1.5 w-1.5 animate-pulse rounded-full bg-violet-500" />{t("cp.thinking")}</div>}
            <div ref={end} />
          </div>
          <div className="border-t border-line p-3">
            <ErrorText error={ask.error} />
            <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (text.trim() && !ask.loading) void ask.run(text.trim()); }}>
              <input value={text} onChange={(e) => setText(e.target.value)} maxLength={600} placeholder={t("cp.placeholder")} className="min-w-0 flex-1 rounded-[10px] border border-line-strong bg-surface px-3.5 py-2.5 text-sm text-ink outline-none transition placeholder:text-faint focus:border-brand focus:ring-4 focus:ring-brand/12" />
              <Button loading={ask.loading} disabled={!text.trim() || (d ? !d.available : false)}><Icon name="send" className="h-4 w-4 flip-rtl" />{t("cp.send")}</Button>
            </form>
          </div>
        </section>
        <div className="space-y-5 lg:col-span-2">
          <Section title={t("cp.reorder")} icon="inventory">
            {d && d.reorder.length === 0 ? <Empty compact icon="check">{t("cp.reorder.none")}</Empty> : (
              <ul className="divide-y divide-line">{d?.reorder.map((r) => (
                <li key={r.name} className="flex items-start justify-between gap-3 py-2.5 text-sm first:pt-0 last:pb-0">
                  <div className="min-w-0"><div className="truncate font-medium text-ink">{r.name}</div><div className="text-xs text-muted">{r.stock} · {r.coverDays} {t("cp.days")}</div></div>
                  <Badge tone={r.urgent ? "red" : "amber"}>{t("cp.order")} {r.suggestedOrderQuantity}</Badge>
                </li>
              ))}</ul>
            )}
          </Section>
          <Section title={t("cp.prices")} icon="tag">
            {d && d.prices.length === 0 ? <Empty compact icon="tag">{t("cp.prices.none")}</Empty> : (
              <ul className="divide-y divide-line">{d?.prices.map((p) => (
                <li key={p.name + p.idea} className="py-2.5 text-sm first:pt-0 last:pb-0">
                  <div className="flex items-center justify-between gap-2"><span className="min-w-0 truncate font-medium text-ink">{p.name}</span><Badge tone={p.idea === "DISCOUNT" ? "amber" : "green"}><span dir="ltr">{p.idea === "DISCOUNT" ? `-${p.percent}%` : `+${p.percent}%`}</span></Badge></div>
                  <div className="text-xs text-muted tnum" dir="ltr">{p.from} → {p.to}</div>
                  <div className="text-xs text-muted">{p.idea === "DISCOUNT" ? t("cp.why.slow").replace("{sold}", String(p.sold)).replace("{stock}", String(p.stock ?? 0)) : t("cp.why.fast").replace("{sold}", String(p.sold)).replace("{cover}", String(p.cover ?? 0))}</div>
                </li>
              ))}</ul>
            )}
          </Section>
          {d && d.actions.length === 0 && <Empty icon="sparkles">{t("cp.empty")}</Empty>}
        </div>
      </div>
    </div>
  );
}
