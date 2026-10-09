"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Button, ErrorText, Field, Input, Modal, Textarea } from "../ui";

export type TaxonomyNode = { id: number; slug: string; name: string; breadcrumb: string; level: number; hasChildren: boolean; appliesAudience: boolean; sizeScales: string[]; icon?: string | null };

/**
 * Pick one of the platform's standard categories: search by any word, browse level by level, or start from the ones suggested for this shop.
 * Shops cannot invent categories; a missing one can be suggested to the platform team.
 */
export function TaxonomyPicker({ value, onChange }: { value: TaxonomyNode | null; onChange: (n: TaxonomyNode) => void }) {
  const { t, locale } = useI18n();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [results, setResults] = useState<TaxonomyNode[] | null>(null);
  const [path, setPath] = useState<TaxonomyNode[]>([]);
  const [suggest, setSuggest] = useState(false);
  const parent = path.length ? path[path.length - 1].id : null;
  const children = useApi<TaxonomyNode[]>(open ? `store/taxonomy/children?lang=${locale}${parent ? `&parent=${parent}` : ""}` : null);
  const suggested = useApi<TaxonomyNode[]>(open ? `store/taxonomy/suggested?lang=${locale}` : null);

  useEffect(() => {
    if (!open || q.trim().length < 2) { const id = setTimeout(() => setResults(null), 0); return () => clearTimeout(id); }
    let alive = true;
    const id = setTimeout(() => { api<TaxonomyNode[]>(`store/taxonomy/search?lang=${locale}&q=${encodeURIComponent(q.trim())}`).then((r) => alive && setResults(r)).catch(() => alive && setResults([])); }, 220);
    return () => { alive = false; clearTimeout(id); };
  }, [q, open, locale]);

  const choose = (n: TaxonomyNode) => { onChange(n); setOpen(false); setQ(""); setPath([]); };
  const row = (n: TaxonomyNode, showCrumb: boolean) => (
    <li key={n.id}>
      <div className="flex items-center gap-2 rounded-xl px-3 py-2.5 transition hover:bg-brand-soft">
        <button type="button" className="min-w-0 flex-1 text-start" onClick={() => (n.hasChildren && !showCrumb ? setPath([...path, n]) : choose(n))}>
          <span className="font-medium">{n.icon ? `${n.icon} ` : ""}{n.name}</span>
          {showCrumb && <span className="block truncate text-xs text-slate-500">{n.breadcrumb}</span>}
        </button>
        {n.hasChildren && !showCrumb && n.level >= 2 && <Button type="button" size="sm" variant="secondary" onClick={() => choose(n)}>{t("taxonomy.pickThis")}</Button>}
        {n.hasChildren && !showCrumb && <span className="text-slate-400" aria-hidden>{locale === "ar" ? "‹" : "›"}</span>}
      </div>
    </li>
  );

  return (
    <>
      <button type="button" onClick={() => setOpen(true)} className="flex w-full items-center justify-between gap-3 rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-start text-sm transition hover:border-brand">
        <span className={value ? "font-medium" : "text-slate-400"}>{value ? value.breadcrumb : t("taxonomy.choose")}</span><span className="text-brand">{t("taxonomy.change")}</span>
      </button>
      <Modal open={open} onClose={() => setOpen(false)} title={suggest ? t("taxonomy.suggestTitle") : t("taxonomy.title")} wide>
        {suggest ? <SuggestForm onDone={() => setSuggest(false)} /> : (
          <div className="space-y-4">
            <Input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("taxonomy.searchHint")} />
            {results ? (
              <ul className="max-h-80 overflow-y-auto">{results.map((n) => row(n, true))}{results.length === 0 && <li className="p-4 text-center text-sm text-slate-500">{t("taxonomy.noMatch")}</li>}</ul>
            ) : (
              <>
                {path.length === 0 && (suggested.data?.length ?? 0) > 0 && (
                  <div><p className="mb-2 text-xs font-semibold text-slate-500">{t("taxonomy.forYourShop")}</p><div className="flex flex-wrap gap-2">{suggested.data!.slice(0, 18).map((n) => <button key={n.id} type="button" onClick={() => choose(n)} className="rounded-full border border-slate-300 bg-white px-3 py-1.5 text-sm transition hover:border-brand hover:bg-brand-soft">{n.name}</button>)}</div></div>
                )}
                <div>
                  <div className="mb-2 flex flex-wrap items-center gap-1 text-sm">
                    <button type="button" onClick={() => setPath([])} className="font-semibold text-brand">{t("taxonomy.all")}</button>
                    {path.map((n, i) => <span key={n.id} className="flex items-center gap-1"><span className="text-slate-400">/</span><button type="button" onClick={() => setPath(path.slice(0, i + 1))} className="font-semibold text-brand">{n.name}</button></span>)}
                  </div>
                  <ul className="max-h-72 overflow-y-auto rounded-xl border">{(children.data ?? []).map((n) => row(n, false))}</ul>
                </div>
              </>
            )}
            <p className="text-sm text-slate-500">{t("taxonomy.missing")} <button type="button" onClick={() => setSuggest(true)} className="font-semibold text-brand underline">{t("taxonomy.suggest")}</button></p>
          </div>
        )}
      </Modal>
    </>
  );
}

function SuggestForm({ onDone }: { onDone: () => void }) {
  const { t } = useI18n();
  const [name, setName] = useState("");
  const [note, setNote] = useState("");
  const [sent, setSent] = useState(false);
  const send = useAction(async () => { await api("store/taxonomy/requests", { body: { name, note } }); setSent(true); });
  if (sent) return <div className="space-y-4"><Alert tone="green">{t("taxonomy.suggestSent")}</Alert><Button type="button" onClick={onDone}>{t("taxonomy.back")}</Button></div>;
  return (
    <form onSubmit={(e) => { e.preventDefault(); void send.run(); }} className="space-y-4">
      <p className="text-sm text-slate-600">{t("taxonomy.suggestHint")}</p>
      <Field label={t("taxonomy.suggestName")}><Input value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} /></Field>
      <Field label={t("taxonomy.suggestNote")}><Textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></Field>
      <ErrorText error={send.error} />
      <div className="flex gap-2"><Button type="submit" loading={send.loading}>{t("taxonomy.suggestSend")}</Button><Button type="button" variant="ghost" onClick={onDone}>{t("taxonomy.back")}</Button></div>
    </form>
  );
}
