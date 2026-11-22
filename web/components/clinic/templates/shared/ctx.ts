import { backendJson, currentHost } from "@/lib/backend";
import { getLocale } from "@/lib/i18n-server";
import { cnDict, cnT } from "./dict";
import { resolveHost } from "@/lib/tenant";
import { siteTemplate } from "@/lib/site-template";
import { CLINIC_TEMPLATE_IDS } from "@/components/clinic/templates";
import { mainPhone } from "./data";
import { CN_IDS, isCnId } from "./ids";
import type { CnProfile, Ctx } from "./kit";

/** Server side: the clinic's public data plus the resolved template. Returns null when the site renders with one of the original (non-cn) templates. */
export async function cnCtx(): Promise<Ctx | null> {
  const p = await backendJson<CnProfile>("/clinic/public/profile").catch(() => null);
  if (!p) return null;
  const tpl = await siteTemplate(p.template, [...CLINIC_TEMPLATE_IDS, ...CN_IDS], "clear");
  if (!isCnId(tpl.id)) return null;
  const locale = await getLocale();
  const t = cnT(locale);
  const info = await resolveHost(await currentHost());
  const logo = info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id ?? null : null;
  const name = p.clinicName || (info.kind === "TENANT" ? info.name : "");
  return { id: tpl.id, p, t, dict: cnDict(locale), locale, logo, name, book: p.bookingEnabled ? "/book" : undefined, phone: mainPhone(p), portal: p.patientPortalEnabled === true, inFrame: tpl.inFrame, previewing: tpl.previewing, whiteLabel: info.kind === "TENANT" && !!info.whiteLabel };
}
