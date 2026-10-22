"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ImageUploader } from "@/components/uploader";
import { Alert, Badge, Button, Card, Empty, ErrorText, Field, Input, Loading, Modal, Pager, Select, Textarea } from "@/components/ui";
import { dateTime } from "@/lib/format";
import { fileUrl } from "@/lib/media";

export type Overview = { channels: { id: string; platform: string; externalId: string; name?: string; status: string }[]; adAccounts: { id: string; label: string; externalId: string; country: string; status: string; lastError?: string }[]; imagesAvailable: boolean };
type Result = { channel: string; name?: string; ok: boolean; id?: string; error?: string };
export type Post = { id: string; productId?: string; productName?: string; kind: "PRODUCT" | "SHOP"; channels: string[]; caption: string; imageFileId?: string; imageUrl?: string; status: string; scheduledAt?: string; postedAt?: string; results: Result[]; createdAt: string };
type ProductRow = { id: string; name: string };

const TONE: Record<string, "green" | "red" | "amber" | "blue" | "slate"> = { POSTED: "green", PARTIAL: "amber", FAILED: "red", SCHEDULED: "blue", POSTING: "blue", DRAFT: "slate" };
const picture = (p: Post) => (p.imageFileId ? fileUrl(p.imageFileId, "medium") : p.imageUrl ?? null);

