"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useApi } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { LogoutButton } from "@/components/logout-button";
import { Badge, Card, Empty, ErrorText, Loading, PageHeader } from "@/components/ui";

type Mine = { id: string; slug: string; name: string; type: "STORE" | "CLINIC"; host?: string; roles?: string };

/** One login, every doctor and store you belong to. Each opens on its own address with its own separate data. */
export default function MyPlaces() {
  const t = useT();
  const { data, loading, error } = useApi<Mine[]>("me/tenants");
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  // One sign-in: ask for a one-time ticket for this place, then carry it to the place's own address, which signs us in there.
  const open = async (m: Mine) => {
    setBusy(m.id);
    setProblem(null);
    const port = window.location.port ? `:${window.location.port}` : "";
    const roles = (m.roles ?? "").split(",");
    const staff = roles.some((r) => r && !["PATIENT", "CUSTOMER", "GUARDIAN"].includes(r));
    const path = staff ? "/dashboard" : m.type === "CLINIC" ? "/portal" : "/account";
    try {
      const r = await api<{ ticket: string }>("auth/handoff", { body: { tenantId: m.id } });
      window.location.assign(`${window.location.protocol}//${m.host}${port}/api/sso?ticket=${encodeURIComponent(r.ticket)}&next=${encodeURIComponent(path)}`);
    } catch {
      setProblem(m.id);
      setBusy(null);
    }
  };
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <PageHeader title={t("myplaces.title")} subtitle={t("myplaces.hint")} actions={<LogoutButton />} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.length === 0 ? <Empty>{t("myplaces.none")}</Empty> : (
        <div className="grid gap-4 sm:grid-cols-2">
          {data?.filter((m) => m.host).map((m) => (
            <button key={m.id} type="button" onClick={() => void open(m)} disabled={busy !== null} className="text-start">
              <Card className="h-full transition hover:shadow-md">
                <Badge tone={m.type === "CLINIC" ? "blue" : "green"}>{m.type === "CLINIC" ? t("type.clinic") : t("type.store")}</Badge>
                <p className="mt-2 text-lg font-semibold">{m.name}</p>
                <p className="font-mono text-xs text-slate-500" dir="ltr">{m.host}</p>
                {busy === m.id && <p className="mt-2 text-sm text-slate-500">{t("myplaces.opening")}</p>}
                {problem === m.id && <p className="mt-2 text-sm text-red-600">{t("myplaces.failed")}</p>}
              </Card>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
