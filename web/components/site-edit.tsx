"use client";

import { useRouter } from "next/navigation";
import Link from "next/link";
import { createContext, useContext, useState, type ReactNode } from "react";
import { api } from "@/lib/client";
import { useAction, useMe } from "./hooks";
import { useI18n } from "./i18n-provider";
import { Icon } from "./icons";
import { SECTION_ICON, SectionEditor, SiteWideEditor, type Kind, type SiteWide } from "./site/editors";

const EditContext = createContext(false);

/** Tells the editing controls whether this visitor may edit the site (settings.manage). Checked through the BFF, so an expired token is renewed first. */
export function EditProvider({ children }: { children: ReactNode }) {
  const { can } = useMe();
  return <EditContext.Provider value={can("settings.manage")}>{children}</EditContext.Provider>;
}

/** The owner's view of one section: an outline with the section's name and Edit / Hide / Move buttons. Edit opens that section's own editor. */
export function EditFrame({ kind, section, hidden, order, hiddenKeys, children }: { kind: Kind; section: string; hidden: boolean; order: string[]; hiddenKeys: string[]; children: ReactNode }) {
  const { t } = useI18n();
  const router = useRouter();
  const allowed = useContext(EditContext);
  const [open, setOpen] = useState(false);
  const i = order.indexOf(section);
  const save = useAction(async (o: string[], h: string[]) => { await api(`${kind}/profile`, { method: "PATCH", body: { siteLayout: { order: o, hidden: h } } }); router.refresh(); });
  const move = (d: -1 | 1) => { const o = [...order]; const j = i + d; if (j < 0 || j >= o.length) return; [o[i], o[j]] = [o[j], o[i]]; void save.run(o, hiddenKeys); };
  const toggle = () => void save.run(order, hidden ? hiddenKeys.filter((k) => k !== section) : [...hiddenKeys, section]);
  if (!allowed) return hidden ? null : <div className="contents">{children}</div>;   // not an editor: the page looks like the public one
  const btn = "flex h-9 items-center gap-1.5 rounded-full bg-white px-3 text-xs font-bold text-slate-800 shadow-md ring-1 ring-black/5 transition hover:bg-brand hover:text-white active:scale-95 disabled:opacity-40";
  return (
    <div className={`group/s relative my-1 rounded-3xl outline-2 outline-dashed outline-offset-[-4px] outline-brand/30 transition hover:outline-brand ${hidden ? "opacity-45" : ""}`}>
      <div className="pointer-events-none absolute start-3 top-3 z-30 flex items-center gap-1.5 rounded-full bg-slate-900/85 px-3 py-1.5 text-xs font-bold text-white opacity-90 backdrop-blur">
        <Icon name={SECTION_ICON[section] ?? "sparkles"} className="h-3.5 w-3.5" />{t(`site.section.${section}`)}{hidden && <span className="rounded-full bg-amber-400 px-2 text-[10px] text-slate-900">{t("site.hiddenBadge")}</span>}
      </div>
      <div className="absolute end-3 top-3 z-30 flex flex-wrap items-center gap-1.5">
        <button className={`${btn} !bg-brand !text-white hover:!brightness-110`} onClick={() => setOpen(true)}><Icon name="edit" className="h-4 w-4" />{t("site.edit")}</button>
        <button className={btn} onClick={toggle} disabled={save.loading}>{hidden ? t("common.show") : t("common.hide")}</button>
        <button className={btn} onClick={() => move(-1)} disabled={i <= 0 || save.loading} aria-label={t("site.moveUp")}>↑</button>
        <button className={btn} onClick={() => move(1)} disabled={i < 0 || i >= order.length - 1 || save.loading} aria-label={t("site.moveDown")}>↓</button>
      </div>
      {children}
      {open && <SectionEditor kind={kind} section={section} onClose={() => { setOpen(false); router.refresh(); }} />}
    </div>
  );
}

/** The dock at the bottom while editing: the list of sections, site-wide settings (colours, announcement, footer), all settings, and Done. */
export function EditBar({ kind, order = [], hiddenKeys = [] }: { kind: Kind; order?: string[]; hiddenKeys?: string[] }) {
  const { t } = useI18n();
  const allowed = useContext(EditContext);
  const [panel, setPanel] = useState<SiteWide | null>(null);
  const [menu, setMenu] = useState(false);
  const item = "flex w-full items-center gap-2.5 rounded-xl px-3 py-2.5 text-start text-sm font-semibold hover:bg-slate-50";
  const open = (p: SiteWide) => { setMenu(false); setPanel(p); };
  if (!allowed) return null;
  return (
    <>
      <div className="edit-bar fixed inset-x-3 bottom-[calc(var(--safe-b)+0.75rem)] z-40 mx-auto flex max-w-2xl items-center justify-between gap-2 rounded-full bg-slate-900/95 px-4 py-2.5 text-white shadow-2xl backdrop-blur">
        <span className="hidden items-center gap-2 text-sm font-bold sm:flex"><span className="h-2 w-2 animate-pulse rounded-full bg-emerald-400" />{t("site.editing")}</span>
        <span className="flex flex-1 items-center justify-end gap-1.5 sm:flex-none">
          <button onClick={() => open("sections")} className="inline-flex items-center gap-1.5 rounded-full bg-white/15 px-3.5 py-2 text-xs font-bold hover:bg-white/25"><Icon name="menu" className="h-4 w-4" />{t("se.sections")}</button>
          <div className="relative">
            <button onClick={() => setMenu(!menu)} aria-expanded={menu} className="inline-flex items-center gap-1.5 rounded-full bg-white/15 px-3.5 py-2 text-xs font-bold hover:bg-white/25"><Icon name="settings" className="h-4 w-4" />{t("se.siteWide")}</button>
            {menu && (
              <div className="absolute bottom-full mb-2 w-64 animate-scale-in rounded-2xl bg-white p-1.5 text-slate-800 shadow-2xl" onMouseLeave={() => setMenu(false)}>
                <button className={item} onClick={() => open("branding")}><Icon name="sparkles" className="h-4 w-4 text-brand" />{t("site.branding")}</button>
                <button className={item} onClick={() => open("announce")}><Icon name="bell" className="h-4 w-4 text-brand" />{t("se.announce")}</button>
                <button className={item} onClick={() => open("footer")}><Icon name="phone" className="h-4 w-4 text-brand" />{kind === "clinic" ? t("site.section.contact") : t("se.footer")}</button>
                <Link href="/dashboard/settings" className={item}><Icon name="settings" className="h-4 w-4 text-brand" />{t("site.allSettings")}</Link>
              </div>
            )}
          </div>
          <Link href="/" className="inline-flex items-center gap-1.5 rounded-full bg-white px-4 py-2 text-xs font-bold text-slate-900"><Icon name="check" className="h-4 w-4" />{t("se.done")}</Link>
        </span>
      </div>
      {panel && <SiteWideEditor kind={kind} which={panel} order={order} hidden={hiddenKeys} onClose={() => setPanel(null)} />}
    </>
  );
}
