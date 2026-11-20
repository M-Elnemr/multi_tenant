"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { fileUrl } from "@/lib/media";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Sheet, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { ImageUploader } from "@/components/uploader";
import { Badge, Button, Empty, ErrorText, Field, Input, Loading } from "@/components/ui";
import { WEEK, type Range, type Service, type Staff } from "./common";

type Form = { name: string; capacity: string; active: boolean; imageFileId: string; serviceIds: string[]; ownHours: boolean; days: Record<number, { open: boolean; opens: string; closes: string }> };
const blankDays = () => Object.fromEntries(WEEK.map((w) => [w, { open: w !== 5, opens: "10:00", closes: "18:00" }])) as Form["days"];

function fromStaff(s: Staff | null): Form {
  const days = blankDays();
  const own = (s?.hours ?? []).length > 0;
  if (own) WEEK.forEach((w) => { const r = (s!.hours as Range[]).find((x) => x.weekday === w); days[w] = r ? { open: true, opens: r.opens, closes: r.closes } : { open: false, opens: "10:00", closes: "18:00" }; });
  return { name: s?.name ?? "", capacity: String(s?.capacity ?? 1), active: s?.isActive ?? true, imageFileId: s?.imageFileId ?? "", serviceIds: s?.serviceIds ?? [], ownHours: own, days };
}

function Editor({ staff, services, onClose, onDone }: { staff: Staff | null; services: Service[]; onClose: () => void; onDone: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const [f, setF] = useState<Form>(fromStaff(staff));
  const [off, setOff] = useState("");
  const set = (p: Partial<Form>) => setF((x) => ({ ...x, ...p }));
  const save = useAction(async () => {
    const hours = f.ownHours ? WEEK.flatMap((w) => (f.days[w].open ? [{ weekday: w, opens: f.days[w].opens, closes: f.days[w].closes }] : [])) : [];
    const body = { name: f.name.trim(), capacity: Number(f.capacity || 1), active: f.active, imageFileId: f.imageFileId || undefined, serviceIds: f.serviceIds, hours };
    if (staff) await api(`store/staff/${staff.id}`, { method: "PATCH", body }); else await api("store/staff", { body });
    toast(t("common.saved"));
    onDone();
  });
  const addOff = useAction(async () => { if (staff && off) { await api(`store/staff/${staff.id}/days-off`, { body: { date: off } }); setOff(""); onDone(); } });
  const rmOff = useAction(async (d: string) => { if (staff) { await api(`store/staff/${staff.id}/days-off/${d}`, { method: "DELETE" }); onDone(); } });
  return (
    <Sheet open onClose={onClose} title={staff ? t("team.edit") : t("team.add")} footer={<div className="flex justify-end gap-2"><Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button variant="ink" loading={save.loading} disabled={!f.name.trim()} onClick={() => void save.run()}>{t("common.save")}</Button></div>}>
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); if (f.name.trim()) void save.run(); }}>
        <Field label={t("team.name")}><Input value={f.name} maxLength={120} onChange={(e) => set({ name: e.target.value })} /></Field>
        <div className="space-y-2"><span className="text-[13px] font-semibold text-ink-2">{t("team.photo")}</span>
          {f.imageFileId && /* eslint-disable-next-line @next/next/no-img-element */ <img src={fileUrl(f.imageFileId, "thumb")} alt="" className="h-16 w-16 rounded-full object-cover" />}
          <ImageUploader category="PRODUCT_IMAGE" label={t("team.photoUpload")} onUploaded={(id) => set({ imageFileId: id })} /></div>
        <div className="space-y-2"><span className="text-[13px] font-semibold text-ink-2">{t("team.does")}</span>
          {services.filter((s) => s.isActive).map((s) => <label key={s.id} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-[10px] bg-canvas px-3 text-sm"><input type="checkbox" checked={f.serviceIds.includes(s.id)} onChange={(e) => set({ serviceIds: e.target.checked ? [...f.serviceIds, s.id] : f.serviceIds.filter((x) => x !== s.id) })} />{s.name}</label>)}
          <p className="text-[12.5px] text-muted">{t("team.doesHint")}</p></div>
        <Field label={t("team.capacity")} hint={t("team.capacityHint")}><Input type="number" inputMode="numeric" min={1} max={10} dir="ltr" value={f.capacity} onChange={(e) => set({ capacity: e.target.value })} /></Field>
        <div className="flex items-start justify-between gap-4 rounded-[12px] bg-canvas p-3 ring-1 ring-line"><span><span className="block text-sm font-semibold text-ink-2">{t("team.ownHours")}</span><span className="block text-[12.5px] text-muted">{t("team.ownHoursHint")}</span></span><Switch checked={f.ownHours} onChange={(v) => set({ ownHours: v })} label={t("team.ownHours")} /></div>
        {f.ownHours && (
          <ul className="space-y-2">{WEEK.map((w) => (
            <li key={w} className="flex flex-wrap items-center gap-2"><span className="flex w-28 items-center gap-2"><Switch checked={f.days[w].open} onChange={(v) => set({ days: { ...f.days, [w]: { ...f.days[w], open: v } } })} label={t(`bk.day.${w}`)} /><span className="text-sm font-semibold">{t(`bk.day.${w}`)}</span></span>
              {f.days[w].open ? <><Input type="time" dir="ltr" className="!w-28" value={f.days[w].opens} onChange={(e) => set({ days: { ...f.days, [w]: { ...f.days[w], opens: e.target.value } } })} /><span className="text-muted">–</span><Input type="time" dir="ltr" className="!w-28" value={f.days[w].closes} onChange={(e) => set({ days: { ...f.days, [w]: { ...f.days[w], closes: e.target.value } } })} /></> : <span className="text-sm text-muted">{t("bk.hours.closed")}</span>}</li>
          ))}</ul>
        )}
        {staff && (
          <div className="space-y-2"><span className="text-[13px] font-semibold text-ink-2">{t("team.daysOff")}</span>
            <div className="flex items-end gap-2"><Field label={t("bk.date")} className="w-44"><Input type="date" dir="ltr" min={new Date().toISOString().slice(0, 10)} value={off} onChange={(e) => setOff(e.target.value)} /></Field><Button type="button" variant="secondary" loading={addOff.loading} disabled={!off} onClick={() => void addOff.run()}>{t("bk.off.add")}</Button></div>
            <ul className="flex flex-wrap gap-2">{staff.daysOff.map((d) => <li key={d.date} className="inline-flex items-center gap-2 rounded-full bg-canvas py-1 pe-1 ps-3 text-[13px] ring-1 ring-line"><span className="tnum font-semibold" dir="ltr">{d.date}</span><button type="button" aria-label={t("common.remove")} onClick={() => void rmOff.run(d.date)} className="grid h-8 w-8 place-items-center rounded-full text-red-600 hover:bg-red-50">✕</button></li>)}</ul></div>
        )}
        <div className="flex items-center justify-between rounded-[12px] bg-canvas p-3 ring-1 ring-line"><span className="text-sm font-semibold text-ink-2">{t("bk.svc.active")}</span><Switch checked={f.active} onChange={(v) => set({ active: v })} label={t("bk.svc.active")} /></div>
        <ErrorText error={save.error ?? addOff.error ?? rmOff.error} />
      </form>
    </Sheet>
  );
}

