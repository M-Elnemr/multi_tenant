import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { shopLook } from "@/lib/shop-look";
import { vnDesign } from "@/components/shop/service/vn-registry";
import { vnData } from "@/components/shop/service/vn-core/data";

export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: { params: Promise<{ id: string }> }): Promise<Metadata> {
  const { id } = await params;
  const s = (await vnData()).cfg.services.find((x) => x.id === id);
  return s ? { title: s.name, description: s.shortDescription || s.description || undefined } : {};
}

/** One service: photos, price, duration, place, options, who performs it, and a reserve button. */
export default async function ServiceDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const { template, service } = await shopLook();
  const vn = service ? vnDesign(template) : undefined;
  if (!vn) notFound();
  const d = await vnData();
  const s = d.cfg.services.find((x) => x.id === id);
  if (!s) notFound();
  return <vn.Detail {...d} service={s} />;
}
