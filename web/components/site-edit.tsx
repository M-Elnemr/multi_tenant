"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "./hooks";
import { useI18n } from "./i18n-provider";
import { Icon } from "./icons";
import { Button, Card, ErrorText, Input, Modal } from "./ui";
import BrandingCard from "./dashboard/branding";
import { BannersCard } from "./dashboard/banners";
import { BranchesWebsiteCard, DoctorsWebsiteCard, WebsiteCard } from "./dashboard/clinic-website";
import { BranchesCard, ContactCard, type StoreProfile } from "./dashboard/store-identity";

type Kind = "clinic" | "store";

function StoreContact() {
  const profile = useApi<StoreProfile>("store/profile");
  if (!profile.data) return null;
  return <ContactCard profile={profile.data} reload={profile.reload} />;
}

/** Up to 6 short promises shown under the banner (cash on delivery, returns...). Empty = the standard four. */
function TrustTilesCard() {
  const { t } = useI18n();
  const profile = useApi<{ trustTiles?: { title: string; text: string }[] }>("store/profile");
  const [tiles, setTiles] = useState<{ title: string; text: string }[] | null>(null);
  const cur = tiles ?? profile.data?.trustTiles ?? [];
  const save = useAction(async () => { await api("store/profile", { method: "PATCH", body: { trustTiles: cur } }); await profile.reload(); });
  if (!profile.data) return null;
  const set = (i: number, k: "title" | "text", v: string) => setTiles(cur.map((x, j) => (j === i ? { ...x, [k]: v } : x)));
  return (
    <Card className="space-y-3">
      <div><h2 className="font-semibold">{t("site.tiles")}</h2><p className="text-sm text-slate-500">{t("site.tilesHint")}</p></div>
      {cur.map((x, i) => (
        <div key={i} className="flex items-center gap-2">
          <Input value={x.title} maxLength={60} placeholder={t("site.tileTitle")} onChange={(e) => set(i, "title", e.target.value)} />
          <Input value={x.text} maxLength={160} placeholder={t("site.tileText")} onChange={(e) => set(i, "text", e.target.value)} />
          <Button size="sm" variant="ghost" onClick={() => setTiles(cur.filter((_, j) => j !== i))}>{t("common.delete")}</Button>
        </div>
      ))}
      <div className="flex gap-2">
        {cur.length < 6 && <Button variant="secondary" onClick={() => setTiles([...cur, { title: "", text: "" }])}>+ {t("site.addTile")}</Button>}
        <Button loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button>
      </div>
      <ErrorText error={save.error} />
    </Card>
  );
}

function DashLink({ href, label }: { href: string; label: string }) {
  return <Link href={href} className="block rounded-2xl border border-slate-200 p-4 text-center font-semibold text-brand hover:bg-brand-soft">{label} →</Link>;
}

function Editor({ kind, section }: { kind: Kind; section: string }) {
  const { t } = useI18n();
  if (kind === "clinic") {
    switch (section) {
      case "doctors": return <DoctorsWebsiteCard />;
      case "services": return <DashLink href="/dashboard/services" label={t("site.manageServices")} />;
      case "visit": return <><BranchesWebsiteCard /><WebsiteCard /></>;
      case "how": return <p className="text-sm text-slate-500">{t("site.fixedSection")}</p>;
      default: return <WebsiteCard />;
    }
  }
  switch (section) {
    case "banners": return <BannersCard />;
    case "trust": return <TrustTilesCard />;
    case "branches": return <BranchesCard />;
    case "whatsapp": return <StoreContact />;
    case "categories": return <DashLink href="/dashboard/products" label={t("site.manageProducts")} />;
    default: return <DashLink href="/dashboard/products" label={t("site.manageProducts")} />;
  }
}

/** The owner's view of one section of the public site: a dashed frame with Edit, Hide/Show and Move buttons. Every change shows at once. */
export function EditFrame({ kind, section, hidden, order, hiddenKeys, children }: { kind: Kind; section: string; hidden: boolean; order: string[]; hiddenKeys: string[]; children: ReactNode }) {
  const { t } = useI18n();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const i = order.indexOf(section);
  const save = useAction(async (o: string[], h: string[]) => { await api(`${kind}/profile`, { method: "PATCH", body: { siteLayout: { order: o, hidden: h } } }); router.refresh(); });
  const move = (d: -1 | 1) => { const o = [...order]; const j = i + d; if (j < 0 || j >= o.length) return; [o[i], o[j]] = [o[j], o[i]]; void save.run(o, hiddenKeys); };
  const toggle = () => void save.run(order, hidden ? hiddenKeys.filter((k) => k !== section) : [...hiddenKeys, section]);
  const btn = "flex h-9 items-center gap-1.5 rounded-full bg-white/95 px-3 text-xs font-bold text-slate-800 shadow-md transition hover:bg-brand hover:text-white active:scale-95 disabled:opacity-40";
  return (
    <div className={`relative my-1 rounded-3xl outline-2 outline-dashed outline-offset-[-4px] outline-brand/50 transition ${hidden ? "opacity-40" : ""}`}>
      <div className="absolute end-3 top-3 z-30 flex flex-wrap items-center gap-1.5">
        {hidden && <span className="rounded-full bg-slate-900/80 px-3 py-1 text-xs font-bold text-white">{t("site.hiddenBadge")}</span>}
        <button className={btn} onClick={() => setOpen(true)}><Icon name="edit" className="h-4 w-4" />{t("site.edit")}</button>
        <button className={btn} onClick={toggle} disabled={save.loading}>{hidden ? t("common.show") : t("common.hide")}</button>
        <button className={btn} onClick={() => move(-1)} disabled={i <= 0 || save.loading} aria-label={t("site.moveUp")}>↑</button>
        <button className={btn} onClick={() => move(1)} disabled={i < 0 || i >= order.length - 1 || save.loading} aria-label={t("site.moveDown")}>↓</button>
      </div>
      {children}
      <Modal open={open} onClose={() => { setOpen(false); router.refresh(); }} title={t(`site.section.${section}`)} wide>
        <div className="space-y-4"><Editor kind={kind} section={section} /></div>
      </Modal>
    </div>
  );
}

/** Floating bar while editing: exit, branding (colours and logo) and a link to the full settings. */
export function EditBar({ kind }: { kind: Kind }) {
  const { t } = useI18n();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  return (
    <>
      <div className="fixed inset-x-3 bottom-3 z-40 mx-auto flex max-w-xl items-center justify-between gap-2 rounded-full bg-slate-900 px-4 py-2.5 text-white shadow-2xl">
        <span className="flex items-center gap-2 text-sm font-bold"><span className="h-2 w-2 animate-pulse rounded-full bg-emerald-400" />{t("site.editing")}</span>
        <span className="flex items-center gap-1.5">
          <button onClick={() => setOpen(true)} className="rounded-full bg-white/15 px-3 py-1.5 text-xs font-bold hover:bg-white/25">{t("site.branding")}</button>
          <Link href="/dashboard/settings" className="rounded-full bg-white/15 px-3 py-1.5 text-xs font-bold hover:bg-white/25">{t("site.allSettings")}</Link>
          <Link href="/" className="rounded-full bg-white px-3 py-1.5 text-xs font-bold text-slate-900">{t("site.exit")}</Link>
        </span>
      </div>
      <Modal open={open} onClose={() => { setOpen(false); router.refresh(); }} title={t("site.branding")} wide><BrandingCard /></Modal>
      <span className="hidden">{kind}</span>
    </>
  );
}