function PostEditor({ id, imagesAvailable, onClose, onChanged }: { id: string; imagesAvailable: boolean; onClose: () => void; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const { data, error, reload } = useApi<Post>(`store/marketing/posts/${id}`);
  const [caption, setCaption] = useState<string | null>(null);
  const [when, setWhen] = useState("");
  const done = async () => { await reload(); onChanged(); };
  const patch = useAction(async (body: Record<string, unknown>) => { await api(`store/marketing/posts/${id}`, { method: "PATCH", body }); await done(); });
  const regen = useAction(async (what: "caption" | "image") => { if (caption !== null) await api(`store/marketing/posts/${id}`, { method: "PATCH", body: { caption } }); await api(`store/marketing/posts/${id}/regenerate`, { body: { what } }); setCaption(null); await done(); });
  const publish = useAction(async () => { if (caption !== null) await api(`store/marketing/posts/${id}`, { method: "PATCH", body: { caption } }); await api(`store/marketing/posts/${id}/publish`, { body: {} }); setCaption(null); await done(); });
  const schedule = useAction(async () => { if (caption !== null) await api(`store/marketing/posts/${id}`, { method: "PATCH", body: { caption } }); await api(`store/marketing/posts/${id}/schedule`, { body: { at: new Date(when).toISOString() } }); setCaption(null); await done(); });
  const unschedule = useAction(async () => { await api(`store/marketing/posts/${id}/unschedule`, { body: {} }); await done(); });
  const del = useAction(async () => { await api(`store/marketing/posts/${id}`, { method: "DELETE" }); onChanged(); onClose(); });
  const ov = useApi<Overview>("store/marketing/overview");
  if (!data) return <Modal open onClose={onClose} title={t("mk.post")} wide>{error ? <ErrorText error={error} /> : <Loading />}</Modal>;
  const locked = data.status === "POSTED" || data.status === "POSTING";
  const text = caption ?? data.caption;
  const pic = picture(data);
  const platforms = Array.from(new Set((ov.data?.channels ?? []).map((c) => c.platform)));
  const busy = patch.loading || regen.loading || publish.loading || schedule.loading;
  return (
    <Modal open onClose={onClose} title={data.kind === "SHOP" ? t("mk.shopPost") : data.productName ?? t("mk.post")} wide>
      <div className="grid gap-5 md:grid-cols-2">
        <div className="space-y-3">
          <div className="relative aspect-square overflow-hidden rounded-2xl bg-slate-100">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {pic ? <img src={pic} alt="" className="h-full w-full object-cover" /> : <div className="grid h-full place-items-center p-6 text-center text-sm text-slate-500">{t("mk.noImage")}</div>}
            {regen.loading && <div className="absolute inset-0 grid place-items-center bg-white/70"><Loading /></div>}
          </div>
          {!locked && (
            <div className="flex flex-wrap gap-2">
              {imagesAvailable && <Button size="sm" variant="secondary" disabled={busy} onClick={() => void regen.run("image")}>✨ {t("mk.newImage")}</Button>}
              <ImageUploader category="PRODUCT_IMAGE" label={t("mk.uploadImage")} onUploaded={(fid) => void patch.run({ imageFileId: fid })} />
              {pic && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void patch.run({ removeImage: true })}>{t("common.remove")}</Button>}
            </div>
          )}
        </div>
        <div className="space-y-3">
          <Field label={t("mk.caption")}><Textarea rows={9} value={text} disabled={locked} onChange={(e) => setCaption(e.target.value)} /></Field>
          {!locked && (
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" disabled={busy} onClick={() => void regen.run("caption")}>✨ {t("mk.rewrite")}</Button>
              {caption !== null && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void patch.run({ caption }).then(() => setCaption(null))}>{t("common.save")}</Button>}
            </div>
          )}
          <fieldset className="space-y-1.5" disabled={locked}>
            <legend className="mb-1 text-sm font-medium">{t("mk.postTo")}</legend>
            {(platforms.length ? platforms : ["FACEBOOK", "INSTAGRAM"]).map((p) => {
              const names = (ov.data?.channels ?? []).filter((c) => c.platform === p).map((c) => c.name).filter(Boolean).join(", ");
              return (
                <label key={p} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={data.channels.includes(p)} onChange={(e) => void patch.run({ channels: e.target.checked ? [...data.channels, p] : data.channels.filter((x) => x !== p) })} />{t(`ai.platform.${p}`)}{names && <span className="text-xs text-slate-500">· {names}</span>}</label>
              );
            })}
            {data.channels.includes("INSTAGRAM") && !pic && <p className="text-xs text-amber-700">{t("mk.igNeedsImage")}</p>}
          </fieldset>
          <div className="flex flex-wrap items-center gap-2"><Badge tone={TONE[data.status] ?? "slate"}>{t(`mk.status.${data.status}`)}</Badge>{data.scheduledAt && data.status === "SCHEDULED" && <span className="text-xs text-slate-500">{dateTime(data.scheduledAt, locale)}</span>}{data.postedAt && <span className="text-xs text-slate-500">{dateTime(data.postedAt, locale)}</span>}</div>
          {data.results.length > 0 && (
            <ul className="space-y-1.5">{data.results.filter((r) => r.channel !== "-" || !r.ok).map((r, i) => <li key={i}><Alert tone={r.ok ? "green" : "red"}>{t(`ai.platform.${r.channel}`) === `ai.platform.${r.channel}` ? r.channel : t(`ai.platform.${r.channel}`)}{r.name ? ` · ${r.name}` : ""}: {r.ok ? t("mk.posted") : r.error}</Alert></li>)}</ul>
          )}
          <ErrorText error={error ?? patch.error ?? regen.error ?? publish.error ?? schedule.error ?? unschedule.error ?? del.error} />
          {!locked && (
            <div className="space-y-3 border-t border-slate-100 pt-3">
              <div className="flex flex-wrap gap-2">
                <Button loading={publish.loading} disabled={busy || data.channels.length === 0} onClick={() => void publish.run()}>{data.status === "FAILED" || data.status === "PARTIAL" ? t("mk.retry") : t("mk.postNow")}</Button>
                {data.status === "SCHEDULED" && <Button variant="secondary" loading={unschedule.loading} onClick={() => void unschedule.run()}>{t("mk.unschedule")}</Button>}
                <Button variant="ghost" loading={del.loading} onClick={() => void del.run()}>{t("common.delete")}</Button>
              </div>
              <div className="flex flex-wrap items-end gap-2">
                <Field label={t("mk.scheduleFor")}><Input type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} /></Field>
                <Button variant="secondary" loading={schedule.loading} disabled={!when || busy || data.channels.length === 0} onClick={() => void schedule.run()}>{t("mk.schedule")}</Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </Modal>
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
    <Card className="space-y-4">
      <div className="flex items-center gap-3"><span className="grid h-11 w-11 place-items-center rounded-2xl bg-gradient-to-br from-violet-500 to-fuchsia-500 text-xl text-white">✨</span><div><h2 className="font-bold">{t("mk.compose")}</h2><p className="text-sm text-slate-500">{t("mk.compose.sub")}</p></div></div>
      <div className="inline-flex gap-1 rounded-xl bg-slate-100/80 p-1 text-sm font-semibold">
        {(["PRODUCT", "SHOP"] as const).map((k) => <button key={k} type="button" onClick={() => setKind(k)} className={`rounded-lg px-4 py-1.5 transition ${k === kind ? "bg-white text-brand-dark shadow-sm" : "text-slate-600"}`}>{t(`mk.kind.${k}`)}</button>)}
      </div>
      {kind === "PRODUCT" && (
        product ? (
          <div className="flex items-center justify-between rounded-xl border border-brand/40 bg-brand-soft/40 px-3 py-2 text-sm"><span className="font-semibold">{product.name}</span><button className="text-xs font-semibold text-brand" onClick={() => setProduct(null)}>{t("mk.change")}</button></div>
        ) : (
          <div>
            <Input placeholder={t("mk.pickProduct")} value={q} onChange={(e) => setQ(e.target.value)} />
            <ul className="mt-2 max-h-48 divide-y divide-slate-100 overflow-y-auto rounded-xl border border-slate-200">
              {found.data?.data.map((p) => <li key={p.id}><button type="button" className="w-full px-3 py-2 text-start text-sm hover:bg-slate-50" onClick={() => setProduct(p)}>{p.name}</button></li>)}
              {found.data && found.data.data.length === 0 && <li className="p-3 text-sm text-slate-500">{t("ch.link.none")}</li>}
            </ul>
          </div>
        )
      )}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={t("mk.tone")}><Select value={tone} onChange={(e) => setTone(e.target.value)}>{TONES.map((x) => <option key={x} value={x}>{t(`mk.tone.${x}`)}</option>)}</Select></Field>
        <Field label={t("mk.language")}><Select value={lang} onChange={(e) => setLang(e.target.value as "ar" | "en")}><option value="ar">العربية</option><option value="en">English</option></Select></Field>
      </div>
      <div className="flex flex-wrap gap-x-5 gap-y-2 text-sm">
        {platforms.map((p) => <label key={p} className="flex items-center gap-2"><input type="checkbox" checked={chans.includes(p)} onChange={(e) => setChans(e.target.checked ? [...chans, p] : chans.filter((x) => x !== p))} />{t(`ai.platform.${p}`)}</label>)}
        {ov.imagesAvailable ? <label className="flex items-center gap-2"><input type="checkbox" checked={withImage} onChange={(e) => setWithImage(e.target.checked)} />✨ {t("mk.withImage")}</label> : <span className="text-xs text-slate-500">{t("mk.noAiImages")}</span>}
      </div>
      <ErrorText error={go.error} />
      <Button loading={go.loading} disabled={!canWrite || (kind === "PRODUCT" && !product) || chans.length === 0} onClick={() => void go.run()}>✨ {t("mk.generate")}</Button>
    </Card>
  );
}

