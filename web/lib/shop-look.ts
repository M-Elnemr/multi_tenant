import { cache } from "react";
import { backendJson } from "@/lib/backend";
import { siteTemplate } from "@/lib/site-template";
import { TEMPLATE_IDS, type TemplateId } from "@/components/shop/templates/ids";
import { lookOf, type Look } from "@/components/shop/templates/registry";
import { SERVICE_LOOKS, SERVICE_TEMPLATE_IDS, type ServiceLook } from "@/components/shop/templates/service-looks";
import { currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";
import type { ShopProfile } from "@/components/shop/types";

/** The shop's profile and the template this request renders with (saved, or previewed with ?tpl=). Cached per request, so layout and pages share one call. */
export const shopLook = cache(async (): Promise<{ profile: ShopProfile | null; template: string; look: Look; service: ServiceLook | null; previewing: boolean; inFrame: boolean }> => {
  const res = await backendJson<{ profile: ShopProfile }>("/shop/profile").catch(() => null);
  const profile = res?.profile ?? null;
  const info = await resolveHost(await currentHost());
  if (info.kind === "TENANT" && info.type === "SERVICE") {   // a service business has its own ten designs, never the shop ones
    const { id, previewing, inFrame } = await siteTemplate(profile?.template, SERVICE_TEMPLATE_IDS, "lumiere");
    const sv = SERVICE_LOOKS[id];
    return { profile, template: id, look: { ...lookOf("original"), header: sv.header, dark: sv.dark, categories: [], swatch: sv.swatch } as Look, service: sv, previewing, inFrame };
  }
  const { id, previewing, inFrame } = await siteTemplate(profile?.template, TEMPLATE_IDS, "original");
  return { profile, template: id, look: lookOf(id), service: null, previewing, inFrame };
});
