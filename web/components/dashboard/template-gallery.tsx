"use client";

import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { api } from "@/lib/client";
import { ease } from "@/lib/motion";
import { useAction, useApi } from "../hooks";
import { Icon } from "../icons";
import { useT } from "../i18n-provider";
import { Alert, Button, ErrorText, Loading, Segmented } from "../ui";
import { activeTemplates, defaultTokens, isKnownTemplate, TEMPLATE_PALETTES, type SiteKind } from "@/lib/template-palettes";

export type GalleryLook = { categories: string[]; swatch: [string, string, string]; dark?: boolean };
type Device = "desktop" | "tablet" | "phone";

/** Business kinds a profile path serves (the picker passes its own when a shop may also use the small-catalog designs). */
const kindsOf = (profilePath: string): SiteKind[] => (profilePath.startsWith("clinic") ? ["clinic"] : profilePath.startsWith("lawyer") ? ["lawyer"] : ["store"]);
/** Names and one-line personalities: the new designs live under tplx.*, the retired ones keep their own prefix. */
const nameKey = (prefix: string, id: string) => (TEMPLATE_PALETTES[id]?.tokens ? `tplx.${id}` : `${prefix}.${id}`);
/** Swatch of a design: its palette when it has one, else the legacy look's three colours. */
const swatchOf = (id: string, looks: Record<string, GalleryLook>): [string, string, string] => {
  const d = defaultTokens(id);
  return d ? [d.bg, d.text, d.primary] : looks[id]?.swatch ?? ["#ffffff", "#111111", "#888888"];
};
const WIDTH: Record<Device, number> = { desktop: 1280, tablet: 834, phone: 390 };

/** A live, scaled copy of the business's own site in one design. Mounted only when scrolled into view (iframes are heavy). */
function LiveFrame({ id, width, height, className = "", interactive = false, own = true }: { id: string; width: number; height: number; className?: string; interactive?: boolean; own?: boolean }) {
  const box = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(0);
  const [visible, setVisible] = useState(interactive);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setScale(el.clientWidth / width));
    ro.observe(el);
    const io = new IntersectionObserver(([e]) => { if (e.isIntersecting) { setVisible(true); io.disconnect(); } }, { rootMargin: "200px" });
    io.observe(el);
    return () => { ro.disconnect(); io.disconnect(); };
  }, [width]);
  return (
    <div ref={box} className={`relative overflow-hidden bg-surface-2 ${className}`} style={{ height: scale ? height * scale : undefined, aspectRatio: scale ? undefined : `${width}/${height}` }}>
      {!loaded && <div className="skeleton absolute inset-0 !rounded-none" />}
      {visible && scale > 0 && (
        <iframe
          src={`/?tpl=${id}&preview=1${own ? "" : "&theme=off"}`} title={id} loading="lazy" onLoad={() => setLoaded(true)} tabIndex={interactive ? 0 : -1} aria-hidden={!interactive}
          className={`absolute start-0 top-0 origin-top-left border-0 transition-opacity duration-500 rtl:origin-top-right ${loaded ? "opacity-100" : "opacity-0"} ${interactive ? "" : "pointer-events-none"}`}
          style={{ width, height, transform: `scale(${scale})` }}
        />
      )}
    </div>
  );
}

/**
 * Template marketplace for a website (shop or clinic): category filters, large live previews of the business's OWN content in every design,
 * a full-screen preview with desktop / tablet / phone frames, and one click to switch. Saving uses the existing profile endpoint.
 */
