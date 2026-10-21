"use client";

import { useRouter } from "next/navigation";
import { GoogleSignIn } from "@/components/open/google-sign-in";
import { useI18n } from "@/components/i18n-provider";

export function LoginView({ next }: { next: string }) {
  const { t } = useI18n();
  const router = useRouter();
  return (
    <div className="o-container grid max-w-md pt-8 md:pt-16">
      <div className="o-card o-reveal overflow-hidden">
        <div className="bg-gradient-to-br from-[var(--o-navy)] to-[var(--o-navy-2)] px-6 py-7 text-white">
          <p className="text-3xl">👋</p>
          <h1 className="mt-2 text-2xl font-black">{t("open.auth.welcome")}</h1>
          <p className="mt-1 text-sm text-white/75">{t("open.auth.sub")}</p>
        </div>
        <div className="grid gap-4 p-6">
          <GoogleSignIn next={next} onSignedIn={() => { router.replace(next); router.refresh(); }} />
          <p className="o-mute text-center text-xs">{t("open.auth.sameAccount")}</p>
          <p className="o-mute text-center text-xs">{t("open.auth.guestNote")}</p>
        </div>
      </div>
    </div>
  );
}
