"use client";

import { use } from "react";
import { useRouter } from "next/navigation";
import { OfferForm, type Offer } from "@/components/dashboard/offer-form";
import { BackLink } from "@/components/dashboard/product-editor-bits";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Loading, PageHeader, StatusBadge } from "@/components/ui";

export default function EditOffer({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t } = useI18n();
  const router = useRouter();
  const { data, error, loading } = useApi<Offer>(`store/offers/${id}`);
  if (loading && !data) return <Loading variant="detail" />;
  if (!data) return <ErrorText error={error} />;
  return (
    <>
      <PageHeader eyebrow={<BackLink href="/dashboard/offers" label={t("nav.offers")} />} title={data.name} subtitle={t(`offer.type.${data.type}`)} badge={<StatusBadge status={data.state} />} />
      <OfferForm key={data.id} type={data.type} initial={data} onSaved={() => router.replace("/dashboard/offers")} />
    </>
  );
}
