import { headers } from "next/headers";

/**
 * The template a site renders with: the one being previewed ("?tpl=", forwarded by proxy.ts as x-site-tpl) when it is a valid id for this kind of site,
 * otherwise the saved one. `preview` is true inside the template gallery's iframe: chat, pixels and the visit counter stay off there.
 */
export async function siteTemplate<T extends string>(saved: string | null | undefined, ids: readonly T[], fallback: T): Promise<{ id: T; previewing: boolean; inFrame: boolean }> {
  const h = await headers();
  const asked = h.get("x-site-tpl");
  const ok = (v: unknown): v is T => typeof v === "string" && (ids as readonly string[]).includes(v);
  const id = ok(asked) ? asked : ok(saved) ? saved : fallback;
  return { id, previewing: ok(asked) && asked !== saved, inFrame: h.get("x-site-preview") === "1" };
}
