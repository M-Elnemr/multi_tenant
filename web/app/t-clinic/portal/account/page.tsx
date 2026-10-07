"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { ChangePasswordCard } from "@/components/change-password";
import { useAction, useMe } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Button, Card, ErrorText, PageHeader } from "@/components/ui";

export default function PortalAccount() {
  const t = useT();
  const router = useRouter();
  const { me } = useMe();
  const leave = useAction(async () => {
    if (!window.confirm(t("account.leaveConfirm"))) return;
    await api("portal/leave", { body: {} });
    router.replace("/");
    router.refresh();
  });
  return (
    <>
      <PageHeader title={t("account.title")} subtitle={me ? `${me.firstName} ${me.lastName} · ${me.phone ?? ""}` : ""} />
      <div className="space-y-5">
        <ChangePasswordCard />
        <Card className="space-y-3">
          <h2 className="font-medium">{t("account.leaveTitle")}</h2>
          <p className="text-sm text-slate-600">{t("account.leaveHelp")}</p>
          <ErrorText error={leave.error} />
          <Button variant="danger" loading={leave.loading} onClick={() => leave.run()}>{t("account.leave")}</Button>
        </Card>
      </div>
    </>
  );
}
