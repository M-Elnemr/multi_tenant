"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Select } from "../ui";

type Status = {
  configured: boolean; platformNumber: string; linked: boolean; phone?: string; enabled?: boolean; sendAfter?: string; language?: string;
  windowOpen?: boolean; windowClosesAt?: string; needsPing?: boolean; lastDay?: string; lastStatus?: string;
};

/** Settings card: link the owner's WhatsApp by messaging our number, then get the daily report after the chosen end-of-day time. */
export default function WaReportCard() {
  const { t, locale } = useI18n();
  const st = useApi<Status>("store/whatsapp-report");
  const [code, setCode] = useState<{ code: string; platformNumber: string } | null>(null);
  const [time, setTime] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const getCode = useAction(async () => { setCode(await api("store/whatsapp-report/link-code", { body: {} })); });
  const save = useAction(async (patch: Record<string, unknown>) => { await api("store/whatsapp-report", { method: "PATCH", body: patch }); await st.reload(); });
  const sendNow = useAction(async () => { await api("store/whatsapp-report/send-now", { body: {} }); setNotice(t("wr.sent")); await st.reload(); });
  const unlink = useAction(async () => { await api("store/whatsapp-report", { method: "DELETE" }); setCode(null); await st.reload(); });
  const s = st.data;
  if (!s) return null;
  const digits = s.platformNumber.replace(/\D/g, "");
  return (
    <Card className="space-y-4">
      <div>
        <h2 className="font-medium">{t("wr.title")}</h2>
        <p className="mt-1 text-sm text-slate-500">{t("wr.hint")}</p>
      </div>
      {!s.configured && <Alert tone="amber">{t("wr.notReady")}</Alert>}
      {s.configured && !s.linked && (
        <div className="space-y-3">
          {!code ? <Button loading={getCode.loading} onClick={() => void getCode.run()}>{t("wr.getCode")}</Button> : (
            <div className="space-y-2 rounded-xl bg-slate-50 p-4 text-sm">
              <p>{t("wr.sendCode")}</p>
              <div className="flex flex-wrap items-center gap-3"><code dir="ltr" className="rounded-lg bg-white px-3 py-2 text-lg font-bold tracking-widest text-brand shadow-sm">{code.code}</code>
                {digits && <a href={`https://wa.me/${digits}?text=${encodeURIComponent(code.code)}`} target="_blank" rel="noreferrer" className="inline-flex items-center rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700">{t("wr.openWhatsapp")}</a>}</div>
              <p className="text-xs text-slate-500" dir="ltr">{s.platformNumber}</p>
              <p className="text-xs text-slate-500">{t("wr.expires")}</p>
              <button type="button" onClick={() => void st.reload()} className="text-xs font-semibold text-brand hover:underline">{t("wr.check")}</button>
            </div>
          )}
        </div>
      )}
      {s.linked && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2 text-sm"><Badge tone="green">{t("wr.linked")}</Badge><span dir="ltr" className="text-slate-600">+{s.phone}</span>
            <Badge tone={s.windowOpen ? "green" : "amber"}>{s.windowOpen ? t("wr.window.open") : t("wr.window.closed")}</Badge></div>
          {s.windowOpen && s.windowClosesAt && <p className="text-xs text-slate-500">{t("wr.window.until")} {dateTime(s.windowClosesAt, locale)}</p>}
          {s.needsPing && <Alert tone="amber">{t("wr.ping")}</Alert>}
          {s.lastDay && <p className="text-xs text-slate-500">{t("wr.last")}: {s.lastDay} · {t(`wr.status.${s.lastStatus}`)}</p>}
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t("wr.enabled")}><Select value={s.enabled ? "1" : "0"} onChange={(e) => void save.run({ enabled: e.target.value === "1" })}><option value="1">{t("wr.on")}</option><option value="0">{t("wr.off")}</option></Select></Field>
            <Field label={t("wr.time")} hint={t("wr.timeHint")}><Input type="time" dir="ltr" value={time ?? s.sendAfter ?? "23:59"} min="18:00" onChange={(e) => setTime(e.target.value)} onBlur={() => { if (time && time !== s.sendAfter) void save.run({ sendAfter: time }); }} /></Field>
            <Field label={t("wr.language")}><Select value={s.language ?? "ar"} onChange={(e) => void save.run({ language: e.target.value })}><option value="ar">العربية</option><option value="en">English</option></Select></Field>
          </div>
          <ErrorText error={save.error ?? sendNow.error ?? unlink.error} />
          {notice && <Alert tone="green">{notice}</Alert>}
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" loading={sendNow.loading} onClick={() => { setNotice(null); void sendNow.run(); }}>{t("wr.sendNow")}</Button>
            <Button variant="ghost" loading={unlink.loading} onClick={() => void unlink.run()}>{t("wr.unlink")}</Button>
          </div>
        </div>
      )}
      <ErrorText error={getCode.error ?? st.error} />
    </Card>
  );
}
