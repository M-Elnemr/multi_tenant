import { notFound } from "next/navigation";
import type { ShopProfile } from "@/components/shop/types";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";

const FIELDS: Record<string, keyof ShopProfile> = { shipping: "shippingPolicy", returns: "returnPolicy", privacy: "privacyPolicy", terms: "termsText", about: "about" };

/** Shipping, returns, privacy, terms and about pages: the owner's own text, with a sensible default for the return policy (Egypt: 14 days). */
export default async function PolicyPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const field = FIELDS[slug];
  if (!field) notFound();
  const { t } = await getT();
  const p = (await backendJson<{ profile: ShopProfile }>("/shop/profile").catch(() => null))?.profile;
  if (!p) notFound();
  const text = (p[field] as string | undefined)?.trim();
  const fallback = slug === "returns" ? t("policy.returnsDefault", { n: p.returnWindowDays ?? 14 }) : slug === "shipping" ? t("policy.shippingDefault") : "";
  const body = text || fallback;
  return (
    <article className="mx-auto max-w-3xl">
      <p className="s-eyebrow">{p.storeName}</p>
      <h1 className="mt-1 text-3xl font-extrabold sm:text-4xl">{t(slug === "about" ? "settings.about" : `policy.${slug}`)}</h1>
      <div className="s-card mt-8 p-7 sm:p-10">{body ? <p className="whitespace-pre-line leading-loose">{body}</p> : <p className="text-[var(--s-mute)]">{t("policy.empty")}</p>}</div>
    </article>
  );
}