export function PostsView({ ov, canWrite }: { ov: Overview; canWrite: boolean }) {
  const { t, locale } = useI18n();
  const [page, setPage] = useState(1);
  const [open, setOpen] = useState<string | null>(null);
  const { data, loading, reload } = useApi<Page<Post>>(`store/marketing/posts?page=${page}`);
  return (
    <div className="space-y-5">
      {ov.channels.length === 0 ? null : <Composer ov={ov} canWrite={canWrite} onCreated={(id) => { setOpen(id); void reload(); }} />}
      <h2 className="font-bold">{t("mk.history")}</h2>
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? <Empty>{t("mk.noPosts")}</Empty> : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {data?.data.map((p) => (
              <button key={p.id} onClick={() => setOpen(p.id)} className="hover-lift overflow-hidden rounded-2xl bg-white text-start shadow-card">
                <div className="aspect-[16/9] bg-slate-100">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  {picture(p) ? <img src={picture(p)!} alt="" className="h-full w-full object-cover" /> : <div className="grid h-full place-items-center text-3xl text-slate-300">✎</div>}
                </div>
                <div className="space-y-2 p-4">
                  <div className="flex items-center justify-between gap-2"><Badge tone={TONE[p.status] ?? "slate"}>{t(`mk.status.${p.status}`)}</Badge><span className="text-xs text-slate-400">{dateTime(p.postedAt ?? p.scheduledAt ?? p.createdAt, locale)}</span></div>
                  <p className="line-clamp-3 text-sm text-slate-700">{p.caption || "—"}</p>
                  <p className="text-xs text-slate-500">{p.kind === "SHOP" ? t("mk.kind.SHOP") : p.productName} · {p.channels.map((c) => t(`ai.platform.${c}`)).join(" + ")}</p>
                </div>
              </button>
            ))}
          </div>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      {open && <PostEditor id={open} imagesAvailable={ov.imagesAvailable} onClose={() => setOpen(null)} onChanged={() => void reload()} />}
    </div>
  );
}
