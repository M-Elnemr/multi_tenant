"use client";

import { api } from "@/lib/client";
import { Page, useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { useToast } from "../toast";
import { Button, Empty, ErrorText, Loading, PageHeader, cx } from "../ui";
import { dateTime } from "@/lib/format";

type N = { id: string; notificationType: string; title: string; body: string; readAt?: string; createdAt: string };

export default function NotificationsPage() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { data, loading, reload, error } = useApi<Page<N>>("notifications?pageSize=50");
  const readAll = useAction(async () => { await api("notifications/read-all", { body: {} }); await reload(); toast(t("notif.markAll")); });
  const read = useAction(async (id: string) => { await api(`notifications/${id}/read`, { body: {} }); await reload(); });
  const unread = data?.data.filter((n) => !n.readAt).length ?? 0;
  return (
    <>
      <PageHeader eyebrow={t("nav.account")} title={t("nav.notifications")} subtitle={unread > 0 ? t("kit.unread", { n: unread }) : undefined}
        actions={<Button variant="secondary" loading={readAll.loading} disabled={unread === 0} onClick={() => readAll.run()}><Icon name="check" className="h-4 w-4" />{t("notif.markAll")}</Button>} />
      <ErrorText error={error ?? readAll.error ?? read.error} />
      {loading && !data ? <Loading variant="list" /> : data?.data.length === 0 ? <Empty icon="bell">{t("notif.empty")}</Empty> : (
        <ul className="max-w-3xl overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
          {data?.data.map((n) => (
            <li key={n.id} className={cx("flex items-start gap-3 border-b border-line px-5 py-4 last:border-0", !n.readAt && "bg-brand-soft")}>
              <span className={cx("mt-1.5 h-2 w-2 shrink-0 rounded-full", n.readAt ? "bg-transparent" : "bg-brand")} />
              <div className="min-w-0 flex-1">
                <p className={cx("text-[13.5px] text-ink", n.readAt ? "font-medium" : "font-semibold")}>{n.title}</p>
                <p className="mt-0.5 text-[13px] leading-relaxed text-muted">{n.body}</p>
                <p className="mt-1 text-[11.5px] text-faint tnum">{dateTime(n.createdAt, locale)}</p>
              </div>
              {!n.readAt && <Button size="sm" variant="ghost" onClick={() => read.run(n.id)}>{t("notif.markRead")}</Button>}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
