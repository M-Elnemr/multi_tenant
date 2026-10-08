"use client";

import Link from "next/link";
import { useMe } from "./hooks";
import { useT } from "./i18n-provider";

export function ClinicAccountLink({ portal = true }: { portal?: boolean }) {
  const t = useT();
  const { me, ready } = useMe();
  if (!ready) return <span className="w-16" />;
  if (!me) return <Link href="/login" className="rounded-lg px-2 py-1.5 text-sm hover:bg-slate-100">{portal ? t("nav.login") : t("nav.staffLogin")}</Link>;
  const patient = me.roles.includes("PATIENT");
  return <Link href={patient ? "/portal" : "/dashboard"} className="rounded-lg bg-slate-900 px-3 py-1.5 text-sm text-white">{patient ? t("nav.portal") : t("nav.dashboard")}</Link>;
}
