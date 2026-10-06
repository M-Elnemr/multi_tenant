"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { api } from "@/lib/client";
import { useAction } from "../hooks";
import { useT } from "../i18n-provider";
import { Alert, Button, Card, ErrorText } from "../ui";

function Inner({ back }: { back: string }) {
  const t = useT();
  const q = useSearchParams();
  const router = useRouter();
  const kind = q.get("kind") ?? "subscription";
  const id = q.get("invoice") ?? q.get("id") ?? "";
  const amount = Number(q.get("amount") ?? 0);
  const pay = useAction(async () => {
    await api("dev/mock-pay", { body: { kind, id, amountMinor: amount } });
    router.replace(back);
  });
  return (
    <div className="mx-auto max-w-md py-10">
      <Card className="space-y-4 text-center">
        <h1 className="text-xl font-semibold">{t("mockpay.title")}</h1>
        <Alert tone="amber">{t("mockpay.notice")}</Alert>
        <ErrorText error={pay.error} />
        <Button className="w-full" loading={pay.loading} onClick={() => pay.run()}>{t("mockpay.pay")}</Button>
      </Card>
    </div>
  );
}

/** Development stand-in for a payment gateway's hosted page (only works when the backend runs with the dev profile). */
export default function MockPayPage({ back }: { back: string }) {
  return <Suspense><Inner back={back} /></Suspense>;
}
