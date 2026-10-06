"use client";

import { api } from "@/lib/client";
import { Page, useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Button, Empty, ErrorText, Loading, PageHeader } from "../ui";
import { dateTime } from "@/lib/format";

type N = { id: string; notificationType: string; title: string; body: string; readAt?: string; createdAt: string };

export default function NotificationsPage() {
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
          {data?.data.map((n) => (
            <li key={n.id} className={`rounded-xl border p-4 ${n.readAt ? "bg-white" : "border-brand/40 bg-white shadow-sm"}`}>
              <div className="flex items-start justify-between gap-3">
                <div><p className="font-medium">{n.title}</p><p className="text-sm text-slate-600">{n.body}</p><p className="mt-1 text-xs text-slate-400">{dateTime(n.createdAt, locale)}</p></div>
                {!n.readAt && <Button size="sm" variant="ghost" onClick={() => read.run(n.id)}>{t("notif.markRead")}</Button>}
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
