import { Suspense } from "react";
import { currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";
import { LoginForm } from "./login-form";

/**
 * The control-panel sign-in on the business's own address (shop.com/dashboard/login): the business's own logo, name and colours,
 * no Elmanassa branding, and straight into the dashboard after signing in.
 */
export async function StaffLogin() {
  const { t } = await getT();
  const info = await resolveHost(await currentHost());
  const name = info.kind === "TENANT" ? info.name : "";
  const logo = info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id : undefined;
  const kind = info.kind === "TENANT" ? info.type : "STORE";
  const perks = [t(`staffLogin.perk1.${kind}`), t(`staffLogin.perk2.${kind}`), t(`staffLogin.perk3.${kind}`)];
  return (
    <main className="grid min-h-dvh lg:grid-cols-[1.05fr_1fr]">
      <section className="relative hidden overflow-hidden bg-brand-gradient p-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div className="pointer-events-none absolute -end-24 -top-24 h-96 w-96 rounded-full bg-white/10 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-32 -start-16 h-[28rem] w-[28rem] rounded-full bg-black/10 blur-3xl" />
        <div className="relative flex items-center gap-3">
          {logo
            // eslint-disable-next-line @next/next/no-img-element
            ? <img src={`/api/bff/files/${logo}/content?variant=thumb`} alt="" className="h-14 w-14 rounded-2xl bg-white object-cover shadow-xl" />
            : <span className="grid h-14 w-14 place-items-center rounded-2xl bg-white/20 text-2xl font-extrabold backdrop-blur">{name.trim().charAt(0).toUpperCase()}</span>}
          <span className="text-xl font-extrabold">{name}</span>
        </div>
        <div className="relative max-w-md">
          <p className="text-sm font-bold uppercase tracking-[0.2em] text-white/70">{t("staffLogin.eyebrow")}</p>
          <h1 className="mt-3 text-4xl font-extrabold leading-tight">{t(`staffLogin.headline.${kind}`)}</h1>
          <ul className="mt-8 space-y-3">{perks.map((p) => <li key={p} className="flex items-center gap-3 text-white/90"><span className="grid h-7 w-7 place-items-center rounded-full bg-white/20">✓</span>{p}</li>)}</ul>
        </div>
        <p className="relative text-sm text-white/60">{t("staffLogin.secure")}</p>
      </section>
      <section className="flex items-center justify-center bg-gradient-to-b from-slate-50 to-white px-4 py-10">
        <div className="w-full max-w-md animate-fade-up">
          <div className="mb-8 text-center lg:hidden">
            {logo
              // eslint-disable-next-line @next/next/no-img-element
              ? <img src={`/api/bff/files/${logo}/content?variant=thumb`} alt="" className="mx-auto h-16 w-16 rounded-2xl object-cover shadow-lg" />
              : <span className="mx-auto grid h-16 w-16 place-items-center rounded-2xl bg-brand-gradient text-2xl font-extrabold text-white shadow-lg">{name.trim().charAt(0).toUpperCase()}</span>}
            <p className="mt-3 text-lg font-extrabold text-ink">{name}</p>
          </div>
          <h2 className="text-2xl font-extrabold tracking-tight text-ink">{t("staffLogin.title")}</h2>
          <p className="mt-1.5 text-sm text-slate-500">{t("staffLogin.subtitle")}</p>
          <div className="mt-6 rounded-3xl border border-slate-200/80 bg-white p-6 shadow-lift sm:p-8"><Suspense><LoginForm /></Suspense></div>
          <p className="mt-6 text-center text-sm text-slate-500"><a href="/" className="font-semibold text-brand hover:underline">← {t("staffLogin.back")}</a></p>
        </div>
      </section>
    </main>
  );
}
