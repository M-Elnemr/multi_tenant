"use client";

import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { api } from "@/lib/client";
import { ease } from "@/lib/motion";
import { useAction, useApi } from "../hooks";
import { Icon } from "../icons";
import { useT } from "../i18n-provider";
import { Alert, Button, ErrorText, Loading, Segmented } from "../ui";

export type GalleryLook = { categories: string[]; swatch: [string, string, string]; dark?: boolean };
type Device = "desktop" | "tablet" | "phone";
const WIDTH: Record<Device, number> = { desktop: 1280, tablet: 834, phone: 390 };

/** A live, scaled copy of the business's own site in one design. Mounted only when scrolled into view (iframes are heavy). */
function LiveFrame({ id, width, height, className = "", interactive = false }: { id: string; width: number; height: number; className?: string; interactive?: boolean }) {
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
          src={`/?tpl=${id}&preview=1`} title={id} loading="lazy" onLoad={() => setLoaded(true)} tabIndex={interactive ? 0 : -1} aria-hidden={!interactive}
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
export function TemplateGallery({ ids, looks, profilePath, prefix, fallback, categoryKey }: { ids: readonly string[]; looks: Record<string, GalleryLook>; profilePath: string; prefix: string; fallback: string; categoryKey: string }) {
  const t = useT();
  const reduce = useReducedMotion();
  const { data, reload } = useApi<{ template?: string }>(profilePath);
  const [cat, setCat] = useState("all");
  const [open, setOpen] = useState<string | null>(null);
  const [device, setDevice] = useState<Device>("desktop");
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
  const current = data.template && ids.includes(data.template) ? data.template : fallback;
  const cats = ["all", ...new Set(ids.flatMap((id) => looks[id].categories))];
  const shown = ids.filter((id) => cat === "all" || looks[id].categories.includes(cat));
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
        <div className="flex items-center gap-2 text-[13px] text-muted"><span className="h-2 w-2 rounded-full bg-emerald-500" />{t("gal.inUse")}: <b className="text-ink">{t(`${prefix}.${current}`)}</b></div>
      </div>

      <div className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none]">
        {cats.map((c) => <button key={c} onClick={() => setCat(c)} className={`chip ${cat === c ? "chip-on" : ""}`}>{c === "all" ? t("gal.all") : t(`${categoryKey}.${c}`)}</button>)}
      </div>

      <ErrorText error={save.error} />
      {saved && <Alert tone="green">{t("gal.switched", { name: t(`${prefix}.${saved}`) })}</Alert>}

      <motion.div layout className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
        <AnimatePresence mode="popLayout">
          {shown.map((id) => {
            const l = looks[id];
            const on = id === current;
            return (
              <motion.article key={id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, scale: 0.98 }} transition={{ duration: 0.3, ease: ease.out }}
                className={`group overflow-hidden rounded-[16px] bg-surface shadow-card transition hover:shadow-[0_0_0_1px_var(--line-strong),0_24px_50px_-28px_rgb(16_18_24/.35)] ${on ? "!shadow-[0_0_0_2px_var(--brand)]" : ""}`}>
                <button onClick={() => { setOpen(id); setDevice("desktop"); }} className="relative block w-full text-start" aria-label={`${t("tpl.preview")} ${t(`${prefix}.${id}`)}`}>
                  <LiveFrame id={id} width={1280} height={900} className="transition duration-500 group-hover:brightness-[.97]" />
                  <span className="absolute inset-0 flex items-end justify-center bg-gradient-to-t from-black/45 via-transparent to-transparent pb-4 opacity-0 transition duration-300 group-hover:opacity-100">
                    <span className="inline-flex items-center gap-2 rounded-full bg-white px-4 py-2 text-[13px] font-semibold text-[#101218] shadow-lg"><Icon name="eye" className="h-4 w-4" />{t("gal.livePreview")}</span>
                  </span>
                  {on && <span className="absolute start-3 top-3 inline-flex items-center gap-1.5 rounded-full bg-emerald-500 px-2.5 py-1 text-[11px] font-bold text-white shadow"><Icon name="check" className="h-3 w-3" />{t("tpl.current")}</span>}
                </button>
                <div className="flex items-start gap-3 p-4">
                  <span className="mt-0.5 flex shrink-0 -space-x-1.5 rtl:space-x-reverse" aria-hidden>
                    {l.swatch.map((c, i) => <span key={i} className="h-5 w-5 rounded-full ring-2 ring-surface" style={{ background: c, boxShadow: "inset 0 0 0 1px rgb(0 0 0 / .08)" }} />)}
                  </span>
                  <div className="min-w-0 flex-1">
                    <h3 className="font-display text-[15px] font-semibold text-ink">{t(`${prefix}.${id}`)}</h3>
                    <p className="line-clamp-2 text-[13px] text-muted">{t(`${prefix}.${id}.for`)}</p>
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
                  <p className="truncate font-display text-[15px] font-semibold">{t(`${prefix}.${open}`)}</p>
                  <p className="truncate text-xs text-white/60">{t(`${prefix}.${open}.for`)}</p>
                </div>
              </div>
              <div className="order-3 w-full sm:order-none sm:w-auto [&_[role=tablist]]:bg-white/10 [&_button]:!text-white/70 [&_button[aria-selected=true]]:!text-[#101218]">
                <Segmented value={device} onChange={setDevice} tabs={[{ key: "desktop", label: t("gal.desktop") }, { key: "tablet", label: t("gal.tablet") }, { key: "phone", label: t("gal.phone") }]} />
              </div>
              <div className="flex items-center gap-2">
                <button onClick={() => step(-1)} className="grid h-9 w-9 place-items-center rounded-full bg-white/10 hover:bg-white/20" aria-label={t("common.prev")}><Icon name="chevron" className="h-4 w-4 rotate-180 flip-rtl" /></button>
                <button onClick={() => step(1)} className="grid h-9 w-9 place-items-center rounded-full bg-white/10 hover:bg-white/20" aria-label={t("common.next")}><Icon name="chevron" className="h-4 w-4 flip-rtl" /></button>
                <a href={`/?tpl=${open}`} target="_blank" rel="noreferrer" className="hidden h-9 items-center gap-1.5 rounded-full bg-white/10 px-3 text-[13px] font-semibold hover:bg-white/20 sm:inline-flex"><Icon name="external" className="h-4 w-4" />{t("gal.newTab")}</a>
                {open === current ? <span className="inline-flex h-9 items-center gap-1.5 rounded-full bg-emerald-500 px-3.5 text-[13px] font-bold"><Icon name="check" className="h-4 w-4" />{t("tpl.current")}</span>
                  : <button onClick={() => void save.run(open)} disabled={save.loading} className="inline-flex h-9 items-center gap-1.5 rounded-full bg-white px-4 text-[13px] font-bold text-[#101218] transition hover:bg-white/90 disabled:opacity-60">{t("tpl.use")}</button>}
              </div>
            </div>
            <div className="flex min-h-0 flex-1 items-start justify-center overflow-auto px-3 pb-6 sm:px-6">
              <motion.div key={open} initial={reduce ? false : { opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0, width: device === "desktop" ? "min(100%, 1280px)" : WIDTH[device] }} transition={{ duration: 0.35, ease: ease.out }}
                className={`overflow-hidden bg-white shadow-[0_40px_120px_-30px_rgb(0_0_0/.8)] ${device === "phone" ? "rounded-[38px] border-[10px] border-[#1b1d22]" : device === "tablet" ? "rounded-[26px] border-[10px] border-[#1b1d22]" : "rounded-[12px]"}`}
                style={{ maxWidth: "100%" }}>
                {device === "desktop" && <div className="flex h-8 items-center gap-1.5 bg-[#f1f1f3] px-3"><i className="h-2.5 w-2.5 rounded-full bg-[#ff5f57]" /><i className="h-2.5 w-2.5 rounded-full bg-[#febc2e]" /><i className="h-2.5 w-2.5 rounded-full bg-[#28c840]" /></div>}
                <iframe key={`${open}-${device}`} src={`/?tpl=${open}&preview=1`} title={t(`${prefix}.${open}`)} className="block w-full border-0" style={{ height: device === "desktop" ? "calc(100dvh - 9rem)" : device === "tablet" ? "min(1112px, calc(100dvh - 9rem))" : "min(844px, calc(100dvh - 9rem))" }} />
              </motion.div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </section>
  );
}
