"use client";

import { TEMPLATE_IDS } from "../shop/templates/ids";
import { LOOKS } from "../shop/templates/registry";
import { SERVICE_LOOKS, SERVICE_TEMPLATE_IDS } from "../shop/templates/service-looks";
import { SMALL_LOOKS, SMALL_TEMPLATE_IDS } from "../shop/templates/small-looks";
import { useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert } from "../ui";
import { TemplateGallery } from "./template-gallery";

type Options = { activeProducts: number; smallCatalogEligible: boolean; smallCatalogMax: number; currentIsSmallCatalog: boolean };

/** Settings > Look of the store: the template gallery with live previews of the business's own products in every design. A service business has its own ten designs; a shop with 1-3 active products also gets the ten small-catalog designs (the published one stays whatever the catalog size). */
export default function TemplatePicker() {
  const { t, tenantType } = useI18n();
  const opts = useApi<Options>(tenantType === "SERVICE" ? null : "store/template-options");
  if (tenantType === "SERVICE") return <TemplateGallery ids={SERVICE_TEMPLATE_IDS} looks={SERVICE_LOOKS} profilePath="store/profile" prefix="tpl" fallback="lumiere" categoryKey="galcat" />;
  const showSmall = !!opts.data && (opts.data.smallCatalogEligible || opts.data.currentIsSmallCatalog);
  const ids = showSmall ? [...SMALL_TEMPLATE_IDS, ...TEMPLATE_IDS] : [...TEMPLATE_IDS];
  const looks = { ...LOOKS, ...SMALL_LOOKS };
  return (
    <div className="space-y-4">
      {opts.data && !opts.data.smallCatalogEligible && <Alert tone="blue">{opts.data.currentIsSmallCatalog ? t("tpl.smallKept", { n: String(opts.data.smallCatalogMax) }) : t("tpl.smallHint", { n: String(opts.data.smallCatalogMax) })}</Alert>}
      <TemplateGallery ids={ids} looks={looks} profilePath="store/profile" prefix="tpl" fallback="original" categoryKey="galcat" />
    </div>
  );
}
