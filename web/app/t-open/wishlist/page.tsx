import type { Metadata } from "next";
import { WishlistView } from "./wishlist-view";
import { getT } from "@/lib/i18n-server";

export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.wishlist") }; }
export default function WishlistPage() { return <WishlistView />; }
