"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, money, toMinor } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { fileUrl } from "@/lib/media";
import { ImageUploader } from "@/components/uploader";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Sheet, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Badge, Button, Empty, ErrorText, Field, Input, Select, Textarea } from "@/components/ui";
import type { Category, Service } from "./common";

type Q = { key: string; label: string; required: boolean };
type Form = { name: string; shortDescription: string; description: string; durationMinutes: string; bufferMinutes: string; price: string; priceBefore: string; active: boolean; categoryId: string; locationMode: "SHOP" | "HOME" | "BOTH";
  serviceArea: string; maxConcurrent: string; instructions: string; cancellationPolicy: string; imageFileId: string; gallery: string[]; questions: Q[] };
const BLANK: Form = { name: "", shortDescription: "", description: "", durationMinutes: "60", bufferMinutes: "0", price: "", priceBefore: "", active: true, categoryId: "", locationMode: "SHOP", serviceArea: "", maxConcurrent: "", instructions: "", cancellationPolicy: "", imageFileId: "", gallery: [], questions: [] };

const fromSvc = (s: Service): Form => ({ name: s.name, shortDescription: s.shortDescription ?? "", description: s.description ?? "", durationMinutes: String(s.durationMinutes), bufferMinutes: String(s.bufferMinutes), price: fromMinor(s.priceMinor), priceBefore: s.priceBeforeMinor ? fromMinor(s.priceBeforeMinor) : "", active: s.isActive,
  categoryId: s.categoryId ?? "", locationMode: s.locationMode ?? "SHOP", serviceArea: s.serviceArea ?? "", maxConcurrent: s.maxConcurrent ? String(s.maxConcurrent) : "", instructions: s.instructions ?? "", cancellationPolicy: s.cancellationPolicy ?? "", imageFileId: s.imageFileId ?? "", gallery: s.gallery ?? [],
  questions: (s.requiredFields ?? []).map((q) => ({ key: q.key, label: q.label, required: q.required !== false })) });

const slug = (label: string, taken: string[]) => { const base = label.trim().replace(/[^A-Za-z0-9]+/g, " ").trim().split(" ").filter(Boolean).map((w, i) => (i === 0 ? w.toLowerCase() : w[0].toUpperCase() + w.slice(1).toLowerCase())).join("") || "q"; let k = /^[a-z]/.test(base) ? base : "q" + base; let n = 2; while (taken.includes(k)) k = base + n++; return k.slice(0, 30); };

function Group({ title, children, open }: { title: string; children: React.ReactNode; open?: boolean }) {
  return <details open={open} className="group rounded-[12px] ring-1 ring-line"><summary className="flex cursor-pointer list-none items-center justify-between gap-2 px-3.5 py-3 text-sm font-semibold text-ink-2">{title}<Icon name="chevron" className="h-4 w-4 text-muted transition-transform group-open:rotate-90" /></summary><div className="space-y-4 px-3.5 pb-4">{children}</div></details>;
}

/** Extras of one service (a beard trim after a haircut, a new candle set): each lengthens the appointment and adds its price. Saved one by one, so the service must exist first. */
function Addons({ svc, onChanged }: { svc: Service; onChanged: () => void }) {
  const { t, currency, locale } = useI18n();
  const [name, setName] = useState(""); const [price, setPrice] = useState(""); const [mins, setMins] = useState("0");
  const add = useAction(async () => { await api(`store/services/${svc.id}/addons`, { body: { name: name.trim(), priceMinor: price.trim() ? toMinor(price) : 0, durationMinutes: Number(mins || 0) } }); setName(""); setPrice(""); setMins("0"); onChanged(); });
  const del = useAction(async (id: string) => { await api(`store/services/${svc.id}/addons/${id}`, { method: "DELETE" }); onChanged(); });
  return (
    <div className="space-y-3">
      <p className="text-[12.5px] text-muted">{t("svc.addons.hint")}</p>
      {(svc.addons ?? []).map((a) => <div key={a.id} className="flex items-center gap-2 rounded-[10px] bg-canvas px-3 py-2 text-[13px]"><span className="min-w-0 flex-1 truncate font-semibold text-ink">{a.name}</span><span className="tnum text-muted">+{money(a.priceMinor, currency, locale)} · +{a.durationMinutes} {t("bk.min")}</span><button type="button" aria-label={t("common.remove")} onClick={() => void del.run(a.id)} className="grid h-8 w-8 place-items-center rounded-full text-red-600 hover:bg-red-50">✕</button></div>)}
      <div className="grid grid-cols-[1fr_5.5rem_4.5rem_auto] items-end gap-2">
        <Field label={t("svc.addons.name")}><Input value={name} maxLength={120} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label={currency}><Input inputMode="decimal" dir="ltr" value={price} onChange={(e) => setPrice(e.target.value)} placeholder="0" /></Field>
        <Field label={t("bk.min")}><Input type="number" inputMode="numeric" min={0} max={480} dir="ltr" value={mins} onChange={(e) => setMins(e.target.value)} /></Field>
        <Button type="button" variant="secondary" loading={add.loading} disabled={!name.trim()} onClick={() => void add.run()}><Icon name="plus" className="h-4 w-4" /></Button>
      </div>
      <ErrorText error={add.error ?? del.error} />
    </div>
  );
}

