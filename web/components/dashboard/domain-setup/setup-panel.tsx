"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Button, ErrorText } from "../../ui";
import { type DnsRecord, RecordCard } from "./record-card";

type Check = { verified: boolean; records: DnsRecord[] };

function Steps({ current }: { current: 1 | 2 | 3 }) {
  const { t } = useI18n();
  return (
    <ol className="mb-5 grid gap-2 sm:grid-cols-3">
      {([1, 2, 3] as const).map((n) => {
        const done = n < current, on = n === current;
        return (
          <li key={n} className={`flex items-center gap-3 rounded-xl border p-3 text-sm font-semibold ${on ? "border-brand bg-brand-soft text-brand-dark" : done ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-slate-200 bg-white text-slate-400"}`}>
            <span className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold ${on ? "bg-brand text-white" : done ? "bg-emerald-500 text-white" : "bg-slate-200 text-slate-500"}`}>{done ? "✓" : n}</span>
            {t(`domain.step${n}`)}
          </li>
        );
      })}
    </ol>
  );
}

/** Guided DNS setup of one custom domain that is not verified yet; it looks at the real DNS every 15 seconds and tells what is right or wrong. */
export function SetupPanel({ id, host, onVerified }: { id: string; host: string; onVerified: () => void }) {
  const { t, locale } = useI18n();
  const [check, setCheck] = useState<Check | null>(null);
  const [at, setAt] = useState<Date | null>(null);
  const run = useAction(async () => {
    const r = await api<Check>(`tenant/domains/${id}/check`);
    setCheck(r); setAt(new Date());
    if (r.verified) onVerified();
  });
  const { run: refresh } = run;
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 15_000); return () => clearInterval(timer); }, [refresh]);

  const allTxt = check?.records.find((r) => r.purpose === "VERIFY")?.status === "OK";
  return (
    <div className="mt-4">
      <Steps current={allTxt ? 3 : 2} />
      <p className="mb-4 text-sm text-slate-600">{t("domain.intro", { host })}</p>
      <div className="space-y-3">{check?.records.map((r) => <RecordCard key={r.type + r.name} rec={r} host={host} />)}</div>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button size="sm" loading={run.loading} onClick={() => run.run()}>{t("domain.checkAgain")}</Button>
        {at && <p className="text-xs text-slate-500">{t("domain.lastCheck", { time: new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-GB", { timeStyle: "medium" }).format(at) })}</p>}
      </div>
      <ErrorText error={run.error} />
      <details className="mt-4 rounded-xl bg-slate-50 p-4 text-sm">
        <summary className="cursor-pointer font-bold text-ink">{t("domain.mistakes.title")}</summary>
        <ul className="mt-2 list-disc space-y-1 ps-5 text-slate-600">{[1, 2, 3, 4, 5].map((i) => <li key={i}>{t(`domain.mistakes.${i}`)}</li>)}</ul>
      </details>
      <details className="mt-2 rounded-xl bg-slate-50 p-4 text-sm">
        <summary className="cursor-pointer font-bold text-ink">{t("domain.providers.title")}</summary>
        <ul className="mt-2 list-disc space-y-1 ps-5 text-slate-600">{(["godaddy", "namecheap", "cloudflare"] as const).map((p) => <li key={p}>{t(`domain.providers.${p}`)}</li>)}</ul>
      </details>
    </div>
  );
}
