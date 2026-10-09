import Link from "next/link";
import { Icon, type IconName } from "@/components/icons";
import { getT } from "@/lib/i18n-server";

const FEATURE_ICONS: IconName[] = ["bag", "calendar", "users", "shield", "chart", "globe"];

export default async function Landing() {
  const { t } = await getT();
  const steps = ["landing.step1", "landing.step2", "landing.step3"] as const;
  const features = ["landing.f1", "landing.f2", "landing.f3", "landing.f4", "landing.f5", "landing.f6"] as const;
  return (
    <>
      <section className="relative overflow-hidden">
        <div className="pointer-events-none absolute -top-24 start-[-8rem] h-96 w-96 rounded-full bg-brand/15 blur-3xl [animation:drift_14s_ease-in-out_infinite]" />
        <div className="pointer-events-none absolute -bottom-24 end-[-6rem] h-96 w-96 rounded-full bg-brand-2/20 blur-3xl [animation:drift_18s_ease-in-out_infinite_reverse]" />
        <div className="relative mx-auto grid max-w-6xl items-center gap-10 px-4 py-16 sm:py-24 lg:grid-cols-[1.15fr_0.85fr]">
          <div className="text-center lg:text-start">
            <h1 className="animate-fade-up text-4xl font-extrabold leading-[1.25] tracking-tight text-ink sm:text-5xl">
              <span className="text-gradient">{t("landing.title")}</span>
            </h1>
            <p className="mt-5 max-w-xl animate-fade-up text-lg leading-relaxed text-slate-600 [animation-delay:100ms] lg:mx-0 mx-auto">{t("landing.subtitle")}</p>
            <div className="mt-8 flex animate-fade-up flex-wrap justify-center gap-3 [animation-delay:200ms] lg:justify-start">
              <Link href="/register?type=STORE" className="group inline-flex items-center gap-2 rounded-2xl bg-brand-gradient px-6 py-3.5 font-semibold text-white shadow-brand transition hover:-translate-y-0.5 hover:brightness-110 active:scale-[.97]">
                <Icon name="bag" className="h-5 w-5" />{t("landing.ctaStore")}
                <Icon name="arrow" className="h-4 w-4 flip-rtl transition group-hover:translate-x-1 rtl:group-hover:-translate-x-1" />
              </Link>
              <Link href="/register?type=CLINIC" className="group inline-flex items-center gap-2 rounded-2xl border-2 border-brand/30 bg-white px-6 py-3.5 font-semibold text-brand shadow-sm transition hover:-translate-y-0.5 hover:border-brand hover:bg-brand-soft active:scale-[.97]">
                <Icon name="stethoscope" className="h-5 w-5" />{t("landing.ctaClinic")}
              </Link>
            </div>
            <p className="mt-4 flex animate-fade-up items-center justify-center gap-2 text-sm text-slate-500 [animation-delay:300ms] lg:justify-start"><Icon name="check" className="h-4 w-4 text-emerald-600" />{t("landing.noCard")}</p>
          </div>
          <div className="relative mx-auto w-64 animate-scale-in sm:w-80 lg:w-full lg:max-w-sm">
            <div className="absolute inset-6 rounded-full bg-brand-gradient opacity-20 blur-2xl" />
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/brand/logo.png" alt="" className="relative w-full animate-float drop-shadow-[0_24px_40px_rgb(31_106_153/0.35)]" />
          </div>
        </div>
      </section>

      <section className="mx-auto max-w-5xl px-4 py-14">
        <h2 className="mb-10 text-center text-2xl font-bold sm:text-3xl">{t("landing.howTitle")}</h2>
        <ol className="stagger grid gap-5 sm:grid-cols-3">
          {steps.map((k, i) => (
            <li key={k} style={{ "--i": i } as React.CSSProperties} className="hover-lift relative rounded-2xl border border-slate-200/80 bg-white p-6 shadow-soft">
              <span className="mb-4 flex h-10 w-10 items-center justify-center rounded-xl bg-brand-gradient text-lg font-bold text-white shadow-brand">{i + 1}</span>
              <p className="text-lg font-bold">{t(k)}</p>
              <p className="mt-1.5 text-sm leading-relaxed text-slate-500">{t(`${k}d`)}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className="border-y border-slate-200/70 bg-white py-16">
        <div className="mx-auto max-w-6xl px-4">
          <h2 className="mb-10 text-center text-2xl font-bold sm:text-3xl">{t("landing.featuresTitle")}</h2>
          <div className="stagger grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {features.map((k, i) => (
              <div key={k} style={{ "--i": i } as React.CSSProperties} className="hover-lift group rounded-2xl border border-slate-200/80 bg-white p-6">
                <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-soft text-brand transition group-hover:bg-brand-gradient group-hover:text-white"><Icon name={FEATURE_ICONS[i]} className="h-6 w-6" /></span>
                <p className="font-bold">{t(k)}</p>
                <p className="mt-1.5 text-sm leading-relaxed text-slate-500">{t(`${k}d`)}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="px-4 py-16">
        <div className="bg-brand-gradient mx-auto max-w-4xl overflow-hidden rounded-3xl p-10 text-center text-white shadow-lift">
          <h2 className="text-2xl font-extrabold sm:text-3xl">{t("landing.title")}</h2>
          <div className="mt-6 flex flex-wrap justify-center gap-3">
            <Link href="/register?type=STORE" className="rounded-2xl bg-white px-6 py-3 font-semibold text-brand shadow transition hover:-translate-y-0.5 active:scale-[.97]">{t("landing.ctaStore")}</Link>
            <Link href="/register?type=CLINIC" className="rounded-2xl border-2 border-white/60 px-6 py-3 font-semibold text-white transition hover:-translate-y-0.5 hover:bg-white/10 active:scale-[.97]">{t("landing.ctaClinic")}</Link>
          </div>
        </div>
      </section>
    </>
  );
}
