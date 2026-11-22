"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";
import type { VnService } from "../vn-core/types";

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** The first thing a visitor sees: what do you need, and when? It only carries the choice to the booking page (/book?service=&date=); real times are chosen there from the free slots. */
export function RequestCard({ services, maxDays, id }: { services: VnService[]; maxDays: number; id?: string }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [svc, setSvc] = useState(services[0]?.id ?? "");
  const [date, setDate] = useState("");
  const now = new Date();
  const max = new Date(now.getTime() + maxDays * 86400000);
  return (
    <form id={id} className="hp-card" onSubmit={(e) => { e.preventDefault(); router.push(`/book?service=${encodeURIComponent(svc)}${date ? `&date=${date}` : ""}`); }}>
      <h2 className="hp-card-title">{t("vn.hp.requestTitle")}</h2>
      <p className="hp-card-sub">{t("vn.hp.requestSub")}</p>
      <label className="vf-field">{t("vn.hp.what")}
        <select value={svc} onChange={(e) => setSvc(e.target.value)}>{services.map((s) => <option key={s.id} value={s.id}>{s.name} · {money(s.priceMinor, s.currency, locale)}</option>)}</select>
      </label>
      <label className="vf-field">{t("vn.hp.when")}
        <input type="date" dir="ltr" className="vf-date" min={iso(now)} max={iso(max)} value={date} onChange={(e) => setDate(e.target.value)} />
      </label>
      <button className="vf-btn hp-card-go">{t("vn.hp.seeTimes")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></button>
    </form>
  );
}
