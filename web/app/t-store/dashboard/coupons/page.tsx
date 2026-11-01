import { redirect } from "next/navigation";

/** Coupons now live in the offers screen (one more offer type). */
export default function Coupons() {
  redirect("/dashboard/offers?type=COUPON");
}
