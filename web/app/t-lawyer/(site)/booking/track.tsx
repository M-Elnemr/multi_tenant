"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText } from "@/components/ui";

type B = { code: string; guestName: string; startsAt: string; status: string; decisionNote?: string; serviceName?: string };
const TONE: Record<string, string> = { PENDING: "#f59e0b", APPROVED: "#10b981", DONE: "#10b981", DENIED: "#ef4444", CANCELLED: "#64748b", NO_SHOW: "#64748b" };

export function TrackBooking() {
  const { t, locale } = useI18n();
  const [code, setCode] = useState(useSearchParams().get("code") ?? "");
  const [phone, setPhone] = useState("");
  const [b, setB] = useState<B | null>(null);
  const find = useAction(async () => setB(await api<B>("lawyer/public/bookings/track", { body: { code, phone } })));
  const cancel = useAction(async () => setB(await api<B>("lawyer/public/bookings/cancel", { body: { code, phone } })));
  return (
    <div className="mt-8 space-y-5">
      <form onSubmit={(e) => { e.preventDefault(); void find.run(); }} className="lw-card space-y-4 p-6">
        <label className="block space-y-1.5"><span className="text-sm font-bold">{t("lws.code")}</span><input className="lw-input font-mono uppercase tracking-widest" dir="ltr" value={code} onChange={(e) => setCode(e.target.value)} /></label>
        <label className="block space-y-1.5"><span className="text-sm font-bold">{t("lws.phone")}</span><input className="lw-input" dir="ltr" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} /></label>
        <ErrorText error={find.error} />
        <button type="submit" className="lw-btn w-full" disabled={find.loading}>{t("lws.find")}</button>
      </form>
      {b && (
        <div className="lw-card lw-reveal overflow-hidden">
          <div className="p-6" style={{ background: `color-mix(in srgb, ${TONE[b.status] ?? "#64748b"} 14%, var(--lw-surface))` }}>
            <p className="text-sm font-bold" style={{ color: TONE[b.status] }}>● {t(`lws.st.${b.status}`)}</p>
            <p className="lw-display mt-1 text-2xl font-bold">{new Date(b.startsAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "long", day: "numeric", month: "long", hour: "numeric", minute: "2-digit" })}</p>
            <p className="text-sm" style={{ color: "var(--lw-mute)" }}>{b.serviceName ?? ""}</p>
          </div>
          <div className="space-y-3 p-6">
            {b.decisionNote && <p className="rounded-xl p-3 text-sm" style={{ background: "var(--lw-bg)" }}>{b.decisionNote}</p>}
            <ErrorText error={cancel.error} />
            {(b.status === "PENDING" || b.status === "APPROVED") && <button type="button" onClick={() => cancel.run()} className="lw-btn lw-btn-ghost w-full">{t("lws.cancelBooking")}</button>}
          </div>
        </div>
      )}
    </div>
  );
}
