export type ShopCategory = { id: string; parentId: string | null; name: string; slug: string; imageFileId?: string | null; productCount: number; sortOrder: number };
export type CategoryNode = ShopCategory & { children: CategoryNode[] };

export function buildTree(flat: ShopCategory[]): CategoryNode[] {
  const byId = new Map<string, CategoryNode>(flat.map((c) => [c.id, { ...c, children: [] }]));
  const roots: CategoryNode[] = [];
  for (const n of byId.values()) {
    const parent = n.parentId ? byId.get(n.parentId) : undefined;
    if (parent) parent.children.push(n); else roots.push(n);
  }
  return roots;
}

/** The chain from the top-level category down to `slug` (for breadcrumbs). */
export function pathTo(flat: ShopCategory[], slug: string): ShopCategory[] {
  const byId = new Map(flat.map((c) => [c.id, c]));
  const out: ShopCategory[] = [];
  let cur = flat.find((c) => c.slug === slug);
  let guard = 0;
  while (cur && guard++ < 12) { out.unshift(cur); cur = cur.parentId ? byId.get(cur.parentId) : undefined; }
  return out;
}

export type ShopProfile = {
  storeName: string; shortDescription?: string; about?: string; supportPhone?: string; supportEmail?: string; addressText?: string;
  whatsapp?: string; extraPhones?: string[]; facebookUrl?: string; instagramUrl?: string; tiktokUrl?: string; websiteUrl?: string; mapsUrl?: string;
  workingHours?: Record<string, { open: string; close: string; closed: boolean }>; isOpen?: boolean; closedMessage?: string; announcement?: string; coverFileId?: string | null;
  minOrderMinor?: number; vatIncluded?: boolean; returnWindowDays?: number; shippingPolicy?: string; returnPolicy?: string; privacyPolicy?: string; termsText?: string;
  branding?: { logoFileId?: string | null; primaryColor?: string; secondaryColor?: string };
};

export type ShopBranch = {
  id: string; name: string; code: string; phone?: string; whatsapp?: string; addressLine1?: string; city?: string; governorateCode?: string; area?: string; landmark?: string;
  mapsUrl?: string; workingHours?: Record<string, { open: string; close: string; closed: boolean }>; isPickup?: boolean;
};
