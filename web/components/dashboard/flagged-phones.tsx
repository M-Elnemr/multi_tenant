"use client";

import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Badge, Button, Card, ErrorText } from "../ui";

type Flag = { phone: string; deliveredCount: number; returnedCount: number; cancelledCount: number; blocked: boolean; note: string };

/** Numbers that returned or cancelled orders, and numbers you blocked. Blocked numbers cannot place orders online. */
export function FlaggedPhones() {
  const { t } = useI18n();
  const { can } = useMe();
  const flags = useApi<Flag[]>("store/phone-flags");
  const set = useAction(async (f: Flag) => { await api("store/phone-flags", { body: { phone: f.phone, blocked: !f.blocked, note: f.note } }); await flags.reload(); });
  if (!flags.data || flags.data.length === 0) return null;
  return (
    <Card className="mb-6 space-y-3">
      <div><h2 className="font-semibold">⚠️ {t("flags.title")}</h2><p className="text-sm text-slate-500">{t("flags.hint")}</p></div>
      <ul className="divide-y text-sm">
        {flags.data.map((f) => (
          <li key={f.phone} className="flex flex-wrap items-center justify-between gap-2 py-2.5">
            <div><span dir="ltr" className="font-mono">{f.phone}</span> {f.blocked && <Badge tone="red">{t("flags.blocked")}</Badge>}<p className="text-xs text-slate-500">{t("flags.counts", { d: f.deliveredCount, r: f.returnedCount, c: f.cancelledCount })}</p></div>
            {can("order.update_status") && <Button size="sm" variant={f.blocked ? "secondary" : "danger"} loading={set.loading} onClick={() => set.run(f)}>{f.blocked ? t("history.unblock") : t("history.block")}</Button>}
          </li>
        ))}
      </ul>
      <ErrorText error={set.error} />
    </Card>
  );
}
