"use client";

import Link from "next/link";
import { useEffect } from "react";
import { useApi } from "./hooks";
import { useT } from "./i18n-provider";
import { Icon } from "./icons";

/** The platform owner's bell: unread count (refreshed every 30 s), opens the notifications page. */
export function AdminBell() {
  const t = useT();
  const unread = useApi<{ count: number }>("notifications/unread-count");
  const { reload } = unread;
  useEffect(() => { const id = setInterval(() => void reload(), 30_000); return () => clearInterval(id); }, [reload]);
  const n = unread.data?.count ?? 0;
  return (
    <Link href="/admin/notifications" className="relative rounded-xl p-2.5 text-slate-600 transition hover:bg-brand-soft hover:text-brand" aria-label={t("nav.notifications")}>
      <Icon name="bell" />
      {n > 0 && <span className="absolute end-1 top-1 flex h-4 min-w-4 animate-pop items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white ring-2 ring-white">{n > 99 ? "99+" : n}</span>}
    </Link>
  );
}
