"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { useT } from "./i18n-provider";
import { Icon } from "./icons";
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
      <Icon name="logout" className="h-4 w-4 flip-rtl" />{t("nav.logout")}
    </Button>
  );
}
