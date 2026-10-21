"use client";

import { useState } from "react";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";

type Img = { id?: string; thumb: string; medium: string; original: string; alt?: string | null };

/** Product pictures: a big image with thumbnails on desktop, a swipeable strip on phones, and a full-screen viewer on tap. */
export function OpenGallery({ images, name, badge }: { images: Img[]; name: string; badge?: React.ReactNode }) {
  const { t } = useI18n();
  const [i, setI] = useState(0);
  const [zoom, setZoom] = useState(false);
  if (images.length === 0) return <div className="grid aspect-square place-items-center rounded-3xl bg-[var(--o-soft)] text-7xl opacity-40">🛍️</div>;
  const cur = images[i];
  const step = (d: number) => setI((n) => (n + d + images.length) % images.length);
  return (
    <div className="flex flex-col-reverse gap-3 md:flex-row">
      {images.length > 1 && (
        <div className="o-scroll-hide flex gap-2 overflow-x-auto md:max-h-[34rem] md:w-[4.6rem] md:flex-col md:overflow-y-auto md:overflow-x-visible">
          {images.map((im, n) => (
            <button key={im.id ?? n} type="button" onClick={() => setI(n)} aria-label={`${n + 1}`} className={`h-16 w-16 shrink-0 overflow-hidden rounded-xl border-2 bg-[var(--o-soft)] transition md:h-[4.4rem] md:w-[4.4rem] ${n === i ? "border-[var(--o-accent)]" : "border-transparent opacity-70 hover:opacity-100"}`}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={im.thumb} alt="" width={70} height={70} className="h-full w-full object-cover" loading="lazy" />
            </button>
          ))}
        </div>
      )}
      <div className="relative min-w-0 flex-1 overflow-hidden rounded-3xl bg-white shadow-[var(--o-shadow)]">
        <button type="button" onClick={() => setZoom(true)} className="block aspect-square w-full cursor-zoom-in" aria-label={t("open.zoom")}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img key={cur.medium} src={cur.medium} alt={cur.alt || name} width={720} height={720} fetchPriority="high" className="o-pop h-full w-full object-contain" />
        </button>
        <div className="absolute start-3 top-3 flex flex-col items-start gap-1.5">{badge}</div>
        {images.length > 1 && (
          <>
            <button type="button" onClick={() => step(-1)} aria-label="previous" className="absolute start-2 top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/90 shadow-lg transition hover:scale-110"><Icon name="arrow" className="h-5 w-5 rotate-180 rtl:rotate-0" /></button>
            <button type="button" onClick={() => step(1)} aria-label="next" className="absolute end-2 top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/90 shadow-lg transition hover:scale-110"><Icon name="arrow" className="h-5 w-5 rtl:rotate-180" /></button>
            <span className="absolute bottom-3 end-3 rounded-full bg-black/55 px-2.5 py-1 text-xs font-bold text-white backdrop-blur">{i + 1} / {images.length}</span>
          </>
        )}
      </div>
      {zoom && (
        <div className="fixed inset-0 z-[80] grid place-items-center bg-black/90 p-4 o-pop" onClick={() => setZoom(false)} role="dialog" aria-modal="true">
          <button type="button" aria-label={t("common.close")} className="absolute end-4 top-4 grid h-11 w-11 place-items-center rounded-full bg-white/15 text-white" onClick={() => setZoom(false)}><Icon name="close" className="h-6 w-6" /></button>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={cur.original} alt={cur.alt || name} className="max-h-full max-w-full rounded-2xl object-contain" onClick={(e) => e.stopPropagation()} />
        </div>
      )}
    </div>
  );
}
