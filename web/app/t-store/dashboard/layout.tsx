import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.sales", icon: "dashboard" },
  { href: "/dashboard/orders", label: "nav.orders", group: "navgrp.sales", screen: "orders", icon: "box" },
  { href: "/dashboard/carts", label: "nav.carts", group: "navgrp.sales", perm: ["cart.read", "cart.manage"], icon: "bag" },
  { href: "/dashboard/pos", label: "nav.pos", group: "navgrp.sales", perm: "pos.use", icon: "bag" },
  { href: "/dashboard/invoices", label: "nav.invoices", group: "navgrp.sales", perm: "invoice.manage", icon: "file" },
  { href: "/dashboard/reports", label: "nav.reports", group: "navgrp.sales", perm: "report.read", icon: "trending" },
  { href: "/dashboard/purchasing", label: "nav.purchasing", group: "navgrp.catalog", perm: "purchase.manage", icon: "inventory" },
  { href: "/dashboard/content", label: "nav.content", group: "navgrp.setup", perm: "blog.manage", icon: "file" },
  { href: "/dashboard/developers", label: "nav.developers", group: "navgrp.setup", perm: "api.manage", icon: "link" },
  { href: "/dashboard/returns", label: "nav.returns", group: "navgrp.sales", screen: "orders", icon: "file" },
  { href: "/dashboard/customers", label: "nav.customers", group: "navgrp.sales", screen: "customers", icon: "users" },
  { href: "/dashboard/channels", label: "nav.channels", group: "navgrp.sales", screen: "integrations", icon: "link" },
  { href: "/dashboard/shipping", label: "nav.shipping", group: "navgrp.sales", screen: "shipping", icon: "navigate" },
  { href: "/dashboard/products", label: "nav.products", group: "navgrp.catalog", screen: "products", icon: "bag" },
  { href: "/dashboard/inventory", label: "nav.inventory", group: "navgrp.catalog", screen: "inventory", icon: "inventory" },
  { href: "/dashboard/coupons", label: "nav.coupons", group: "navgrp.catalog", screen: "coupons", icon: "coupon" },
  { href: "/dashboard/reviews", label: "nav.reviews", group: "navgrp.catalog", screen: "reviews", icon: "star" },
  { href: "/dashboard/copilot", label: "nav.copilot", group: "navgrp.setup", perm: "copilot.use", icon: "sparkles" },
  { href: "/dashboard/growth", label: "nav.growth", group: "navgrp.setup", perm: "growth.manage", icon: "users" },
  { href: "/dashboard/marketing", label: "nav.marketing", group: "navgrp.setup", screen: "marketing", icon: "trending" },
  { href: "/dashboard/ai", label: "nav.ai", group: "navgrp.setup", screen: "ai", icon: "sparkles" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", screen: "settings", icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.setup", screen: "domains", icon: "globe" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", screen: "billing", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell" },
];

export default function StoreDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
