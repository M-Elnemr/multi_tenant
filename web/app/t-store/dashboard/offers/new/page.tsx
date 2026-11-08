"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { OFFER_TYPES, OfferForm, TypePicker, type OfferType } from "@/components/dashboard/offer-form";
import { BackLink } from "@/components/dashboard/product-editor-bits";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function NewOfferPage() { return <Suspense fallback={null}><NewOffer /></Suspense>; }

function NewOffer() {
  const { t } = useI18n();
  const router = useRouter();
  const sp = useSearchParams();
  const type = sp.get("type") as OfferType | null;
  const valid = type && (OFFER_TYPES as readonly string[]).includes(type) ? type : null;
  return (
    <>
      <PageHeader eyebrow={<BackLink href="/dashboard/offers" label={t("nav.offers")} />} title={valid ? t("offer.newOf", { type: t(`offer.type.${valid}`) }) : t("offer.pickTitle")} subtitle={valid ? t("offer.formSub") : t("offer.pickSubtitle")} />
      {valid ? <OfferForm type={valid} onBack={() => router.replace("/dashboard/offers/new")} onSaved={() => router.replace("/dashboard/offers?created=1")} />
        : <TypePicker onPick={(ty) => router.push(`/dashboard/offers/new?type=${ty}`)} />}
    </>
  );
}
