import type { Metadata } from "next";
import { CheckoutView } from "./checkout-view";
import { getT } from "@/lib/i18n-server";

export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.checkout.title") }; }
export default function CheckoutPage() { return <CheckoutView />; }
