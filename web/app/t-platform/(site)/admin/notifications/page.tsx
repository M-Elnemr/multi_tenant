"use client";

import Link from "next/link";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Empty, ErrorText, Loading, PageHeader } from "@/components/ui";
import { dateTime } from "@/lib/format";
import { Icon } from "@/components/icons";

type N = { id: string; notificationType: string; title: string; body: string; payload?: string | { tenantId?: string } | null; readAt?: string; createdAt: string };

const tenantOf = (n: N): string | undefined => {
  try { const p = typeof n.payload === "string" ? JSON.parse(n.payload) : n.payload; return p?.tenantId; } catch { return undefined; }
};

/** Platform owner's inbox: a new pay request, with a link to the business. */
export default function AdminNotifications() {
  const { t, locale } = useI18n();
  const { data, loading, reload, error } = useApi<Page<N>>("notifications?pageSize=50");
  const readAll = useAction(async () => { await api("notifications/read-all", { body: {} }); await reload(); });
  const read = useAction(async (id: string) => { await api(`notifications/${id}/read`, { body: {} }); await reload(); });
  return (
    <>
      <PageHeader title={t("nav.notifications")} actions={<Button variant="secondary" onClick={() => readAll.run()}>{t("notif.markAll")}</Button>} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("notif.empty")}</Empty> : (
        <ul className="space-y-2">
          {data?.data.map((n) => {
            const tenantId = tenantOf(n);
            return (
              <li key={n.id} className={`flex items-start gap-3 rounded-2xl bg-white p-4 shadow-card ${n.readAt ? "opacity-80" : "ring-1 ring-brand/40"}`}>
                <span className={`mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-full ${n.notificationType === "PAY_REQUEST" ? "bg-amber-100 text-amber-700" : "bg-brand-soft text-brand"}`}><Icon name={n.notificationType === "PAY_REQUEST" ? "wallet" : "bell"} className="h-5 w-5" /></span>
                <div className="min-w-0 flex-1">
                  <p className="font-bold text-ink">{n.title}</p>
                  <p className="text-sm text-slate-600">{n.body}</p>
                  <p className="mt-1 text-xs text-slate-400">{dateTime(n.createdAt, locale)}</p>
                  <div className="mt-2 flex flex-wrap gap-4">
                    {n.notificationType === "PAY_REQUEST" && <Link href="/admin/pay-requests" onClick={() => !n.readAt && read.run(n.id)} className="text-sm font-semibold text-brand underline">{t("admin.payRequests")}</Link>}
                    {tenantId && <Link href={`/admin/tenants/${tenantId}`} className="text-sm font-semibold text-brand underline">{t("admin.tenants")}</Link>}
                  </div>
                </div>
                {!n.readAt && <Button size="sm" variant="ghost" onClick={() => read.run(n.id)}>{t("notif.markRead")}</Button>}
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}
