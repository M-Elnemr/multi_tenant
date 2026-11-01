"use client";

import { use } from "react";
import { useRouter } from "next/navigation";
import { OfferForm, type Offer } from "@/components/dashboard/offer-form";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Loading, PageHeader, StatusBadge } from "@/components/ui";

export default function EditOffer({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t } = useI18n();
  const router = useRouter();
  const { data, error, loading } = useApi<Offer>(`store/offers/${id}`);
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  return (
    <>
      <PageHeader title={data.name} subtitle={t(`offer.type.${data.type}`)} actions={<StatusBadge status={data.state} />} />
      <OfferForm key={data.id} type={data.type} initial={data} onSaved={() => router.replace("/dashboard/offers")} />
    </>
  );
}
