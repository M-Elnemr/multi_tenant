"use client";

import { TemplateGallery } from "@/components/dashboard/template-gallery";
import { CLINIC_LOOKS, CLINIC_TEMPLATE_IDS } from "@/components/clinic/templates";
import { useNavTitle } from "@/components/dashboard/section";
import { PageHeader } from "@/components/ui";
import { useT } from "@/components/i18n-provider";

/** Website design for the clinic: six designs, previewed live with the clinic's own doctors, services and photos. */
export default function ClinicWebsiteDesign() {
  const t = useT();
  const title = useNavTitle(t("nav.websiteDesign"));
  return (
    <div>
      <PageHeader title={title} subtitle={t("gal.clinicHint")} />
      <TemplateGallery ids={CLINIC_TEMPLATE_IDS} looks={CLINIC_LOOKS} profilePath="clinic/profile" prefix="ctpl" fallback="clear" categoryKey="galcat" />
    </div>
  );
}
