import Link from "next/link";
import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { AccountActions } from "./account-actions";
import { BackendError } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { localPhone } from "@/lib/phone";
import { openJson, openToken } from "@/lib/open";
import { Icon } from "@/components/icons";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("nav.account") }; }

export default async function AccountPage() {
  const { t } = await getT();
  if (!(await openToken())) redirect("/login?next=/account");
  const me = await openJson<{ id: string; name: string; phone?: string | null }>("/me").catch((e) => { if (e instanceof BackendError && e.status === 401) return null; throw e; });
  if (!me) redirect("/login?next=/account");
  const links = [
    { href: "/orders", icon: "box", label: t("open.myOrders"), sub: t("open.account.ordersSub") },
    { href: "/wishlist", icon: "heart", label: t("open.wishlist"), sub: t("open.account.wishlistSub") },
    { href: "/shops", icon: "bag", label: t("open.allShops"), sub: t("open.account.shopsSub") },
  ] as const;
  return (
    <div className="o-container max-w-2xl pt-6 md:pt-10">
      <div className="o-card o-reveal flex items-center gap-4 p-5">
        <span className="grid h-16 w-16 place-items-center rounded-full bg-gradient-to-br from-[var(--o-accent)] to-[var(--o-accent-2)] text-2xl font-black text-white">{me.name.slice(0, 1)}</span>
        <div className="min-w-0"><h1 className="truncate text-xl font-black">{me.name}</h1><p className="o-mute text-sm" dir="ltr">{me.phone ? localPhone(me.phone) : t("open.account.noPhone")}</p></div>
      </div>
      <ul className="mt-4 grid gap-3">
        {links.map((l) => (
          <li key={l.href}><Link href={l.href} className="o-card flex items-center gap-4 p-4 transition hover:shadow-[var(--o-shadow-lg)]"><span className="grid h-11 w-11 place-items-center rounded-2xl bg-[var(--o-soft)] text-[var(--o-accent)]"><Icon name={l.icon} className="h-5 w-5" /></span><span className="flex-1"><b className="block">{l.label}</b><span className="o-mute text-xs">{l.sub}</span></span><Icon name="arrow" className="h-4 w-4 text-[var(--o-mute)] rtl:rotate-180" /></Link></li>
        ))}
      </ul>
      <AccountActions name={me.name} phone={localPhone(me.phone)} />
    </div>
  );
}
