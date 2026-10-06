import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", icon: "📊" },
  { href: "/dashboard/orders", label: "nav.orders", perm: "order.read", icon: "📦" },
  { href: "/dashboard/products", label: "nav.products", perm: ["product.update", "product.create"], icon: "🛍️" },
  { href: "/dashboard/inventory", label: "nav.inventory", perm: "inventory.adjust", icon: "🏬" },
  { href: "/dashboard/categories", label: "nav.categories", perm: "category.manage", icon: "🗂️" },
  { href: "/dashboard/customers", label: "nav.customers", perm: "customer.read", icon: "👥" },
  { href: "/dashboard/coupons", label: "nav.coupons", perm: "coupon.manage", icon: "🏷️" },
  { href: "/dashboard/reviews", label: "nav.reviews", perm: "review.moderate", icon: "⭐" },
  { href: "/dashboard/settings", label: "nav.settings", perm: ["settings.manage", "shipping.manage"], icon: "⚙️" },
  { href: "/dashboard/domains", label: "nav.domains", perm: "domain.manage", icon: "🌐" },
  { href: "/dashboard/staff", label: "nav.staff", perm: "staff.manage", icon: "🧑‍💼" },
  { href: "/dashboard/billing", label: "nav.billing", perm: "billing.read", icon: "💳" },
  { href: "/dashboard/notifications", label: "nav.notifications", icon: "🔔" },
];

export default function StoreDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
