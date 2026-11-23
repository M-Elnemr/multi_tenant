import { shopLook } from "@/lib/shop-look";
import { vnDesign } from "@/components/shop/service/vn-registry";

/** Inner pages sit in a centred container; the home page (one level up) is full-bleed. A vn-* or large-catalog design lays out its own pages, so those pages get no frame. */
export default async function InnerLayout({ children }: { children: React.ReactNode }) {
  const { template, service, large } = await shopLook();
  if (large) return <div className="lg-inner">{children}</div>;   // DR-C
  if (service && vnDesign(template)) return <div className="animate-fade-up">{children}</div>;   // DR-E
  return <div className="s-container animate-fade-up py-8 sm:py-10">{children}</div>;
}
