import Link from "next/link";
import { getT } from "@/lib/i18n-server";

export default async function Landing() {
  const { t } = await getT();
  const steps = ["landing.step1", "landing.step2", "landing.step3"] as const;
  const features = ["landing.f1", "landing.f2", "landing.f3", "landing.f4", "landing.f5", "landing.f6"] as const;
  return (
    <>
      <section className="bg-gradient-to-b from-white to-slate-50">
        <div className="mx-auto max-w-4xl px-4 py-20 text-center">
          <h1 className="text-4xl font-bold leading-tight text-slate-900 sm:text-5xl">{t("landing.title")}</h1>
          <p className="mx-auto mt-5 max-w-2xl text-lg text-slate-600">{t("landing.subtitle")}</p>
          <div className="mt-8 flex flex-wrap justify-center gap-3">
            <Link href="/register?type=STORE" className="rounded-xl bg-brand px-6 py-3 font-medium text-white shadow">{t("landing.ctaStore")}</Link>
            <Link href="/register?type=CLINIC" className="rounded-xl border border-brand px-6 py-3 font-medium text-brand">{t("landing.ctaClinic")}</Link>
          </div>
          <p className="mt-4 text-sm text-slate-500">{t("landing.noCard")}</p>
        </div>
      </section>
      <section className="mx-auto max-w-5xl px-4 py-14">
        <h2 className="mb-8 text-center text-2xl font-semibold">{t("landing.howTitle")}</h2>
        <ol className="grid gap-4 sm:grid-cols-3">
          {steps.map((k, i) => (
            <li key={k} className="rounded-xl border border-slate-200 bg-white p-5">
              <span className="mb-3 flex h-8 w-8 items-center justify-center rounded-full bg-brand text-sm font-bold text-white">{i + 1}</span>
              <p className="font-medium">{t(k)}</p>
              <p className="mt-1 text-sm text-slate-500">{t(`${k}d`)}</p>
            </li>
          ))}
        </ol>
      </section>
      <section className="bg-white py-14">
        <div className="mx-auto max-w-5xl px-4">
          <h2 className="mb-8 text-center text-2xl font-semibold">{t("landing.featuresTitle")}</h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {features.map((k) => (
              <div key={k} className="rounded-xl border border-slate-200 p-5">
                <p className="font-medium">{t(k)}</p>
                <p className="mt-1 text-sm text-slate-500">{t(`${k}d`)}</p>
              </div>
            ))}
          </div>
        </div>
      </section>
    </>
  );
}