/** The team: optional. A one-person business leaves it empty and only the business capacity applies. */
export function Team({ services, canManage }: { services: Service[]; canManage: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const { data, loading, reload } = useApi<Staff[]>("store/staff");
  const [edit, setEdit] = useState<Staff | "new" | null>(null);
  const [del, setDel] = useState<Staff | null>(null);
  const remove = useAction(async (id: string) => { await api(`store/staff/${id}`, { method: "DELETE" }); toast(t("common.saved")); setDel(null); await reload(); });
  const name = (id: string) => services.find((s) => s.id === id)?.name ?? "";
  return (
    <div className="space-y-4">
      <p className="text-[13px] text-muted">{t("team.sub")}</p>
      {canManage && <div className="flex justify-end"><Button variant="ink" onClick={() => setEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("team.add")}</Button></div>}
      {loading && !data ? <Loading variant="table" /> : (data ?? []).length === 0 ? <Empty icon="users" title={t("team.none")}>{t("team.noneHint")}</Empty> : (
        <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {(data ?? []).map((s) => (
            <li key={s.id} className={`flex flex-col gap-3 rounded-[16px] bg-surface p-4 ring-1 ring-line ${s.isActive ? "" : "opacity-60"}`}>
              <div className="flex items-center gap-3">
                {s.imageFileId ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={fileUrl(s.imageFileId, "thumb")} alt="" className="h-12 w-12 rounded-full object-cover" /> : <span className="grid h-12 w-12 place-items-center rounded-full bg-brand-soft font-bold text-brand">{s.name.slice(0, 1)}</span>}
                <div className="min-w-0 flex-1"><p className="truncate font-display text-[15px] font-semibold text-ink">{s.name}</p><p className="truncate text-[12.5px] text-muted">{s.serviceIds.map(name).filter(Boolean).join(" · ") || t("team.noServices")}</p></div>
                {!s.isActive && <Badge tone="slate">{t("bk.svc.off")}</Badge>}
              </div>
              <p className="text-[12.5px] text-muted">{s.hours.length > 0 ? t("team.hasHours") : t("team.followsBusiness")} · {t("team.cap", { n: String(s.capacity) })}</p>
              {canManage && <div className="flex gap-2"><Button size="sm" variant="secondary" onClick={() => setEdit(s)}><Icon name="edit" className="h-4 w-4" />{t("common.edit")}</Button><Button size="sm" variant="secondary" onClick={() => setDel(s)}>{t("common.remove")}</Button></div>}
            </li>
          ))}
        </ul>
      )}
      {edit && <Editor key={edit === "new" ? "new" : edit.id} staff={edit === "new" ? null : edit} services={services} onClose={() => setEdit(null)} onDone={() => { setEdit(null); void reload(); }} />}
      <ConfirmDialog open={!!del} onClose={() => setDel(null)} danger loading={remove.loading} onConfirm={() => del && void remove.run(del.id)} title={t("team.delete")} body={del ? t("team.deleteBody", { name: del.name }) : undefined} confirmLabel={t("common.remove")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
