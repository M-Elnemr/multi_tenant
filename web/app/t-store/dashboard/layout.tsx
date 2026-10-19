import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.sales", icon: "dashboard" },
  { href: "/dashboard/orders", label: "nav.orders", group: "navgrp.sales", screen: "orders", icon: "box" },
  { href: "/dashboard/returns", label: "nav.returns", group: "navgrp.sales", screen: "orders", icon: "file" },
  { href: "/dashboard/customers", label: "nav.customers", group: "navgrp.sales", screen: "customers", icon: "users" },
  { href: "/dashboard/products", label: "nav.products", group: "navgrp.catalog", screen: "products", icon: "bag" },
  { href: "/dashboard/inventory", label: "nav.inventory", group: "navgrp.catalog", screen: "inventory", icon: "inventory" },
  { href: "/dashboard/coupons", label: "nav.coupons", group: "navgrp.catalog", screen: "coupons", icon: "coupon" },
  { href: "/dashboard/reviews", label: "nav.reviews", group: "navgrp.catalog", screen: "reviews", icon: "star" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", screen: "settings", icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.setup", screen: "domains", icon: "globe" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", screen: "billing", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell" },
];

export default function StoreDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
