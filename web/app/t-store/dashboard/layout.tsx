import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", icon: "dashboard" },
  { href: "/dashboard/orders", label: "nav.orders", perm: "order.read", icon: "box" },
  { href: "/dashboard/products", label: "nav.products", perm: ["product.update", "product.create"], icon: "bag" },
  { href: "/dashboard/inventory", label: "nav.inventory", perm: "inventory.adjust", icon: "inventory" },
  { href: "/dashboard/categories", label: "nav.categories", perm: "category.manage", icon: "tag" },
  { href: "/dashboard/customers", label: "nav.customers", perm: "customer.read", icon: "users" },
  { href: "/dashboard/coupons", label: "nav.coupons", perm: "coupon.manage", icon: "coupon" },
  { href: "/dashboard/reviews", label: "nav.reviews", perm: "review.moderate", icon: "star" },
  { href: "/dashboard/settings", label: "nav.settings", perm: ["settings.manage", "shipping.manage"], icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", perm: "domain.manage", icon: "globe" },
  { href: "/dashboard/staff", label: "nav.staff", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", perm: "billing.read", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", icon: "bell" },
];

export default function StoreDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
