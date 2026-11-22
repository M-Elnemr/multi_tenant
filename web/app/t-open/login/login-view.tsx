"use client";

import { useRouter } from "next/navigation";
import { GoogleSignIn } from "@/components/open/google-sign-in";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";

export function LoginView({ next }: { next: string }) {
  const { t } = useI18n();
  const router = useRouter();
  return (
    <div className="o-container grid max-w-md pt-8 md:pt-16">
      <div className="o-card o-reveal overflow-hidden">
        <div className="border-b border-[var(--o-line)] bg-[var(--o-soft)] px-6 py-7">
          <span className="grid h-11 w-11 place-items-center rounded-full bg-[var(--o-ink)] text-[var(--o-paper)]"><Icon name="bag" className="h-5 w-5" /></span>
          <h1 className="o-h1 mt-3">{t("open.auth.welcome")}</h1>
          <p className="o-mute mt-1 text-sm">{t("open.auth.sub")}</p>
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