function Editor({ svc, open, onClose, onDone, categories, onCategories }: { svc: Service | null; open: boolean; onClose: () => void; onDone: () => void; categories: Category[]; onCategories: () => void }) {
  const { t, currency } = useI18n();
  const toast = useToast();
  const [f, setF] = useState<Form>(svc ? fromSvc(svc) : BLANK);
  const [newCat, setNewCat] = useState("");
  const set = (p: Partial<Form>) => setF((x) => ({ ...x, ...p }));
  const addCat = useAction(async () => { const c = await api<Category>("store/service-categories", { body: { name: newCat.trim() } }); setNewCat(""); onCategories(); set({ categoryId: c.id }); });
  const save = useAction(async () => {
    const body = {
      name: f.name.trim(), description: f.description.trim(), shortDescription: f.shortDescription.trim(), durationMinutes: Number(f.durationMinutes), bufferMinutes: Number(f.bufferMinutes || 0), priceMinor: f.price.trim() ? toMinor(f.price) : 0,
      priceBeforeMinor: f.priceBefore.trim() ? toMinor(f.priceBefore) : undefined, active: f.active, categoryId: f.categoryId || undefined, locationMode: f.locationMode, serviceArea: f.serviceArea.trim(), maxConcurrent: f.maxConcurrent.trim() ? Number(f.maxConcurrent) : undefined,
      instructions: f.instructions.trim(), cancellationPolicy: f.cancellationPolicy.trim(), imageFileId: f.imageFileId || undefined, gallery: f.gallery, requiredFields: f.questions.filter((q) => q.label.trim()).map((q) => ({ key: q.key, label: q.label.trim(), required: q.required })),
    };
    if (svc) await api(`store/services/${svc.id}`, { method: "PATCH", body }); else await api("store/services", { body });
    toast(t("common.saved"));
    onDone();
  });
  const ok = f.name.trim() && Number(f.durationMinutes) >= 5;
  return (
    <Sheet open={open} onClose={onClose} title={svc ? t("bk.svc.edit") : t("bk.svc.add")} footer={<div className="flex justify-end gap-2"><Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button variant="ink" loading={save.loading} disabled={!ok} onClick={() => void save.run()}>{t("common.save")}</Button></div>}>
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); if (ok) void save.run(); }}>
        <Field label={t("bk.svc.name")}><Input value={f.name} maxLength={160} onChange={(e) => set({ name: e.target.value })} placeholder={t("bk.svc.namePh")} /></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t("bk.svc.duration")}><Input type="number" inputMode="numeric" min={5} max={720} dir="ltr" value={f.durationMinutes} onChange={(e) => set({ durationMinutes: e.target.value })} /></Field>
          <Field label={`${t("bk.price")} (${currency})`}><Input type="text" inputMode="decimal" dir="ltr" value={f.price} onChange={(e) => set({ price: e.target.value })} placeholder="0" /></Field>
        </div>
        <Field label={`${t("bk.svc.desc")} (${t("ch.optional")})`}><Textarea value={f.description} onChange={(e) => set({ description: e.target.value })} /></Field>
        <Group title={t("svc.g.show")}>
          <Field label={t("svc.category")}><Select value={f.categoryId} onChange={(e) => set({ categoryId: e.target.value })}><option value="">{t("svc.noCategory")}</option>{categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select></Field>
          <div className="flex items-end gap-2"><div className="flex-1"><Field label={t("svc.newCategory")}><Input value={newCat} maxLength={120} onChange={(e) => setNewCat(e.target.value)} /></Field></div><Button type="button" variant="secondary" loading={addCat.loading} disabled={!newCat.trim()} onClick={() => void addCat.run()}><Icon name="plus" className="h-4 w-4" /></Button></div>
          <Field label={`${t("svc.short")} (${t("ch.optional")})`}><Input value={f.shortDescription} maxLength={240} onChange={(e) => set({ shortDescription: e.target.value })} /></Field>
          <Field label={`${t("svc.priceBefore")} (${currency})`} hint={t("svc.priceBeforeHint")}><Input inputMode="decimal" dir="ltr" value={f.priceBefore} onChange={(e) => set({ priceBefore: e.target.value })} placeholder="0" /></Field>
          <div className="space-y-2"><span className="text-[13px] font-semibold text-ink-2">{t("svc.cover")}</span>
            {f.imageFileId && /* eslint-disable-next-line @next/next/no-img-element */ <img src={fileUrl(f.imageFileId, "thumb")} alt="" className="h-24 w-40 rounded-lg object-cover" />}
            <ImageUploader category="PRODUCT_IMAGE" aspect={4 / 3} label={t("svc.coverUpload")} onUploaded={(id) => set({ imageFileId: id })} /></div>
          <div className="space-y-2"><span className="text-[13px] font-semibold text-ink-2">{t("svc.gallery")}</span>
            <div className="flex flex-wrap gap-2">{f.gallery.map((id) => <div key={id} className="relative">{/* eslint-disable-next-line @next/next/no-img-element */}<img src={fileUrl(id, "thumb")} alt="" className="h-16 w-16 rounded-lg object-cover" /><button type="button" aria-label={t("common.remove")} onClick={() => set({ gallery: f.gallery.filter((x) => x !== id) })} className="absolute -end-2 -top-2 grid h-6 w-6 place-items-center rounded-full bg-red-600 text-xs text-white shadow">✕</button></div>)}</div>
            {f.gallery.length < 12 && <ImageUploader category="PRODUCT_IMAGE" label={t("svc.galleryAdd")} onUploaded={(id) => set({ gallery: [...f.gallery, id] })} />}</div>
        </Group>
        <Group title={t("svc.g.where")}>
          <Field label={t("svc.location")}><Select value={f.locationMode} onChange={(e) => set({ locationMode: e.target.value as Form["locationMode"] })}><option value="SHOP">{t("svc.loc.SHOP")}</option><option value="HOME">{t("svc.loc.HOME")}</option><option value="BOTH">{t("svc.loc.BOTH")}</option></Select></Field>
          {f.locationMode !== "SHOP" && <Field label={`${t("svc.area")} (${t("ch.optional")})`} hint={t("svc.areaHint")}><Textarea value={f.serviceArea} onChange={(e) => set({ serviceArea: e.target.value })} /></Field>}
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("bk.svc.buffer")} hint={t("bk.svc.bufferHint")}><Input type="number" inputMode="numeric" min={0} max={240} dir="ltr" value={f.bufferMinutes} onChange={(e) => set({ bufferMinutes: e.target.value })} /></Field>
            <Field label={t("svc.maxConcurrent")} hint={t("svc.maxConcurrentHint")}><Input type="number" inputMode="numeric" min={1} max={50} dir="ltr" value={f.maxConcurrent} onChange={(e) => set({ maxConcurrent: e.target.value })} /></Field>
          </div>
        </Group>
        <Group title={t("svc.g.ask")}>
          <p className="text-[12.5px] text-muted">{t("svc.ask.hint")}</p>
          {f.questions.map((q, i) => (
            <div key={q.key} className="flex items-center gap-2"><Input value={q.label} maxLength={120} onChange={(e) => set({ questions: f.questions.map((x, k) => (k === i ? { ...x, label: e.target.value } : x)) })} placeholder={t("svc.ask.ph")} />
              <label className="flex shrink-0 items-center gap-1.5 text-[12.5px] text-muted"><input type="checkbox" checked={q.required} onChange={(e) => set({ questions: f.questions.map((x, k) => (k === i ? { ...x, required: e.target.checked } : x)) })} />{t("svc.ask.required")}</label>
              <button type="button" aria-label={t("common.remove")} onClick={() => set({ questions: f.questions.filter((_, k) => k !== i) })} className="grid h-9 w-9 shrink-0 place-items-center rounded-full text-red-600 hover:bg-red-50">✕</button></div>
          ))}
          {f.questions.length < 12 && <Button type="button" variant="secondary" onClick={() => set({ questions: [...f.questions, { key: slug("q" + (f.questions.length + 1), f.questions.map((x) => x.key)), label: "", required: true }] })}><Icon name="plus" className="h-4 w-4" />{t("svc.ask.add")}</Button>}
        </Group>
        <Group title={t("svc.g.policy")}>
          <Field label={`${t("svc.instructions")} (${t("ch.optional")})`} hint={t("svc.instructionsHint")}><Textarea value={f.instructions} onChange={(e) => set({ instructions: e.target.value })} /></Field>
          <Field label={`${t("svc.cancelPolicy")} (${t("ch.optional")})`}><Textarea value={f.cancellationPolicy} onChange={(e) => set({ cancellationPolicy: e.target.value })} /></Field>
        </Group>
        {svc && <Group title={t("svc.g.addons")}><Addons svc={svc} onChanged={onDone} /></Group>}
        <div className="flex items-center justify-between rounded-[12px] bg-canvas p-3 ring-1 ring-line"><span className="text-sm font-semibold text-ink-2">{t("bk.svc.active")}</span><Switch checked={f.active} onChange={(v) => set({ active: v })} label={t("bk.svc.active")} /></div>
        <ErrorText error={save.error ?? addCat.error} />
      </form>
    </Sheet>
  );
}

