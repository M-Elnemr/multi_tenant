"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateOnly } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Loading, Tabs, Textarea } from "../ui";

type Post = { id: string; slug: string; title: string; excerpt: string; published: boolean; updatedAt: string };
type Full = Post & { body: string };

function Blog() {
  const { t, locale } = useI18n();
  const list = useApi<Post[]>("store/blog");
  const [editing, setEditing] = useState<{ id?: string; title: string; slug: string; excerpt: string; body: string; published: boolean } | null>(null);
  const load = useAction(async (id: string) => { const p = await api<Full>(`store/blog/${id}`); setEditing({ id: p.id, title: p.title, slug: p.slug, excerpt: p.excerpt, body: p.body, published: p.published }); });
  const save = useAction(async () => { if (!editing) return; const body = { title: editing.title, slug: editing.slug || undefined, excerpt: editing.excerpt, body: editing.body, published: editing.published }; if (editing.id) await api(`store/blog/${editing.id}`, { method: "PUT", body }); else await api("store/blog", { body }); setEditing(null); await list.reload(); });
  const del = useAction(async (id: string) => { await api(`store/blog/${id}`, { method: "DELETE" }); await list.reload(); });
  if (editing) {
    return (
      <Card className="space-y-3">
        <Field label={t("blog.postTitle")}><Input value={editing.title} onChange={(e) => setEditing({ ...editing, title: e.target.value })} /></Field>
        <Field label={t("blog.slug")} hint={t("blog.slugHint")}><Input dir="ltr" value={editing.slug} onChange={(e) => setEditing({ ...editing, slug: e.target.value })} /></Field>
        <Field label={t("blog.excerpt")}><Input value={editing.excerpt} onChange={(e) => setEditing({ ...editing, excerpt: e.target.value })} maxLength={400} /></Field>
        <Field label={t("blog.body")} hint={t("blog.bodyHint")}><Textarea rows={12} value={editing.body} onChange={(e) => setEditing({ ...editing, body: e.target.value })} /></Field>
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={editing.published} onChange={(e) => setEditing({ ...editing, published: e.target.checked })} />{t("blog.publish")}</label>
        <ErrorText error={save.error} />
        <div className="flex gap-2"><Button loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button><Button variant="ghost" onClick={() => setEditing(null)}>{t("pl.cancel")}</Button></div>
      </Card>
    );
  }
  return (
    <div className="space-y-4">
      <Button onClick={() => setEditing({ title: "", slug: "", excerpt: "", body: "", published: false })}>{t("blog.new")}</Button>
      <ErrorText error={load.error ?? del.error} />
      {(list.data ?? []).map((p) => (
        <Card key={p.id} className="flex flex-wrap items-center justify-between gap-2">
          <span><b>{p.title}</b> <Badge tone={p.published ? "green" : "blue"}>{p.published ? t("blog.published") : t("blog.draft")}</Badge><span className="block text-xs text-slate-500" dir="ltr">/blog/{p.slug} · {dateOnly(p.updatedAt, locale)}</span></span>
          <span className="flex gap-2"><Button size="sm" variant="secondary" onClick={() => void load.run(p.id)}>{t("pl.edit")}</Button><Button size="sm" variant="ghost" onClick={() => void del.run(p.id)}>{t("common.delete")}</Button></span>
        </Card>
      ))}
    </div>
  );
}

type Tr = { id: string; name: string; description: string; nameEn?: string | null; descriptionEn?: string | null };

function Translations() {
  const { t } = useI18n();
  const list = useApi<Tr[]>("store/translations");
  const [edit, setEdit] = useState<Record<string, { name: string; description: string }>>({});
  const [msg, setMsg] = useState<string | null>(null);
  const save = useAction(async (id: string) => { const e = edit[id]; await api(`store/translations/${id}`, { method: "PUT", body: e }); await list.reload(); });
  const draft = useAction(async () => { const r = await api<{ drafted: number }>("store/translations/draft", { body: {} }); setMsg(t("blog.drafted", { n: r.drafted })); await list.reload(); });
  if (!list.data) return <Loading />;
  return (
    <div className="space-y-4">
      <Alert tone="blue">{t("blog.trHint")}</Alert>
      <div className="flex items-center gap-3"><Button variant="secondary" loading={draft.loading} onClick={() => { setMsg(null); void draft.run(); }}>{t("blog.draftAi")}</Button>{msg && <span className="text-sm text-emerald-700">{msg}</span>}</div>
      <ErrorText error={save.error ?? draft.error} />
      {list.data.map((p) => {
        const e = edit[p.id] ?? { name: p.nameEn ?? "", description: p.descriptionEn ?? "" };
        return (
          <Card key={p.id} className="space-y-2">
            <div className="flex items-center justify-between"><b>{p.name}</b>{p.nameEn ? <Badge tone="green">EN</Badge> : <Badge tone="amber">{t("blog.noEnglish")}</Badge>}</div>
            <Input dir="ltr" placeholder={t("blog.englishName")} value={e.name} onChange={(x) => setEdit({ ...edit, [p.id]: { ...e, name: x.target.value } })} />
            <Textarea dir="ltr" rows={2} placeholder={t("blog.englishDesc")} value={e.description} onChange={(x) => setEdit({ ...edit, [p.id]: { ...e, description: x.target.value } })} />
            <Button size="sm" loading={save.loading} disabled={e.name.trim().length < 2} onClick={() => void save.run(p.id)}>{t("common.save")}</Button>
          </Card>
        );
      })}
    </div>
  );
}

export default function Content() {
  const { t } = useI18n();
  const [tab, setTab] = useState<"blog" | "tr">("blog");
  return (
    <div className="space-y-5">
      <Tabs tabs={[{ key: "blog", label: t("blog.tab") }, { key: "tr", label: t("blog.trTab") }]} value={tab} onChange={setTab} />
      {tab === "blog" ? <Blog /> : <Translations />}
    </div>
  );
}
