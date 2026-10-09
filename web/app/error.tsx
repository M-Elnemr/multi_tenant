"use client";

import { LogoMark } from "@/components/brand";
import { useT } from "@/components/i18n-provider";

export default function Error({ reset }: { error: Error; reset: () => void }) {
  const t = useT();
  return (
    <main className="mx-auto flex min-h-[70vh] max-w-md animate-fade-up flex-col items-center justify-center gap-4 p-6 text-center">
      <LogoMark className="h-20 w-20 opacity-70 grayscale" />
      <p className="text-slate-600">{t("error.generic")}</p>
      <button onClick={reset} className="rounded-xl bg-brand-gradient px-6 py-2.5 text-sm font-semibold text-white shadow-brand transition active:scale-[.97]">{t("common.retry")}</button>
    </main>
  );
}
