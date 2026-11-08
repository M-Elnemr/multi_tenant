"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ChannelIcon } from "@/components/channel-icons";
import { ConfirmDialog, Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { ImageUploader } from "@/components/uploader";
import { Alert, Badge, Button, Empty, ErrorText, Field, Input, Pager, Select, Textarea } from "@/components/ui";
import { SearchBox } from "@/components/dashboard/filters";
import { dateTime } from "@/lib/format";
import { fileUrl } from "@/lib/media";
import { AI_GRADIENT, AI_SOFT, CardSkeletons, PillTabs, SwitchRow, errText } from "./kit";

export type Overview = { channels: { id: string; platform: string; externalId: string; name?: string; status: string }[]; adAccounts: { id: string; label: string; externalId: string; country: string; status: string; lastError?: string }[]; imagesAvailable: boolean };
type Result = { channel: string; name?: string; ok: boolean; id?: string; error?: string };
export type Post = { id: string; productId?: string; productName?: string; kind: "PRODUCT" | "SHOP"; channels: string[]; caption: string; imageFileId?: string; imageUrl?: string; status: string; scheduledAt?: string; postedAt?: string; results: Result[]; createdAt: string };
type ProductRow = { id: string; name: string };

const TONE: Record<string, "green" | "red" | "amber" | "blue" | "slate"> = { POSTED: "green", PARTIAL: "amber", FAILED: "red", SCHEDULED: "blue", POSTING: "blue", DRAFT: "slate" };
const picture = (p: Post) => (p.imageFileId ? fileUrl(p.imageFileId, "medium") : p.imageUrl ?? null);
const platformIcon = (p: string) => p;

function PostEditor({ id, imagesAvailable, onClose, onChanged }: { id: string; imagesAvailable: boolean; onClose: () => void; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { data, error, reload } = useApi<Post>(`store/marketing/posts/${id}`);
  const [caption, setCaption] = useState<string | null>(null);
  const [when, setWhen] = useState("");
  const [askDelete, setAskDelete] = useState(false);
  const done = async () => { await reload(); onChanged(); };
  const patch = useAction(async (body: Record<string, unknown>) => { await api(`store/marketing/posts/${id}`, { method: "PATCH", body }); await done(); });
  const regen = useAction(async (what: "caption" | "image") => { if (caption !== null) await api(`store/marketing/posts/${id}`, { method: "PATCH", body: { caption } }); await api(`store/marketing/posts/${id}/regenerate`, { body: { what } }); setCaption(null); await done(); });
  const publish = useAction(async () => { if (caption !== null) await api(`store/marketing/posts/${id}`, { method: "PATCH", body: { caption } }); await api(`store/marketing/posts/${id}/publish`, { body: {} }); setCaption(null); toast(t("mkx.published")); await done(); });
  const schedule = useAction(async () => { if (caption !== null) await api(`store/marketing/posts/${id}`, { method: "PATCH", body: { caption } }); await api(`store/marketing/posts/${id}/schedule`, { body: { at: new Date(when).toISOString() } }); setCaption(null); toast(t("mkx.scheduled")); await done(); });
  const unschedule = useAction(async () => { await api(`store/marketing/posts/${id}/unschedule`, { body: {} }); await done(); });
  const del = useAction(async () => { await api(`store/marketing/posts/${id}`, { method: "DELETE" }); toast(t("mkx.deleted")); onChanged(); onClose(); });
  const ov = useApi<Overview>("store/marketing/overview");
  const locked = data?.status === "POSTED" || data?.status === "POSTING";
  const text = caption ?? data?.caption ?? "";
  const pic = data ? picture(data) : null;
  const platforms = Array.from(new Set((ov.data?.channels ?? []).map((c) => c.platform)));
  const busy = patch.loading || regen.loading || publish.loading || schedule.loading;
  const err = error ?? patch.error ?? regen.error ?? publish.error ?? schedule.error ?? unschedule.error ?? del.error;
  return (
    <Sheet open onClose={onClose} width="54rem" title={data ? (data.kind === "SHOP" ? t("mk.shopPost") : data.productName ?? t("mk.post")) : t("mk.post")}
      footer={data && !locked ? (
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="ink" loading={publish.loading} disabled={busy || data.channels.length === 0} onClick={() => void publish.run()}><Icon name="send" className="h-4 w-4 flip-rtl" />{data.status === "FAILED" || data.status === "PARTIAL" ? t("mk.retry") : t("mk.postNow")}</Button>
          {data.status === "SCHEDULED" && <Button variant="secondary" loading={unschedule.loading} onClick={() => void unschedule.run()}>{t("mk.unschedule")}</Button>}
          <Button variant="ghost" className="ms-auto text-red-600" onClick={() => setAskDelete(true)}>{t("common.delete")}</Button>
        </div>
      ) : undefined}>
      {!data ? (err ? <ErrorText error={err} /> : <div className="skeleton h-96 rounded-[18px]" />) : (
        <div className="grid gap-6 md:grid-cols-2">
          <div className="space-y-3">
            <div className="relative aspect-square overflow-hidden rounded-[18px] bg-canvas ring-1 ring-line">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {pic ? <img src={pic} alt="" className="h-full w-full object-cover" /> : <div className="grid h-full place-items-center p-6 text-center text-sm text-muted"><span><Icon name="camera" className="mx-auto mb-2 h-8 w-8 text-faint" />{t("mk.noImage")}</span></div>}
              {regen.loading && <div className="absolute inset-0 grid place-items-center bg-surface/70"><div className="skeleton h-full w-full" /></div>}
            </div>
            {!locked && (
              <div className="flex flex-wrap gap-2">
                {imagesAvailable && <Button size="sm" variant="secondary" disabled={busy} onClick={() => void regen.run("image")}><Icon name="sparkles" className="h-4 w-4 text-violet-500" />{t("mk.newImage")}</Button>}
                <ImageUploader category="PRODUCT_IMAGE" label={t("mk.uploadImage")} onUploaded={(fid) => void patch.run({ imageFileId: fid })} />
                {pic && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void patch.run({ removeImage: true })}>{t("common.remove")}</Button>}
              </div>
            )}
          </div>
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2"><Badge tone={TONE[data.status] ?? "slate"}>{t(`mk.status.${data.status}`)}</Badge>{data.scheduledAt && data.status === "SCHEDULED" && <span className="text-[12px] text-muted">{dateTime(data.scheduledAt, locale)}</span>}{data.postedAt && <span className="text-[12px] text-muted">{dateTime(data.postedAt, locale)}</span>}</div>
            <Field label={t("mk.caption")}><Textarea rows={9} value={text} disabled={locked} onChange={(e) => setCaption(e.target.value)} /></Field>
            {!locked && (
              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => void regen.run("caption")}><Icon name="sparkles" className="h-4 w-4 text-violet-500" />{t("mk.rewrite")}</Button>
                {caption !== null && <Button size="sm" variant="ink" disabled={busy} onClick={() => void patch.run({ caption }).then(() => { setCaption(null); toast(t("common.saved")); })}>{t("common.save")}</Button>}
              </div>
            )}
            <fieldset className="space-y-2" disabled={locked}>
              <legend className="mb-1.5 text-[13px] font-semibold text-ink">{t("mk.postTo")}</legend>
              {(platforms.length ? platforms : ["FACEBOOK", "INSTAGRAM"]).map((p) => {
                const names = (ov.data?.channels ?? []).filter((c) => c.platform === p).map((c) => c.name).filter(Boolean).join(", ");
                const on = data.channels.includes(p);
                return (
                  <label key={p} className={`flex cursor-pointer items-center gap-2.5 rounded-[12px] p-2.5 text-[13px] ring-1 transition ${on ? "bg-brand-soft/60 ring-brand" : "bg-surface ring-line hover:ring-line-strong"}`}>
                    <input type="checkbox" className="sr-only" checked={on} onChange={(e) => void patch.run({ channels: e.target.checked ? [...data.channels, p] : data.channels.filter((x) => x !== p) })} />
                    <ChannelIcon channel={platformIcon(p)} className="h-4 w-4" /><span className="font-semibold text-ink">{t(`ai.platform.${p}`)}</span>{names && <span className="min-w-0 truncate text-[12px] text-muted">· {names}</span>}
                    <span className="ms-auto">{on && <Icon name="check" className="h-4 w-4 text-brand" />}</span>
                  </label>
                );
              })}
              {data.channels.includes("INSTAGRAM") && !pic && <p className="text-[12px] text-amber-700">{t("mk.igNeedsImage")}</p>}
            </fieldset>
            {data.results.length > 0 && (
              <ul className="space-y-1.5">{data.results.filter((r) => r.channel !== "-" || !r.ok).map((r, i) => <li key={i}><Alert tone={r.ok ? "green" : "red"}>{t(`ai.platform.${r.channel}`) === `ai.platform.${r.channel}` ? r.channel : t(`ai.platform.${r.channel}`)}{r.name ? ` · ${r.name}` : ""}: {r.ok ? t("mk.posted") : r.error}</Alert></li>)}</ul>
            )}
            <ErrorText error={err} />
            {!locked && (
              <div className="flex flex-wrap items-end gap-2 rounded-[14px] bg-canvas p-3 ring-1 ring-line">
                <div className="min-w-0 flex-1"><Field label={t("mk.scheduleFor")}><Input type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} /></Field></div>
                <Button variant="secondary" loading={schedule.loading} disabled={!when || busy || data.channels.length === 0} onClick={() => void schedule.run()}><Icon name="clock" className="h-4 w-4" />{t("mk.schedule")}</Button>
              </div>
            )}
          </div>
        </div>
      )}
      <ConfirmDialog open={askDelete} onClose={() => setAskDelete(false)} danger loading={del.loading} onConfirm={() => { setAskDelete(false); void del.run(); }}
        title={t("mkx.deleteTitle")} body={t("mkx.deleteBody")} confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} />
    </Sheet>
  );
}

