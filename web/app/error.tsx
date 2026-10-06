"use client";

import { useT } from "@/components/i18n-provider";

export default function Error({ reset }: { error: Error; reset: () => void }) {
  const t = useT();
  return (
    <main className="mx-auto flex min-h-[60vh] max-w-md flex-col items-center justify-center gap-4 p-6 text-center">
      <p className="text-slate-600">{t("error.generic")}</p>
      <button onClick={reset} className="rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white">{t("common.retry")}</button>
    </main>
  );
}
