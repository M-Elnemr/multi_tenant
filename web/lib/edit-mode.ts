import { backendAsUser } from "./backend";

/** True when the owner (or a manager) opened the public site with ?edit=1 while signed in: sections then get their edit toolbar. */
export async function ownerEditMode(searchParams: Promise<Record<string, string | string[] | undefined>> | undefined): Promise<boolean> {
  const sp = searchParams ? await searchParams : {};
  if (sp.edit !== "1") return false;
  try {
    const me = await backendAsUser<{ permissions: string[] }>("/auth/me");
    return me.permissions.includes("settings.manage");
  } catch {
    return false;
  }
}
