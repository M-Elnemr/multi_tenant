import { cache } from "react";
import { backendJson } from "@/lib/backend";
import { siteTemplate } from "@/lib/site-template";
import { TEMPLATE_IDS, type TemplateId } from "@/components/shop/templates/ids";
import { lookOf, type Look } from "@/components/shop/templates/registry";
import type { ShopProfile } from "@/components/shop/types";

/** The shop's profile and the template this request renders with (saved, or previewed with ?tpl=). Cached per request, so layout and pages share one call. */
export const shopLook = cache(async (): Promise<{ profile: ShopProfile | null; template: TemplateId; look: Look; previewing: boolean; inFrame: boolean }> => {
  const res = await backendJson<{ profile: ShopProfile }>("/shop/profile").catch(() => null);
  const profile = res?.profile ?? null;
  const { id, previewing, inFrame } = await siteTemplate(profile?.template, TEMPLATE_IDS, "original");
  return { profile, template: id, look: lookOf(id), previewing, inFrame };
});
