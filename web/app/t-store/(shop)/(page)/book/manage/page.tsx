import { notFound } from "next/navigation";
import { shopLook } from "@/lib/shop-look";
import { vnDesign } from "@/components/shop/service/vn-registry";

/** Customers look up a booking by its number and phone, and can cancel it. Only for the vn-* designs. */
export default async function ManageBooking() {
  const { template, service } = await shopLook();
  const vn = service ? vnDesign(template) : undefined;
  if (!vn) notFound();
  return <vn.Manage />;
}
