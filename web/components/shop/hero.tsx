"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

export type Slide = { id: string; imageUrl: string; title?: string; subtitle?: string; href?: string };

/** Homepage banner carousel: the owner's banners fade through; with a single slide it is just a hero image. */
export function BannerCarousel({ slides, cta }: { slides: Slide[]; cta: string }) {
  const [i, setI] = useState(0);
  useEffect(() => {
    if (slides.length < 2) return;
    const id = setInterval(() => setI((x) => (x + 1) % slides.length), 5500);
    return () => clearInterval(id);
  }, [slides.length]);
  return (
    <div className="relative overflow-hidden rounded-[28px] shadow-[var(--s-shadow-lg)]">
      <div className="relative aspect-[16/9] sm:aspect-[21/8]">
        {slides.map((s, idx) => (
          <div key={s.id} className={`absolute inset-0 transition-opacity duration-700 ${idx === i ? "opacity-100" : "pointer-events-none opacity-0"}`} aria-hidden={idx !== i}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={s.imageUrl} alt={s.title ?? ""} className="h-full w-full object-cover" loading={idx === 0 ? "eager" : "lazy"} />
            {(s.title || s.subtitle) && (
              <div className="absolute inset-0 s-overlay-side flex items-end p-6 sm:items-center sm:p-12">
                <div className="max-w-lg text-white">
                  {s.title && <h2 className="text-2xl font-extrabold leading-tight drop-shadow sm:text-5xl">{s.title}</h2>}
                  {s.subtitle && <p className="mt-2 text-sm opacity-95 sm:text-lg">{s.subtitle}</p>}
                  {s.href && <Link href={s.href} className="s-btn mt-4">{cta}</Link>}
                </div>
              </div>
            )}
            {!s.title && !s.subtitle && s.href && <Link href={s.href} className="absolute inset-0" aria-label={cta} />}
          </div>
        ))}
      </div>
      {slides.length > 1 && (
        <div className="absolute inset-x-0 bottom-3 flex justify-center gap-1.5">
          {slides.map((s, idx) => <button key={s.id} onClick={() => setI(idx)} aria-label={`${idx + 1}`} className={`h-2 rounded-full transition-all ${idx === i ? "w-6 bg-white" : "w-2 bg-white/55"}`} />)}
        </div>
      )}
    </div>
  );
}
