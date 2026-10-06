import { backendFetch } from "./backend";

export type Branding = { primary_color?: string; secondary_color?: string; font_family?: string | null };

export type HostInfo =
  | { kind: "PLATFORM"; rootDomain: string }
  | { kind: "UNKNOWN" }
  | {
      kind: "TENANT";
      id: string;
      slug: string;
      name: string;
      type: "STORE" | "CLINIC";
      status: string;
      locale: string;
      currency: string;
      timezone: string;
      host: string;
      branding: Branding;
    };

const cache = new Map<string, { at: number; info: HostInfo }>();
const TTL_MS = 30_000;

/** Host -> what is behind it. Short in-memory cache so every page view does not hit the backend. */
export async function resolveHost(host: string): Promise<HostInfo> {
  const key = host.toLowerCase();
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < TTL_MS) return hit.info;
  let info: HostInfo = { kind: "UNKNOWN" };
  try {
    const res = await backendFetch("/tenant/resolve", { host });
    if (res.ok) info = (await res.json()) as HostInfo;
  } catch {
    // backend down: treat as unknown for now, do not cache the failure
    return info;
  }
  if (cache.size > 5000) cache.clear();
  cache.set(key, { at: Date.now(), info });
  return info;
}
