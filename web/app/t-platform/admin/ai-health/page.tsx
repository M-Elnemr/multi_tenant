"use client";

import { useState } from "react";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Card, Empty, ErrorText, Loading, PageHeader, Section, Table, Tabs, Td } from "@/components/ui";
import { dateTime } from "@/lib/format";

type DayRow = { day: string; model: string; replies: number; avgMs: number | null; p90Ms: number | null; errors: number; fallbacks: number; corrections: number };
type CheckRow = { checkName: string; model: string; times: number };
type ShopRow = { tenantId: string; name: string; slug: string; replies: number; corrections: number; failures: number; handedOver: number };
type Problem = { createdAt: string; shop: string; model: string | null; kind: string; detail: string | null };
type EvalRow = { model: string; kind: string; overallPct: number | null; checks: number | null; status: string; finishedAt: string; shop: string };
type Backup = { kind: string; ok: boolean; sizeBytes: number | null; offsite: boolean; detail: string | null; createdAt: string };
type Health = { days: number; byDay: DayRow[]; checks: CheckRow[]; shops: ShopRow[]; recentProblems: Problem[]; evalRuns: EvalRow[]; backups: Backup[] };

const sec = (ms: number | null) => (ms == null ? "–" : `${(ms / 1000).toFixed(1)}s`);

/** How the AI seller is doing: speed, failures and switches to the backup model, which checks corrected replies, shops that need help, quality scores and backups. */
export default function AiHealth() {
  const { t, locale } = useI18n();
  const [days, setDays] = useState("7");
  const h = useApi<Health>(`platform/ai-health?days=${days}`);
  const d = h.data;
  return (
    <>
      <PageHeader title={t("aih.title")} subtitle={t("aih.hint")} />
      <div className="mb-4"><Tabs tabs={["1", "7", "30"].map((k) => ({ key: k, label: t(`aih.days.${k}`) }))} value={days} onChange={setDays} /></div>
      <ErrorText error={h.error} />
      {!d ? <Loading /> : (
        <div className="space-y-6">
          <Section title={t("aih.byDay")}>
            {d.byDay.length === 0 ? <Empty>{t("aih.none")}</Empty> : (
              <Table head={[t("aih.day"), t("aih.model"), t("aih.replies"), t("aih.avg"), t("aih.p90"), t("aih.errors"), t("aih.fallbacks"), t("aih.corrections")]}>
                {d.byDay.map((r) => (
                  <tr key={r.day + r.model}><Td>{r.day}</Td><Td>{r.model}</Td><Td>{r.replies}</Td><Td>{sec(r.avgMs)}</Td><Td>{sec(r.p90Ms)}</Td>
                    <Td>{r.errors > 0 ? <Badge tone="red">{r.errors}</Badge> : 0}</Td><Td>{r.fallbacks > 0 ? <Badge tone="amber">{r.fallbacks}</Badge> : 0}</Td><Td>{r.corrections}</Td></tr>
                ))}
              </Table>
            )}
          </Section>
          <div className="grid gap-6 lg:grid-cols-2">
            <Section title={t("aih.checks")}>
              {d.checks.length === 0 ? <Empty>{t("aih.none")}</Empty> : (
                <Table head={[t("aih.check"), t("aih.model"), t("aih.times")]}>
                  {d.checks.map((c) => <tr key={c.checkName + c.model}><Td>{c.checkName}</Td><Td>{c.model}</Td><Td>{c.times}</Td></tr>)}
                </Table>
              )}
            </Section>
            <Section title={t("aih.shops")}>
              {d.shops.length === 0 ? <Empty>{t("aih.none")}</Empty> : (
                <Table head={[t("aih.shop"), t("aih.replies"), t("aih.corrections"), t("aih.failures"), t("aih.handedOver")]}>
                  {d.shops.map((s) => <tr key={s.tenantId}><Td>{s.name}</Td><Td>{s.replies}</Td><Td>{s.corrections}</Td><Td>{s.failures}</Td><Td>{s.handedOver}</Td></tr>)}
                </Table>
              )}
            </Section>
          </div>
          <div className="grid gap-6 lg:grid-cols-2">
            <Section title={t("aih.evals")}>
              {d.evalRuns.length === 0 ? <Empty>{t("aih.noEvals")}</Empty> : (
                <Table head={[t("aih.when"), t("aih.model"), t("aih.kind"), t("aih.score")]}>
                  {d.evalRuns.map((e, i) => <tr key={i}><Td>{dateTime(e.finishedAt, locale)}</Td><Td>{e.model}</Td><Td>{e.kind}</Td><Td>{e.status === "DONE" ? `${e.overallPct}%` : <Badge tone="red">{e.status}</Badge>}</Td></tr>)}
                </Table>
              )}
            </Section>
            <Section title={t("aih.backups")}>
              {d.backups.length === 0 ? <Empty>{t("aih.noBackups")}</Empty> : (
                <Card className="space-y-2 text-sm">
                  {!d.backups.some((b) => b.kind === "BACKUP" && b.offsite) && <p className="font-semibold text-amber-700">{t("aih.noOffsite")}</p>}
                  {d.backups.map((b, i) => (
                    <div key={i} className="flex items-center justify-between gap-2 border-b pb-1 last:border-0">
                      <span>{b.kind === "BACKUP" ? t("aih.backup") : t("aih.restore")} · {dateTime(b.createdAt, locale)}</span>
                      <span className="flex items-center gap-1.5">{b.sizeBytes ? <span className="text-xs text-slate-500">{(b.sizeBytes / 1048576).toFixed(1)} MB</span> : null}<Badge tone={b.ok ? "green" : "red"}>{b.ok ? "OK" : "FAILED"}</Badge></span>
                    </div>
                  ))}
                </Card>
              )}
            </Section>
          </div>
          <Section title={t("aih.recent")}>
            {d.recentProblems.length === 0 ? <Empty>{t("aih.none")}</Empty> : (
              <Table head={[t("aih.when"), t("aih.shop"), t("aih.model"), t("aih.kind"), t("aih.detail")]}>
                {d.recentProblems.map((p, i) => <tr key={i}><Td>{dateTime(p.createdAt, locale)}</Td><Td>{p.shop}</Td><Td>{p.model ?? "–"}</Td><Td>{p.kind}</Td><Td><span className="line-clamp-2 max-w-md text-xs">{p.detail}</span></Td></tr>)}
              </Table>
            )}
          </Section>
        </div>
      )}
    </>
  );
}
