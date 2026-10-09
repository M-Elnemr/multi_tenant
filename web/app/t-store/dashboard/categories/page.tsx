"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { ImageUploader } from "@/components/uploader";
import { Badge, Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, Select } from "@/components/ui";
import { buildTree, type CategoryNode, type ShopCategory } from "@/components/shop/types";

type Cat = ShopCategory & { isActive: boolean };

function flatten(nodes: CategoryNode[], depth = 0, out: { node: CategoryNode; depth: number }[] = []) {
  for (const n of nodes) { out.push({ node: n, depth }); flatten(n.children, depth + 1, out); }
  return out;
}
const descendants = (n: CategoryNode): string[] => n.children.flatMap((c) => [c.id, ...descendants(c)]);

/** The shop's category tree: add under any category, rename, move, reorder, hide, set a picture, delete when empty. */
export default function Categories() {
  const t = useT();
  const { data, loading, reload, error } = useApi<Cat[]>("store/categories");
  const [newName, setNewName] = useState("");
  const [newParent, setNewParent] = useState("");
  const [edit, setEdit] = useState<(Cat & { parent: string }) | null>(null);
  const tree = buildTree(data ?? []);
  const rows = flatten(tree);
  const byId = new Map((data ?? []).map((c) => [c.id, c]));
  const add = useAction(async () => { await api("store/categories", { body: { name: newName, parentId: newParent || undefined } }); setNewName(""); await reload(); });
  const save = useAction(async () => {
    if (!edit) return;
    await api(`store/categories/${edit.id}`, { method: "PATCH", body: { name: edit.name, moveToParent: true, parentId: edit.parent || null, imageFileId: edit.imageFileId || undefined } });
    setEdit(null); await reload();
  });
  const toggle = useAction(async (c: Cat) => { await api(`store/categories/${c.id}`, { method: "PATCH", body: { active: !c.isActive } }); await reload(); });
  const remove = useAction(async (c: Cat) => { await api(`store/categories/${c.id}`, { method: "DELETE" }); await reload(); });
  const swap = useAction(async (a: CategoryNode, b: CategoryNode) => {
    // give both a distinct, ordered sort value, then swap them
    const sa = a.sortOrder === b.sortOrder ? a.sortOrder - 1 : a.sortOrder, sb = b.sortOrder;
    await api(`store/categories/${a.id}`, { method: "PATCH", body: { sortOrder: sb } });
    await api(`store/categories/${b.id}`, { method: "PATCH", body: { sortOrder: sa === sb ? sb + 1 : sa } });
    await reload();
  });
  const siblings = (n: CategoryNode) => (n.parentId ? byId.get(n.parentId) && flatten(tree).map((r) => r.node).filter((x) => x.parentId === n.parentId) : tree) ?? [];
  const blocked = edit ? new Set([edit.id, ...descendants(rows.find((r) => r.node.id === edit.id)!.node)]) : new Set<string>();
  const parentOptions = rows.filter((r) => !blocked.has(r.node.id));
  return (
    <>
      <PageHeader title={t("nav.categories")} subtitle={t("categories.hint")} />
      <Card className="mb-6">
        <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_auto]">
          <Field label={t("products.name")}><Input value={newName} onChange={(e) => setNewName(e.target.value)} required /></Field>
          <Field label={t("categories.parent")}><Select value={newParent} onChange={(e) => setNewParent(e.target.value)}><option value="">{t("categories.topLevel")}</option>{rows.map((r) => <option key={r.node.id} value={r.node.id}>{"— ".repeat(r.depth)}{r.node.name}</option>)}</Select></Field>
          <Button type="submit" loading={add.loading}>{t("common.add")}</Button>
        </form>
        <ErrorText error={add.error} />
      </Card>
      <ErrorText error={error ?? toggle.error ?? remove.error ?? swap.error} />
      {loading && !data ? <Loading /> : (
        <Card className="divide-y !p-0">
          {rows.length === 0 && <p className="p-8 text-center text-slate-500">{t("categories.empty")}</p>}
          {rows.map(({ node, depth }) => {
            const sib = siblings(node), i = sib.indexOf(node);
            return (
              <div key={node.id} className="flex flex-wrap items-center gap-3 px-4 py-3" style={{ paddingInlineStart: 16 + depth * 28 }}>
                {node.imageFileId ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={`/api/bff/files/${node.imageFileId}/content?variant=thumb`} alt="" className="h-10 w-10 rounded-lg object-cover" />
                ) : <span className="grid h-10 w-10 place-items-center rounded-lg bg-slate-100 text-slate-400">{depth > 0 ? "└" : "▦"}</span>}
                <div className="min-w-0 flex-1"><p className="font-semibold">{node.name} {!(byId.get(node.id)?.isActive ?? true) && <Badge tone="slate">{t("common.hidden")}</Badge>}</p><p className="font-mono text-xs text-slate-400">{node.slug} · {t("shop.itemsCount", { n: node.productCount })}</p></div>
                <div className="flex flex-wrap gap-1">
                  <Button size="sm" variant="ghost" disabled={i <= 0} onClick={() => swap.run(node, sib[i - 1])} aria-label="up">↑</Button>
                  <Button size="sm" variant="ghost" disabled={i < 0 || i >= sib.length - 1} onClick={() => swap.run(node, sib[i + 1])} aria-label="down">↓</Button>
                  <Button size="sm" variant="secondary" onClick={() => setEdit({ ...(byId.get(node.id) as Cat), parent: node.parentId ?? "" })}>{t("common.edit")}</Button>
                  <Button size="sm" variant="ghost" onClick={() => toggle.run(byId.get(node.id) as Cat)}>{byId.get(node.id)?.isActive ? t("common.hide") : t("common.show")}</Button>
                  <Button size="sm" variant="ghost" onClick={() => remove.run(byId.get(node.id) as Cat)}>{t("common.delete")}</Button>
                </div>
              </div>
            );
          })}
        </Card>
      )}
      <Modal open={!!edit} onClose={() => setEdit(null)} title={t("categories.edit")}>
        {edit && (
          <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
            <Field label={t("products.name")}><Input value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} required /></Field>
            <Field label={t("categories.parent")}><Select value={edit.parent} onChange={(e) => setEdit({ ...edit, parent: e.target.value })}><option value="">{t("categories.topLevel")}</option>{parentOptions.map((r) => <option key={r.node.id} value={r.node.id}>{"— ".repeat(r.depth)}{r.node.name}</option>)}</Select></Field>
            <div className="flex items-center gap-3">
              {edit.imageFileId && (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={`/api/bff/files/${edit.imageFileId}/content?variant=thumb`} alt="" className="h-14 w-14 rounded-xl object-cover" />
              )}
              <ImageUploader category="PRODUCT_IMAGE" label={t("categories.image")} onUploaded={(id) => setEdit({ ...edit, imageFileId: id })} />
            </div>
            <ErrorText error={save.error} />
            <Button type="submit" loading={save.loading}>{t("common.save")}</Button>
          </form>
        )}
      </Modal>
    </>
  );
}
