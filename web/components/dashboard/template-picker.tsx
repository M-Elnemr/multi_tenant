"use client";

import { TEMPLATE_IDS } from "../shop/templates/ids";
import { LOOKS } from "../shop/templates/registry";
import { TemplateGallery } from "./template-gallery";

/** Settings > Look of the store: the template gallery with live previews of the shop's own products in every design. */
export default function TemplatePicker() {
  return <TemplateGallery ids={TEMPLATE_IDS} looks={LOOKS} profilePath="store/profile" prefix="tpl" fallback="original" categoryKey="galcat" />;
}