export function TemplateGallery({ ids: legacyIds, looks, profilePath, prefix, fallback, kinds }: { ids: readonly string[]; looks: Record<string, GalleryLook>; profilePath: string; prefix: string; fallback: string; categoryKey: string; kinds?: SiteKind[] }) {
  const t = useT();
  const reduce = useReducedMotion();
  const { data, reload } = useApi<{ template?: string; siteTheme?: { tokens: Record<string, string> } | null }>(profilePath);
  const [cat, setCat] = useState("all");
  const [open, setOpen] = useState<string | null>(null);
  const [device, setDevice] = useState<Device>("desktop");
  const [own, setOwn] = useState(true);   // preview with the owner's own colours (when set) or the design's own
  const [saved, setSaved] = useState<string | null>(null);
  const save = useAction(async (template: string) => { setSaved(null); await api(profilePath, { method: "PATCH", body: { template } }); setSaved(template); await reload(); });
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(null); };
    window.addEventListener("keydown", h);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { window.removeEventListener("keydown", h); document.body.style.overflow = prev; };
  }, [open]);
  if (!data) return <Loading variant="cards" />;
  // the picker offers the active designs of this business kind; a retired design the site already uses stays listed (first) so it can be left and returned to
  const current = data.template || fallback;
  const ids: string[] = [...new Set([...(kinds ?? kindsOf(profilePath)).flatMap(activeTemplates), ...legacyIds.filter((id) => !isKnownTemplate(id))])];
  if (!ids.includes(current) && (isKnownTemplate(current) || legacyIds.includes(current))) ids.unshift(current);
  const kindOfId = (id: string) => TEMPLATE_PALETTES[id]?.kind ?? "store";
  const cats = ["all", ...new Set(ids.map(kindOfId))];
  const shown = ids.filter((id) => cat === "all" || kindOfId(id) === cat);
  const idx = open ? ids.indexOf(open) : -1;
  const step = (d: number) => setOpen(ids[(idx + d + ids.length) % ids.length]);

  return (
    <section className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="eyebrow">{t("gal.eyebrow")}</p>
          <h2 className="mt-1 font-display text-2xl font-semibold text-ink">{t("gal.title")}</h2>
          <p className="mt-1 max-w-2xl text-sm text-muted">{t("gal.hint")}</p>
        </div>
        <div className="flex items-center gap-2 text-[13px] text-muted"><span className="h-2 w-2 rounded-full bg-emerald-500" />{t("gal.inUse")}: <b className="text-ink">{t(nameKey(prefix, current))}</b></div>
      </div>

      <div className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none]">
        {cats.map((c) => <button key={c} onClick={() => setCat(c)} className={`chip ${cat === c ? "chip-on" : ""}`}>{c === "all" ? t("gal.all") : t(`gal.kind.${c}`)}</button>)}
      </div>

      <ErrorText error={save.error} />
      {saved && <Alert tone="green">{t("gal.switched", { name: t(nameKey(prefix, saved)) })}</Alert>}

      <motion.div layout className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
        <AnimatePresence mode="popLayout">
          {shown.map((id) => {
            const sw = swatchOf(id, looks);
            const on = id === current;
            return (
              <motion.article key={id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, scale: 0.98 }} transition={{ duration: 0.3, ease: ease.out }}
                className={`group overflow-hidden rounded-[16px] bg-surface shadow-card transition hover:shadow-[0_0_0_1px_var(--line-strong),0_24px_50px_-28px_rgb(16_18_24/.35)] ${on ? "!shadow-[0_0_0_2px_var(--brand)]" : ""}`}>
                <button onClick={() => { setOpen(id); setDevice("desktop"); }} className="relative block w-full text-start" aria-label={`${t("tpl.preview")} ${t(nameKey(prefix, id))}`}>
                  <LiveFrame id={id} width={1280} height={900} own={own} className="transition duration-500 group-hover:brightness-[.97]" />
                  <span className="absolute inset-0 flex items-end justify-center bg-gradient-to-t from-black/45 via-transparent to-transparent pb-4 opacity-0 transition duration-300 group-hover:opacity-100">
                    <span className="inline-flex items-center gap-2 rounded-full bg-[#fff] px-4 py-2 text-[13px] font-semibold text-[#101218] shadow-lg"><Icon name="eye" className="h-4 w-4" />{t("gal.livePreview")}</span>
                  </span>
                  {on && <span className="absolute start-3 top-3 inline-flex items-center gap-1.5 rounded-full bg-emerald-500 px-2.5 py-1 text-[11px] font-bold text-white shadow"><Icon name="check" className="h-3 w-3" />{t("tpl.current")}</span>}
                </button>
                <div className="flex items-start gap-3 p-4">
                  <span className="mt-0.5 flex shrink-0 -space-x-1.5 rtl:space-x-reverse" aria-hidden>
                    {sw.map((c, i) => <span key={i} className="h-5 w-5 rounded-full ring-2 ring-surface" style={{ background: c, boxShadow: "inset 0 0 0 1px rgb(0 0 0 / .08)" }} />)}
                  </span>
                  <div className="min-w-0 flex-1">
                    <h3 className="font-display text-[15px] font-semibold text-ink">{t(nameKey(prefix, id))}{TEMPLATE_PALETTES[id] && !TEMPLATE_PALETTES[id].active ? <span className="ms-2 rounded-full bg-surface-2 px-2 py-0.5 text-[10px] font-semibold text-muted">{t("gal.retired")}</span> : null}</h3>
                    <p className="line-clamp-2 text-[13px] text-muted">{t(`${nameKey(prefix, id)}.for`)}</p>
                  </div>
                  {on ? <a href="/?edit=1" target="_blank" rel="noreferrer" className="shrink-0"><Button size="sm" variant="secondary"><Icon name="edit" className="h-3.5 w-3.5" />{t("gal.customize")}</Button></a>
                    : <Button size="sm" loading={save.loading && save.error == null && !saved} onClick={() => void save.run(id)}>{t("tpl.use")}</Button>}
                </div>
              </motion.article>
            );
          })}
        </AnimatePresence>
      </motion.div>

      {/* Full-screen live preview with device frames */}
      <AnimatePresence>
        {open && (
          <motion.div className="fixed inset-0 z-[80] flex flex-col bg-[rgb(10_12_16/.92)] backdrop-blur-sm" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: reduce ? 0 : 0.2 }}>
            <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-white sm:px-6">
              <div className="flex min-w-0 items-center gap-3">
                <button onClick={() => setOpen(null)} className="grid h-9 w-9 place-items-center rounded-full bg-white/10 transition hover:bg-white/20" aria-label={t("common.close")}><Icon name="close" className="h-4 w-4" /></button>
                <div className="min-w-0">
                  <p className="truncate font-display text-[15px] font-semibold">{t(nameKey(prefix, open))}</p>
                  <p className="truncate text-xs text-white/60">{t(`${nameKey(prefix, open)}.for`)}</p>
                </div>
              </div>
              <div className="order-3 w-full sm:order-none sm:w-auto [&_[role=tablist]]:bg-white/10 [&_button]:!text-white/70 [&_button[aria-selected=true]]:!text-[#101218]">
                <Segmented value={device} onChange={setDevice} tabs={[{ key: "desktop", label: t("gal.desktop") }, { key: "tablet", label: t("gal.tablet") }, { key: "phone", label: t("gal.phone") }]} />
              </div>
              <div className="flex items-center gap-2">
                {data.siteTheme && Object.keys(data.siteTheme.tokens).length > 0 && (
                  <button onClick={() => setOwn(!own)} className="hidden h-9 items-center rounded-full bg-white/10 px-3 text-[13px] font-semibold hover:bg-white/20 sm:inline-flex" aria-pressed={own}>{own ? t("gal.myColours") : t("gal.designColours")}</button>
                )}
                <button onClick={() => step(-1)} className="grid h-9 w-9 place-items-center rounded-full bg-white/10 hover:bg-white/20" aria-label={t("common.prev")}><Icon name="chevron" className="h-4 w-4 rotate-180 flip-rtl" /></button>
                <button onClick={() => step(1)} className="grid h-9 w-9 place-items-center rounded-full bg-white/10 hover:bg-white/20" aria-label={t("common.next")}><Icon name="chevron" className="h-4 w-4 flip-rtl" /></button>
                <a href={`/?tpl=${open}`} target="_blank" rel="noreferrer" className="hidden h-9 items-center gap-1.5 rounded-full bg-white/10 px-3 text-[13px] font-semibold hover:bg-white/20 sm:inline-flex"><Icon name="external" className="h-4 w-4" />{t("gal.newTab")}</a>
                {open === current ? <span className="inline-flex h-9 items-center gap-1.5 rounded-full bg-emerald-500 px-3.5 text-[13px] font-bold"><Icon name="check" className="h-4 w-4" />{t("tpl.current")}</span>
                  : <button onClick={() => void save.run(open)} disabled={save.loading} className="inline-flex h-9 items-center gap-1.5 rounded-full bg-[#fff] px-4 text-[13px] font-bold text-[#101218] transition hover:bg-[#fff]/90 disabled:opacity-60">{t("tpl.use")}</button>}
              </div>
            </div>
            <div className="flex min-h-0 flex-1 items-start justify-center overflow-auto px-3 pb-6 sm:px-6">
              <motion.div key={open} initial={reduce ? false : { opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0, width: device === "desktop" ? "min(100%, 1280px)" : WIDTH[device] }} transition={{ duration: 0.35, ease: ease.out }}
                className={`overflow-hidden bg-white shadow-[0_40px_120px_-30px_rgb(0_0_0/.8)] ${device === "phone" ? "rounded-[38px] border-[10px] border-[#1b1d22]" : device === "tablet" ? "rounded-[26px] border-[10px] border-[#1b1d22]" : "rounded-[12px]"}`}
                style={{ maxWidth: "100%" }}>
                {device === "desktop" && <div className="flex h-8 items-center gap-1.5 bg-[#f1f1f3] px-3"><i className="h-2.5 w-2.5 rounded-full bg-[#ff5f57]" /><i className="h-2.5 w-2.5 rounded-full bg-[#febc2e]" /><i className="h-2.5 w-2.5 rounded-full bg-[#28c840]" /></div>}
                <iframe key={`${open}-${device}`} src={`/?tpl=${open}&preview=1${own ? "" : "&theme=off"}`} title={t(nameKey(prefix, open))} className="block w-full border-0" style={{ height: device === "desktop" ? "calc(100dvh - 9rem)" : device === "tablet" ? "min(1112px, calc(100dvh - 9rem))" : "min(844px, calc(100dvh - 9rem))" }} />
              </motion.div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </section>
  );
}
