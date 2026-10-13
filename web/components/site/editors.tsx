"use client";

import { Reorder } from "motion/react";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import BrandingCard from "../dashboard/branding";
import { useI18n } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { DrawerShell } from "./drawer-shell";
import { AboutEditor, ClinicAnnounceEditor, ContactEditor, DoctorsEditor, FaqEditor, GalleryEditor, HeroEditor, InfoEditor, InsuranceEditor, LinkEditor, VisitEditor, type EditorProps } from "./clinic-editors";
import { BannersEditor, StoreAnnounceEditor, StoreBranchesEditor, StoreContactEditor, TrustEditor, WhatsAppEditor } from "./store-editors";

export type Kind = "clinic" | "store";
export const SECTION_ICON: Record<string, IconName> = {
  hero: "sparkles", about: "file", services: "services", doctors: "users", how: "navigate", visit: "pin", gallery: "star", insurance: "shield", faq: "chat", contact: "phone",
  banners: "tag", trust: "award", categories: "box", featured: "star", offers: "coupon", bestSellers: "trending", newest: "sparkles", whatsapp: "chat", branches: "pin",
};

/** The editor for one section of the public site. Each editor only contains the data of that section. */
export function SectionEditor({ kind, section, onClose }: { kind: Kind; section: string } & EditorProps) {
  const { t } = useI18n();
  const title = t(`site.section.${section}`);
  if (kind === "clinic") {
    switch (section) {
      case "hero": return <HeroEditor onClose={onClose} />;
      case "about": return <AboutEditor onClose={onClose} />;
      case "services": return <LinkEditor onClose={onClose} title={title} description={t("se.d.services")} icon="services" href="/dashboard/services" label={t("site.manageServices")} />;
      case "doctors": return <DoctorsEditor onClose={onClose} />;
      case "how": return <InfoEditor onClose={onClose} title={title} description={t("se.d.how")} icon="sparkles" body={t("site.fixedSection")} />;
      case "visit": return <VisitEditor onClose={onClose} />;
      case "gallery": return <GalleryEditor onClose={onClose} />;
      case "insurance": return <InsuranceEditor onClose={onClose} />;
      case "faq": return <FaqEditor onClose={onClose} />;
      case "contact": return <ContactEditor onClose={onClose} />;
    }
  } else {
    switch (section) {
      case "banners": return <BannersEditor onClose={onClose} />;
      case "trust": return <TrustEditor onClose={onClose} />;
      case "categories": return <LinkEditor onClose={onClose} title={title} description={t("se.d.categories")} icon="box" href="/dashboard/products" label={t("site.manageProducts")} />;
      case "featured": return <LinkEditor onClose={onClose} title={title} description={t("se.d.featured")} icon="star" href="/dashboard/products" label={t("site.manageProducts")} />;
      case "offers": return <LinkEditor onClose={onClose} title={title} description={t("se.d.offers")} icon="tag" href="/dashboard/products" label={t("site.manageProducts")} />;
      case "newest": return <InfoEditor onClose={onClose} title={title} description={t("se.d.newest")} icon="sparkles" body={t("se.i.newest")} />;
      case "bestSellers": return <InfoEditor onClose={onClose} title={title} description={t("se.d.bestSellers")} icon="chart" body={t("se.i.bestSellers")} />;
      case "whatsapp": return <WhatsAppEditor onClose={onClose} />;
      case "branches": return <StoreBranchesEditor onClose={onClose} />;
    }
  }
  return null;
}

export type SiteWide = "branding" | "announce" | "footer" | "sections";

/** Editors that are not a section of the page: colours and logo, the announcement bar, the footer, and the list of sections. */
export function SiteWideEditor({ kind, which, order, hidden, onClose }: { kind: Kind; which: SiteWide; order: string[]; hidden: string[] } & EditorProps) {
  const { t } = useI18n();
  const router = useRouter();
  const done = () => { router.refresh(); onClose(); };
  if (which === "branding") return <DrawerShell icon="sparkles" title={t("site.branding")} description={t("se.d.branding")} onClose={done}><BrandingCard /></DrawerShell>;
  if (which === "announce") return kind === "clinic" ? <ClinicAnnounceEditor onClose={onClose} /> : <StoreAnnounceEditor onClose={onClose} />;
  if (which === "footer") return kind === "clinic" ? <ContactEditor onClose={onClose} /> : <StoreContactEditor onClose={onClose} />;
  return <SectionsPanel kind={kind} order={order} hidden={hidden} onClose={done} />;
}

/** Every section in one list: drag to reorder, switch to show or hide. Changes apply to the page behind at once. */
function SectionsPanel({ kind, order, hidden, onClose }: { kind: Kind; order: string[]; hidden: string[]; onClose: () => void }) {
  const { t } = useI18n();
  const router = useRouter();
  const [list, setList] = useState(order);
  const [off, setOff] = useState(hidden);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [state, setState] = useState<"idle" | "saving" | "saved">("idle");
  const push = (o: string[], h: string[]) => {
    setState("saving");
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      try { await api(`${kind}/profile`, { method: "PATCH", body: { siteLayout: { order: o, hidden: h } } }); router.refresh(); setState("saved"); } catch { setState("idle"); }
    }, 450);
  };
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  return (
    <DrawerShell icon="menu" title={t("se.sections")} description={t("se.d.sections")} onClose={onClose}>
      <p className="text-xs font-semibold text-slate-500">{state === "saving" ? t("se.saving") : state === "saved" ? `✓ ${t("common.saved")}` : t("se.dragHint")}</p>
      <Reorder.Group axis="y" values={list} onReorder={(o) => { setList(o); push(o, off); }} className="space-y-2">
        {list.map((k) => {
          const hide = off.includes(k);
          return (
            <Reorder.Item key={k} value={k} whileDrag={{ scale: 1.02, boxShadow: "0 14px 30px -10px rgb(15 35 60 / 0.3)" }} className={`flex cursor-grab items-center gap-3 rounded-2xl border bg-white p-3.5 active:cursor-grabbing ${hide ? "opacity-60" : ""}`}>
              <span className="text-lg text-slate-300" aria-hidden>⠿</span>
              <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand-soft text-brand"><Icon name={SECTION_ICON[k] ?? "sparkles"} className="h-4.5 w-4.5" /></span>
              <span className="flex-1 font-semibold">{t(`site.section.${k}`)}</span>
              <label className="flex items-center gap-2 text-xs font-semibold text-slate-500"><input type="checkbox" role="switch" checked={!hide} onChange={(e) => { const n = e.target.checked ? off.filter((x) => x !== k) : [...off, k]; setOff(n); push(list, n); }} className="h-5 w-9 cursor-pointer" />{hide ? t("common.hidden") : t("common.show")}</label>
            </Reorder.Item>
          );
        })}
      </Reorder.Group>
    </DrawerShell>
  );
}
