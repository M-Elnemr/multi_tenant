"use client";

import { DAYS, defaultHours, type WeekHours } from "@/lib/hours";
import { useT } from "../i18n-provider";
import { Input } from "../ui";

/** One row per weekday: open/closed + opening and closing time. */
export function HoursEditor({ value, onChange }: { value: WeekHours; onChange: (v: WeekHours) => void }) {
  const t = useT();
  const v = Object.keys(value ?? {}).length ? value : defaultHours();
  const set = (d: string, patch: Partial<WeekHours[string]>) => onChange({ ...v, [d]: { ...v[d], ...patch } });
  return (
    <div className="space-y-2">
      {DAYS.map((d) => (
        <div key={d} className="grid grid-cols-2 items-center gap-2 rounded-xl border border-slate-100 p-2 text-sm sm:grid-cols-[5.5rem_1fr_1fr_auto] sm:border-0 sm:p-0">   {/* phone: the day and "closed" on one line, the two times under them */}
          <span className="order-1 font-medium">{t(`day.${d}`)}</span>
          <Input className="order-3 min-w-0 sm:order-2" type="time" dir="ltr" value={v[d]?.open ?? "10:00"} disabled={v[d]?.closed} onChange={(e) => set(d, { open: e.target.value })} />
          <Input className="order-4 min-w-0 sm:order-3" type="time" dir="ltr" value={v[d]?.close ?? "22:00"} disabled={v[d]?.closed} onChange={(e) => set(d, { close: e.target.value })} />
          <label className="order-2 flex items-center justify-self-end gap-1 text-xs text-slate-600 sm:order-4"><input type="checkbox" checked={!!v[d]?.closed} onChange={(e) => set(d, { closed: e.target.checked })} />{t("hours.closed")}</label>
        </div>
      ))}
    </div>
  );
}
