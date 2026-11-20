"use client";

import { TEMPLATE_IDS } from "../shop/templates/ids";
import { LOOKS } from "../shop/templates/registry";
import { SERVICE_LOOKS, SERVICE_TEMPLATE_IDS } from "../shop/templates/service-looks";
import { useI18n } from "../i18n-provider";
import { TemplateGallery } from "./template-gallery";

/** Settings > Look of the store: the template gallery with live previews of the shop's own products in every design. */
export default function TemplatePicker() {
  const { tenantType } = useI18n();
  if (tenantType === "SERVICE") return <TemplateGallery ids={SERVICE_TEMPLATE_IDS} looks={SERVICE_LOOKS} profilePath="store/profile" prefix="tpl" fallback="lumiere" categoryKey="galcat" />;
  return <TemplateGallery ids={TEMPLATE_IDS} looks={LOOKS} profilePath="store/profile" prefix="tpl" fallback="original" categoryKey="galcat" />;
}
