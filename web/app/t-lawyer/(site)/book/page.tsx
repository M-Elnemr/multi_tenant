import { Suspense } from "react";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import type { LawyerSite } from "../types";
import { BookFlow } from "./book-flow";

export const dynamic = "force-dynamic";

export default async function Book() {
  const { t } = await getT();
  const p = await backendJson<LawyerSite>("/lawyer/public/profile").catch(() => null);
  if (!p) return null;
  return (
    <section className="lw-section">
      <div className="lw-container max-w-4xl">
        <p className="lw-eyebrow">{t("lws.bookEyebrow")}</p>
        <h1 className="lw-title mt-3">{t("lws.bookTitle", { name: `${p.title ? p.title + " " : ""}${p.displayName}` })}</h1>
        <p className="mt-2" style={{ color: "var(--lw-mute)" }}>{t("lws.bookSub")}</p>
        {p.bookingEnabled === false ? <div className="lw-card mt-8 p-8 text-center">{t("lws.bookingClosed")} {p.phone && <a href={`tel:${p.phone}`} className="font-bold underline" dir="ltr">{p.phone}</a>}</div>
          : <Suspense><BookFlow services={p.services} /></Suspense>}
      </div>
    </section>
  );
}
