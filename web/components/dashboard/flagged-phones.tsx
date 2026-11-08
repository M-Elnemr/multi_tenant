"use client";

import { useState } from "react";
import { localPhone } from "@/lib/phone";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { ConfirmDialog } from "../overlay";
import { useToast } from "../toast";
import { Badge } from "../ui";
import { errText } from "./marketing/kit";

type Flag = { phone: string; deliveredCount: number; returnedCount: number; cancelledCount: number; blocked: boolean; note: string };

/** Numbers that returned or cancelled orders, and numbers you blocked. Blocked numbers cannot place orders online. */
export function FlaggedPhones() {
  const { t } = useI18n();
  const toast = useToast();
  const { can } = useMe();
  const flags = useApi<Flag[]>("store/phone-flags");
  const [ask, setAsk] = useState<Flag | null>(null);
  const [open, setOpen] = useState(true);
  const set = useAction(async (f: Flag) => {
    try {
      await api("store/phone-flags", { body: { phone: f.phone, blocked: !f.blocked, note: f.note } });
      toast(f.blocked ? t("cux.unblocked") : t("cux.blocked"));
      await flags.reload();
    } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
    setAsk(null);
  });
  if (!flags.data || flags.data.length === 0) return null;
  const blocked = flags.data.filter((f) => f.blocked).length;
  return (
    <section className="overflow-hidden rounded-[18px] bg-surface ring-1 ring-amber-200/80">
      <button type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open} className="flex w-full items-start gap-3 bg-amber-50/70 px-5 py-4 text-start">
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[11px] bg-amber-100 text-amber-700"><Icon name="shield" className="h-[18px] w-[18px]" /></span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2 font-display text-[15px] font-semibold text-ink">{t("flags.title")}<Badge tone="amber">{flags.data.length}</Badge>{blocked > 0 && <Badge tone="red">{t("flags.blocked")} · {blocked}</Badge>}</span>
          <span className="mt-0.5 block text-[13px] text-muted">{t("flags.hint")}</span>
        </span>
        <Icon name="chevron" className={`mt-2 h-4 w-4 shrink-0 text-muted transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <ul className="divide-y divide-line">
          {flags.data.map((f) => (
            <li key={f.phone} className="flex flex-wrap items-center justify-between gap-3 px-5 py-3">
              <div className="min-w-0">
                <p className="flex items-center gap-2"><span dir="ltr" className="font-mono text-[13.5px] font-semibold tnum text-ink">{localPhone(f.phone)}</span>{f.blocked && <Badge tone="red">{t("flags.blocked")}</Badge>}</p>
                <p className="mt-0.5 text-[12px] text-muted">{t("flags.counts", { d: f.deliveredCount, r: f.returnedCount, c: f.cancelledCount })}</p>
              </div>
              {can("order.update_status") && (
                <button type="button" disabled={set.loading} onClick={() => (f.blocked ? void set.run(f) : setAsk(f))}
                  className={`inline-flex h-9 items-center gap-1.5 rounded-[10px] px-3 text-[13px] font-semibold transition disabled:opacity-60 ${f.blocked ? "text-ink-2 ring-1 ring-line-strong hover:bg-surface-2" : "bg-red-50 text-red-700 ring-1 ring-inset ring-red-600/15 hover:bg-red-100"}`}>
                  <Icon name={f.blocked ? "check" : "xcircle"} className="h-4 w-4" />{f.blocked ? t("history.unblock") : t("history.block")}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog open={!!ask} onClose={() => setAsk(null)} onConfirm={() => ask && void set.run(ask)} loading={set.loading} danger
        title={t("cux.blockTitle", { phone: ask ? localPhone(ask.phone) : "" })} body={t("cux.blockBody")} confirmLabel={t("history.block")} cancelLabel={t("common.cancel")} />
    </section>
  );
}
