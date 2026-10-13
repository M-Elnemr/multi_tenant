"use client";

import { useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import { api } from "@/lib/client";
import { ChangePasswordCard } from "@/components/change-password";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon, type IconName } from "@/components/icons";
import { PatientIdCard } from "@/components/patient-id-card";
import { PortalQuickLinks } from "@/components/portal-quick-links";
import { Avatar, ErrorText } from "@/components/ui";

function Block({ icon, title, hint, tone = "brand", children }: { icon: IconName; title: string; hint?: string; tone?: "brand" | "red"; children: ReactNode }) {
  return (
    <section className={`animate-fade-up rounded-3xl border bg-white p-5 shadow-soft sm:p-6 ${tone === "red" ? "border-rose-200/80" : "border-slate-200/80"}`}>
      <div className="mb-4 flex items-start gap-3">
        <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl ${tone === "red" ? "bg-rose-50 text-rose-600" : "bg-brand-soft text-brand"}`}><Icon name={icon} className="h-5 w-5" /></span>
        <div><h2 className="font-bold text-ink">{title}</h2>{hint && <p className="mt-0.5 text-sm text-slate-500">{hint}</p>}</div>
      </div>
      {children}
    </section>
  );
}

export default function PortalAccount() {
  const { t, locale, siteName } = useI18n();
  const router = useRouter();
  const { me } = useMe();
  const idInfo = useApi<{ publicId?: number }>("me/patient");
  const [pwOpen, setPwOpen] = useState(false);
  const full = me ? `${me.firstName} ${me.lastName}`.trim() : "";
  const forced = me?.mustChangePassword === true;
  const leave = useAction(async () => {
    if (!window.confirm(t("account.leaveConfirm"))) return;
    await api("portal/leave", { body: {} });
    router.replace("/");
    router.refresh();
  });
  const signOut = async () => { await api("auth/logout", { body: {} }).catch(() => undefined); router.replace("/"); router.refresh(); };
  return (
    <div className="space-y-5">
      <div className="flex animate-fade-up items-center gap-4">
        <Avatar name={full || "?"} className="h-16 w-16 text-xl" />
        <div className="min-w-0">
          <h1 className="truncate text-2xl font-extrabold tracking-tight text-ink sm:text-3xl">{full || t("account.title")}</h1>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-3 text-sm text-slate-500">
            {me?.phone && <span className="inline-flex items-center gap-1.5" dir="ltr"><Icon name="phone" className="h-3.5 w-3.5" />{me.phone}</span>}
            {siteName && <span className="inline-flex items-center gap-1.5"><Icon name="stethoscope" className="h-3.5 w-3.5" />{t("acct.patientOf", { clinic: siteName })}</span>}
          </p>
        </div>
      </div>

      <PortalQuickLinks />

      {idInfo.data?.publicId ? <PatientIdCard publicId={idInfo.data.publicId} name={full} subtitle={siteName} /> : null}

      <div className="grid gap-5 md:grid-cols-2">
        <Block icon="shield" title={t("acct.security")} hint={t("acct.securityHint")}>
          {forced || pwOpen ? <ChangePasswordCard forced={forced} /> : (
            <button onClick={() => setPwOpen(true)} className="flex w-full items-center justify-between rounded-2xl bg-slate-50 px-4 py-3.5 text-start font-semibold transition hover:bg-brand-soft active:scale-[.99]">
              <span className="flex items-center gap-3"><Icon name="settings" className="h-5 w-5 text-brand" />{t("account.changePassword")}</span>
              <Icon name="arrow" className="h-4 w-4 text-slate-400 flip-rtl" />
            </button>
          )}
        </Block>

        <Block icon="language" title={t("acct.preferences")} hint={t("acct.preferencesHint")}>
          <div className="grid grid-cols-2 gap-1.5 rounded-2xl bg-slate-100 p-1.5 text-sm font-bold">
            {([["ar", "العربية"], ["en", "English"]] as const).map(([l, label]) => (
              <a key={l} href={`/api/lang?l=${l}`} className={`rounded-xl py-2.5 text-center transition ${locale === l ? "bg-white text-brand shadow-sm" : "text-slate-500 hover:text-brand"}`}>{label}</a>
            ))}
          </div>
        </Block>
      </div>

      <Block icon="alert" title={t("account.leaveTitle")} hint={t("account.leaveHelp")} tone="red">
        <ErrorText error={leave.error} />
        <div className="flex flex-wrap gap-3">
          <button onClick={() => leave.run()} disabled={leave.loading} className="inline-flex items-center gap-2 rounded-2xl border border-rose-200 px-5 py-2.5 text-sm font-bold text-rose-600 transition hover:bg-rose-50 active:scale-95 disabled:opacity-50">{t("account.leave")}</button>
          <button onClick={signOut} className="inline-flex items-center gap-2 rounded-2xl bg-slate-100 px-5 py-2.5 text-sm font-bold text-slate-700 transition hover:bg-slate-200 active:scale-95"><Icon name="logout" className="h-4 w-4 flip-rtl" />{t("nav.logout")}</button>
        </div>
      </Block>
    </div>
  );
}
