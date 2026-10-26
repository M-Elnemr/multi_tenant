import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.home", icon: "dashboard" },
  { href: "/dashboard/orders", label: "nav.orders", group: "navgrp.sales", screen: "orders", icon: "box" },
  { href: "/dashboard/pos", label: "nav.pos", group: "navgrp.sales", perm: "pos.use", icon: "bag" },
  { href: "/dashboard/carts", label: "nav.carts", group: "navgrp.sales", perm: ["cart.read", "cart.manage"], icon: "bag" },
  { href: "/dashboard/returns", label: "nav.returns", group: "navgrp.sales", screen: "orders", icon: "file" },
  { href: "/dashboard/invoices", label: "nav.invoices", group: "navgrp.sales", perm: "invoice.manage", icon: "file" },
  { href: "/dashboard/products", label: "nav.products", group: "navgrp.catalog", screen: "products", icon: "bag" },
  { href: "/dashboard/inventory", label: "nav.inventory", group: "navgrp.catalog", screen: "inventory", icon: "inventory" },
  { href: "/dashboard/purchasing", label: "nav.purchasing", group: "navgrp.catalog", perm: "purchase.manage", icon: "inventory" },
  { href: "/dashboard/coupons", label: "nav.coupons", group: "navgrp.catalog", screen: "coupons", icon: "coupon" },
  { href: "/dashboard/reviews", label: "nav.reviews", group: "navgrp.catalog", screen: "reviews", icon: "star" },
  { href: "/dashboard/customers", label: "nav.customers", group: "navgrp.growth", screen: "customers", icon: "users" },
  { href: "/dashboard/growth", label: "nav.growth", group: "navgrp.growth", perm: "growth.manage", icon: "users" },
  { href: "/dashboard/marketing", label: "nav.marketing", group: "navgrp.growth", screen: "marketing", icon: "trending" },
  { href: "/dashboard/reports", label: "nav.reports", group: "navgrp.insights", perm: "report.read", icon: "trending" },
  { href: "/dashboard/copilot", label: "nav.copilot", group: "navgrp.insights", perm: "copilot.use", icon: "sparkles" },
  { href: "/dashboard/channels", label: "nav.channels", group: "navgrp.channels", screen: "integrations", icon: "link" },
  { href: "/dashboard/ai", label: "nav.ai", group: "navgrp.channels", screen: "ai", icon: "sparkles" },
  { href: "/dashboard/questions", label: "nav.questions", group: "navgrp.channels", screen: "ai", icon: "chat" },
  { href: "/dashboard/shipping", label: "nav.shipping", group: "navgrp.channels", screen: "shipping", icon: "navigate" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", screen: "settings", icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.setup", screen: "domains", icon: "globe" },
  { href: "/dashboard/developers", label: "nav.developers", group: "navgrp.setup", perm: "api.manage", icon: "link" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", screen: "billing", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell" },
];

export default function StoreDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