const TONES = ["friendly", "professional", "playful", "luxury", "urgent"];

function Composer({ ov, canWrite, onCreated }: { ov: Overview; canWrite: boolean; onCreated: (id: string) => void }) {
  const { t, locale } = useI18n();
  const [kind, setKind] = useState<"PRODUCT" | "SHOP">("PRODUCT");
  const [q, setQ] = useState("");
  const [product, setProduct] = useState<ProductRow | null>(null);
  const [tone, setTone] = useState("friendly");
  const [lang, setLang] = useState(locale);
  const [withImage, setWithImage] = useState(ov.imagesAvailable);
  const platforms = Array.from(new Set(ov.channels.map((c) => c.platform)));
  const [chans, setChans] = useState<string[]>(platforms);
  const found = useApi<Page<ProductRow>>(kind === "PRODUCT" && !product ? `store/products?q=${encodeURIComponent(q)}&page=1` : null);
  const go = useAction(async () => {
    const p = await api<{ id: string }>("store/marketing/posts/draft", { body: { kind, productId: kind === "PRODUCT" ? product?.id : undefined, language: lang, tone, withImage, channels: chans } });
    onCreated(p.id);
  });
  return (
    <section className={`overflow-hidden rounded-[18px] ring-1 ring-violet-200/60 ${AI_SOFT}`}>
      <div className="space-y-4 p-4 sm:p-6">
        <div className="flex items-center gap-3">
          <span className={`grid h-11 w-11 shrink-0 place-items-center rounded-[13px] text-white ${AI_GRADIENT}`}><Icon name="sparkles" className="h-5 w-5" /></span>
          <div className="min-w-0"><h2 className="font-display text-[17px] font-semibold text-ink">{t("mk.compose")}</h2><p className="text-[13px] text-muted">{t("mk.compose.sub")}</p></div>
        </div>
        <PillTabs id="mk-kind" value={kind} onChange={setKind} tabs={(["PRODUCT", "SHOP"] as const).map((k) => ({ key: k, label: t(`mk.kind.${k}`) }))} />
        {kind === "PRODUCT" && (
          product ? (
            <div className="flex items-center justify-between gap-3 rounded-[12px] bg-surface px-3.5 py-2.5 text-[13.5px] ring-2 ring-brand"><span className="min-w-0 truncate font-semibold">{product.name}</span><button type="button" className="shrink-0 text-[12.5px] font-semibold text-brand hover:underline" onClick={() => setProduct(null)}>{t("mk.change")}</button></div>
          ) : (
            <div className="space-y-2">
              <SearchBox value={q} onChange={setQ} placeholder={t("mk.pickProduct")} />
              <ul className="max-h-48 divide-y divide-line overflow-y-auto rounded-[12px] bg-surface ring-1 ring-line">
                {!found.data && <li className="p-3"><div className="skeleton h-4 w-1/2" /></li>}
                {found.data?.data.map((p) => <li key={p.id}><button type="button" className="w-full px-3.5 py-2.5 text-start text-[13.5px] transition hover:bg-surface-2" onClick={() => setProduct(p)}>{p.name}</button></li>)}
                {found.data && found.data.data.length === 0 && <li className="p-3 text-[13px] text-muted">{t("ch.link.none")}</li>}
              </ul>
            </div>
          )
        )}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("mk.tone")}><Select value={tone} onChange={(e) => setTone(e.target.value)}>{TONES.map((x) => <option key={x} value={x}>{t(`mk.tone.${x}`)}</option>)}</Select></Field>
          <Field label={t("mk.language")}><Select value={lang} onChange={(e) => setLang(e.target.value as "ar" | "en")}><option value="ar">العربية</option><option value="en">English</option></Select></Field>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {platforms.map((p) => {
            const on = chans.includes(p);
            return <button key={p} type="button" aria-pressed={on} onClick={() => setChans(on ? chans.filter((x) => x !== p) : [...chans, p])} className={`chip ${on ? "chip-on" : ""}`}><ChannelIcon channel={platformIcon(p)} className="h-3.5 w-3.5" />{t(`ai.platform.${p}`)}</button>;
          })}
        </div>
        {ov.imagesAvailable ? <SwitchRow title={<span className="inline-flex items-center gap-1.5"><Icon name="sparkles" className="h-4 w-4 text-violet-500" />{t("mk.withImage")}</span>} checked={withImage} onChange={setWithImage} /> : <p className="text-[12px] text-muted">{t("mk.noAiImages")}</p>}
        <ErrorText error={go.error} />
        <div className="flex justify-end"><Button variant="ink" size="lg" loading={go.loading} disabled={!canWrite || (kind === "PRODUCT" && !product) || chans.length === 0} onClick={() => void go.run()}><Icon name="sparkles" className="h-4 w-4" />{t("mk.generate")}</Button></div>
      </div>
    </section>
  );
}

