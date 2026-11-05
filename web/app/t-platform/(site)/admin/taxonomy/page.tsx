"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, Select } from "@/components/ui";
import { dateOnly } from "@/lib/format";

type Node = { id: number; parentId: number | null; slug: string; nameAr: string; nameEn: string; level: number; icon?: string; isActive: boolean; isHidden: boolean; ownProducts: number };
type Req = { id: string; shopName: string; name: string; parentId: number | null; note: string; status: string; adminNote: string; createdAt: string };

/** The platform's standard category list: rename, hide, add; and the owners' suggestions waiting for a decision. */
export default function Taxonomy() {
  const { t, locale } = useI18n();
  const list = useApi<Node[]>("platform/taxonomy");
  const reqs = useApi<Req[]>("platform/taxonomy/requests?status=PENDING");
  const [edit, setEdit] = useState<{ id?: number; parentId: number | null; nameAr: string; nameEn: string } | null>(null);
  const [decide, setDecide] = useState<{ req: Req; parentId: number | null; nameAr: string; nameEn: string; note: string } | null>(null);
  const [filter, setFilter] = useState("");
  const nodes = list.data ?? [];
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const parents = nodes.filter((n) => n.level <= 2 && !n.isHidden);
  const shown = nodes.filter((n) => !filter.trim() || n.nameAr.includes(filter.trim()) || n.nameEn.toLowerCase().includes(filter.trim().toLowerCase()) || n.slug.includes(filter.trim().toLowerCase()));
  const save = useAction(async () => {
    if (!edit) return;
    if (edit.id) await api(`platform/taxonomy/${edit.id}`, { method: "PATCH", body: { nameAr: edit.nameAr, nameEn: edit.nameEn } });
    else await api("platform/taxonomy", { body: { parentId: edit.parentId, nameAr: edit.nameAr, nameEn: edit.nameEn } });
    setEdit(null); await list.reload();
  });
  const toggle = useAction(async (n: Node, patch: Record<string, boolean>) => { await api(`platform/taxonomy/${n.id}`, { method: "PATCH", body: patch }); await list.reload(); });
  const decideAct = useAction(async (approve: boolean) => {
    if (!decide) return;
    await api(`platform/taxonomy/requests/${decide.req.id}/decision`, { body: { approve, parentId: decide.parentId, nameAr: decide.nameAr, nameEn: decide.nameEn, note: decide.note } });
    setDecide(null); await Promise.all([reqs.reload(), list.reload()]);
  });
  if (list.loading && !list.data) return <Loading />;
  return (
    <>
      <PageHeader title={t("admin.taxonomy")} subtitle={t("admin.taxonomyHint", { n: nodes.length })} actions={<Button onClick={() => setEdit({ parentId: null, nameAr: "", nameEn: "" })}>{t("common.add")}</Button>} />
      <ErrorText error={list.error ?? toggle.error} />
      {(reqs.data?.length ?? 0) > 0 && (
        <Card className="mb-6 space-y-3 border-amber-300 bg-amber-50/50">
          <h2 className="font-semibold">📬 {t("admin.requests")} ({reqs.data!.length})</h2>
          <ul className="divide-y text-sm">{reqs.data!.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
              <div><p className="font-semibold">{r.name}</p><p className="text-xs text-slate-500">{r.shopName} · {dateOnly(r.createdAt, locale)}{r.note && ` · ${r.note}`}</p></div>
              <Button size="sm" onClick={() => setDecide({ req: r, parentId: r.parentId, nameAr: r.name, nameEn: "", note: "" })}>{t("admin.review")}</Button>
            </li>
          ))}</ul>
        </Card>
      )}
      <Input className="mb-4 max-w-sm" placeholder={t("common.search")} value={filter} onChange={(e) => setFilter(e.target.value)} />
      <Card className="divide-y !p-0">
        {shown.map((n) => (
          <div key={n.id} className="flex flex-wrap items-center gap-3 px-4 py-2.5 text-sm" style={{ paddingInlineStart: 16 + (n.level - 1) * 24 }}>
            <span className="w-6 text-center">{n.icon ?? (n.level > 1 ? "└" : "")}</span>
            <div className="min-w-0 flex-1"><p className="font-semibold">{n.nameAr} <span className="font-normal text-slate-500">· {n.nameEn}</span> {n.isHidden && <Badge tone="slate">{t("common.hidden")}</Badge>} {!n.isActive && <Badge tone="red">{t("status.INACTIVE")}</Badge>}</p><p className="font-mono text-xs text-slate-400">{n.slug} · {n.ownProducts}</p></div>
            <div className="flex gap-1">
              {n.level < 3 && <Button size="sm" variant="ghost" onClick={() => setEdit({ parentId: n.id, nameAr: "", nameEn: "" })}>+ {t("admin.subcategory")}</Button>}
              <Button size="sm" variant="secondary" onClick={() => setEdit({ id: n.id, parentId: n.parentId, nameAr: n.nameAr, nameEn: n.nameEn })}>{t("common.edit")}</Button>
              <Button size="sm" variant="ghost" onClick={() => toggle.run(n, { isHidden: !n.isHidden })}>{n.isHidden ? t("common.show") : t("common.hide")}</Button>
            </div>
          </div>
        ))}
      </Card>
      <Modal open={!!edit} onClose={() => setEdit(null)} title={edit?.id ? t("categories.edit") : t("admin.newCategory")}>
        {edit && (
          <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
            {!edit.id && <p className="text-sm text-slate-500">{edit.parentId ? `${t("categories.parent")}: ${byId.get(edit.parentId)?.nameAr}` : t("categories.topLevel")}</p>}
            <Field label={t("admin.nameAr")}><Input value={edit.nameAr} onChange={(e) => setEdit({ ...edit, nameAr: e.target.value })} required /></Field>
            <Field label={t("admin.nameEn")}><Input dir="ltr" value={edit.nameEn} onChange={(e) => setEdit({ ...edit, nameEn: e.target.value })} required /></Field>
            <ErrorText error={save.error} />
            <Button type="submit" loading={save.loading}>{t("common.save")}</Button>
          </form>
        )}
      </Modal>
      <Modal open={!!decide} onClose={() => setDecide(null)} title={t("admin.review")} wide>
        {decide && (
          <div className="space-y-4">
            <p className="text-sm"><b>{decide.req.name}</b> — {decide.req.shopName}</p>
            <Field label={t("categories.parent")}><Select value={decide.parentId ?? ""} onChange={(e) => setDecide({ ...decide, parentId: e.target.value ? Number(e.target.value) : null })}><option value="">{t("categories.topLevel")}</option>{parents.map((p) => <option key={p.id} value={p.id}>{"— ".repeat(p.level - 1)}{p.nameAr}</option>)}</Select></Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={t("admin.nameAr")}><Input value={decide.nameAr} onChange={(e) => setDecide({ ...decide, nameAr: e.target.value })} /></Field>
              <Field label={t("admin.nameEn")}><Input dir="ltr" value={decide.nameEn} onChange={(e) => setDecide({ ...decide, nameEn: e.target.value })} /></Field>
            </div>
            <Field label={t("admin.noteToShop")}><Input value={decide.note} onChange={(e) => setDecide({ ...decide, note: e.target.value })} /></Field>
            <ErrorText error={decideAct.error} />
            <div className="flex gap-2"><Button loading={decideAct.loading} onClick={() => decideAct.run(true)} disabled={!decide.nameAr.trim() || !decide.nameEn.trim()}>{t("returns.approve")}</Button><Button variant="secondary" loading={decideAct.loading} onClick={() => decideAct.run(false)}>{t("returns.reject")}</Button></div>
          </div>
        )}
      </Modal>
    </>
  );
}
