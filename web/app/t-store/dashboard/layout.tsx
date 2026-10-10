import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.sales", icon: "dashboard" },
  { href: "/dashboard/orders", label: "nav.orders", group: "navgrp.sales", perm: "order.read", icon: "box" },
  { href: "/dashboard/returns", label: "nav.returns", group: "navgrp.sales", perm: "order.read", icon: "file" },
  { href: "/dashboard/customers", label: "nav.customers", group: "navgrp.sales", perm: "customer.read", icon: "users" },
  { href: "/dashboard/products", label: "nav.products", group: "navgrp.catalog", perm: ["product.update", "product.create"], icon: "bag" },
  { href: "/dashboard/inventory", label: "nav.inventory", group: "navgrp.catalog", perm: "inventory.adjust", icon: "inventory" },
  { href: "/dashboard/coupons", label: "nav.coupons", group: "navgrp.catalog", perm: "coupon.manage", icon: "coupon" },
  { href: "/dashboard/reviews", label: "nav.reviews", group: "navgrp.catalog", perm: "review.moderate", icon: "star" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", perm: ["settings.manage", "shipping.manage"], icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.setup", perm: "domain.manage", icon: "globe" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", perm: "billing.read", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell" },
];

export default function StoreDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