export function PostsView({ ov, canWrite }: { ov: Overview; canWrite: boolean }) {
  const { t, locale } = useI18n();
  const reduce = useReducedMotion();
  const [page, setPage] = useState(1);
  const [open, setOpen] = useState<string | null>(null);
  const [status, setStatus] = useState<string[]>([]);
  const { data, loading, reload } = useApi<Page<Post>>(`store/marketing/posts?page=${page}`);
  const rows = (data?.data ?? []).filter((p) => status.length === 0 || status.includes(p.status));
  return (
    <div className="space-y-5">
      {ov.channels.length === 0 ? null : <Composer ov={ov} canWrite={canWrite} onCreated={(id) => { setOpen(id); void reload(); }} />}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-display text-[16px] font-semibold text-ink">{t("mk.history")}</h2>
        <div className="chip-row -mx-1 flex gap-1.5 overflow-x-auto px-1 [scrollbar-width:none]">
          {["POSTED", "SCHEDULED", "DRAFT", "FAILED"].map((s) => { const on = status.includes(s); return <button key={s} type="button" aria-pressed={on} onClick={() => setStatus(on ? status.filter((x) => x !== s) : [...status, s])} className={`chip ${on ? "chip-on" : ""}`}>{t(`mk.status.${s}`)}</button>; })}
        </div>
      </div>
      {loading && !data ? <CardSkeletons n={6} className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" h="h-64" /> : rows.length === 0 ? <Empty icon="edit" title={t("mk.noPosts")} /> : (
        <>
          <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            <AnimatePresence initial={false}>
              {rows.map((p) => (
                <motion.li key={p.id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}>
                  <button type="button" onClick={() => setOpen(p.id)} className="hover-lift group flex h-full w-full flex-col overflow-hidden rounded-[18px] bg-surface text-start ring-1 ring-line">
                    <div className="relative aspect-[16/9] bg-canvas">
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      {picture(p) ? <img src={picture(p)!} alt="" className="h-full w-full object-cover" /> : <div className="grid h-full place-items-center text-faint"><Icon name="edit" className="h-8 w-8" /></div>}
                      <span className="absolute start-3 top-3"><Badge tone={TONE[p.status] ?? "slate"}>{t(`mk.status.${p.status}`)}</Badge></span>
                    </div>
                    <div className="flex flex-1 flex-col gap-2 p-4">
                      <p className="line-clamp-3 flex-1 text-[13.5px] leading-relaxed text-ink-2">{p.caption || "—"}</p>
                      <div className="flex items-center justify-between gap-2 text-[12px] text-muted">
                        <span className="flex min-w-0 items-center gap-1.5"><span className="flex shrink-0 -space-x-1 rtl:space-x-reverse">{p.channels.map((c) => <ChannelIcon key={c} channel={platformIcon(c)} className="h-3.5 w-3.5" />)}</span><span className="truncate">{p.kind === "SHOP" ? t("mk.kind.SHOP") : p.productName}</span></span>
                        <span className="shrink-0 tnum">{dateTime(p.postedAt ?? p.scheduledAt ?? p.createdAt, locale)}</span>
                      </div>
                    </div>
                  </button>
                </motion.li>
              ))}
            </AnimatePresence>
          </ul>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      {open && <PostEditor id={open} imagesAvailable={ov.imagesAvailable} onClose={() => setOpen(null)} onChanged={() => void reload()} />}
    </div>
  );
}
