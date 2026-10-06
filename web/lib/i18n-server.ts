import { cookies } from "next/headers";
import { currentHost } from "./backend";
import { makeT, normalizeLocale, type Locale } from "./i18n";
import { resolveHost } from "./tenant";

/** Visitor's choice (cookie) wins; otherwise the tenant's default language; otherwise Arabic. */
export async function getLocale(): Promise<Locale> {
  const c = (await cookies()).get("lang")?.value;
  if (c) return normalizeLocale(c);
  const info = await resolveHost(await currentHost());
  return info.kind === "TENANT" ? normalizeLocale(info.locale) : "ar";
}

export async function getT() {
  const locale = await getLocale();
  return { t: makeT(locale), locale };
}
