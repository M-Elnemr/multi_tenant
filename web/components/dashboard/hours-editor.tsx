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
        <div key={d} className="grid grid-cols-[5.5rem_1fr_1fr_auto] items-center gap-2 text-sm">
          <span className="font-medium">{t(`day.${d}`)}</span>
          <Input type="time" dir="ltr" value={v[d]?.open ?? "10:00"} disabled={v[d]?.closed} onChange={(e) => set(d, { open: e.target.value })} />
          <Input type="time" dir="ltr" value={v[d]?.close ?? "22:00"} disabled={v[d]?.closed} onChange={(e) => set(d, { close: e.target.value })} />
          <label className="flex items-center gap-1 text-xs text-slate-600"><input type="checkbox" checked={!!v[d]?.closed} onChange={(e) => set(d, { closed: e.target.checked })} />{t("hours.closed")}</label>
        </div>
      ))}
    </div>
  );
}
