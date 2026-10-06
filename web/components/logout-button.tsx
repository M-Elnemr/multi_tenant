"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { useT } from "./i18n-provider";
import { Button } from "./ui";

export function LogoutButton({ className }: { className?: string }) {
  const t = useT();
  const router = useRouter();
  return (
    <Button variant="ghost" size="sm" className={className} onClick={async () => {
      await api("auth/logout", { body: {} }).catch(() => undefined);
      router.replace("/");
      router.refresh();
    }}>
      {t("nav.logout")}
    </Button>
  );
}