export function Services({ services, canManage, onChanged }: { services: Service[]; canManage: boolean; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [edit, setEdit] = useState<Service | "new" | null>(null);
  const [del, setDel] = useState<Service | null>(null);
  const cats = useApi<Category[]>("store/service-categories");
  const remove = useAction(async (id: string) => { await api(`store/services/${id}`, { method: "DELETE" }); toast(t("common.saved")); setDel(null); onChanged(); });
  return (
    <div className="space-y-4">
      {canManage && <div className="flex justify-end"><Button variant="ink" onClick={() => setEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("bk.svc.add")}</Button></div>}
      {services.length === 0 ? <Empty icon="services" title={t("bk.svc.none.title")} action={canManage ? <Button variant="ink" onClick={() => setEdit("new")}>{t("bk.svc.add")}</Button> : undefined}>{t("bk.svc.none.body")}</Empty> : (
        <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {services.map((s) => (
            <li key={s.id} className={`flex flex-col gap-3 rounded-[16px] bg-surface p-4 ring-1 ring-line ${s.isActive ? "" : "opacity-60"}`}>
              <div className="flex items-start justify-between gap-2"><p className="min-w-0 font-display text-[15px] font-semibold text-ink">{s.name}</p>{!s.isActive && <Badge tone="slate">{t("bk.svc.off")}</Badge>}</div>
              {s.description && <p className="line-clamp-2 text-[13px] text-muted">{s.description}</p>}
              <div className="mt-auto flex flex-wrap items-center gap-x-4 gap-y-1 text-[13px] text-ink-2">
                <span className="inline-flex items-center gap-1.5"><Icon name="clock" className="h-4 w-4 text-muted" /><span className="tnum">{s.durationMinutes} {t("bk.min")}</span></span>
                <span className="font-semibold tnum text-ink">{money(s.priceMinor, s.currency, locale)}</span>
              </div>
              {canManage && <div className="flex gap-2"><Button size="sm" variant="secondary" onClick={() => setEdit(s)}><Icon name="edit" className="h-4 w-4" />{t("common.edit")}</Button><Button size="sm" variant="ghost" className="text-red-600" onClick={() => setDel(s)}>{t("common.delete")}</Button></div>}
            </li>
          ))}
        </ul>
      )}
      {edit && <Editor key={edit === "new" ? "new" : edit.id} svc={edit === "new" ? null : edit} open categories={cats.data ?? []} onCategories={() => void cats.reload()} onClose={() => setEdit(null)} onDone={() => { setEdit(null); onChanged(); }} />}
      <ConfirmDialog open={!!del} onClose={() => setDel(null)} danger loading={remove.loading} onConfirm={() => del && void remove.run(del.id)} title={t("bk.svc.delete")} body={del ? t("bk.svc.deleteBody", { name: del.name }) : undefined} confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
