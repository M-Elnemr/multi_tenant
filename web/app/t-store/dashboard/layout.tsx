import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

/**
 * The shop's side menu. Flat: every screen part has its own item (`?tab=`), so nobody hunts for tabs inside a screen. The everyday work comes first in plain groups
 * (selling, customers, products, marketing, reports, the AI seller, setup); what needs experience (purchasing, marketplaces, till setup, growth tools, deeper reports,
 * technical settings) sits in the folded «Advanced» section. Order inside a group = how often a shop uses it.
 */
const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", icon: "dashboard", quick: true },

  // Selling: the counter and the orders of the day
  { href: "/dashboard/orders", label: "nav.orders", group: "navgrp.sell", screen: "orders", icon: "box", quick: true },
  { href: "/dashboard/pos", label: "nav.pos", group: "navgrp.sell", perm: "pos.use", icon: "cart", quick: true },
  { href: "/dashboard/pos?tab=shifts", label: "nav.shifts", group: "navgrp.sell", perm: "pos.manage", icon: "wallet" },
  { href: "/dashboard/returns", label: "nav.returns", group: "navgrp.sell", screen: "orders", icon: "return" },
  { href: "/dashboard/shipping?tab=shipments", label: "nav.shipments", group: "navgrp.sell", screen: "shipping", icon: "navigate" },
  { href: "/dashboard/invoices", label: "nav.invoices", group: "navgrp.sell", perm: "invoice.manage", icon: "receipt" },

  // Customers and messages
  { href: "/dashboard/conversations", label: "nav.inbox", group: "navgrp.service", screen: "ai", icon: "chat" },
  { href: "/dashboard/customers", label: "nav.customers", group: "navgrp.service", screen: "customers", icon: "users" },
  { href: "/dashboard/questions", label: "nav.questions", group: "navgrp.service", screen: "ai", icon: "mail" },
  { href: "/dashboard/reviews", label: "nav.reviews", group: "navgrp.service", screen: "reviews", icon: "star" },

  // Products and stock
  { href: "/dashboard/products", label: "nav.products", group: "navgrp.stock", screen: "products", icon: "bag", quick: true },
  { href: "/dashboard/inventory?tab=stock", label: "nav.inventory", group: "navgrp.stock", screen: "inventory", icon: "inventory" },
  { href: "/dashboard/pos?tab=counts", label: "nav.stockCount", group: "navgrp.stock", perm: ["inventory.adjust", "pos.manage"], icon: "check" },
  { href: "/dashboard/products/labels", label: "nav.labels", group: "navgrp.stock", perm: ["pos.use", "product.update"], icon: "print" },
  { href: "/dashboard/offers", label: "nav.offers", group: "navgrp.stock", screen: "coupons", icon: "coupon" },

  // Marketing
  { href: "/dashboard/marketing?tab=posts", label: "nav.posts", group: "navgrp.marketing", screen: "marketing", icon: "trending" },
  { href: "/dashboard/carts", label: "nav.carts", group: "navgrp.marketing", perm: ["cart.read", "cart.manage"], icon: "bag" },

  // Reports
  { href: "/dashboard/reports?tab=overview", label: "nav.reports", group: "navgrp.reports", perm: "report.read", icon: "chart" },
  { href: "/dashboard/reports?tab=sales", label: "nav.salesReport", group: "navgrp.reports", perm: "report.read", icon: "trending" },
  { href: "/dashboard/reports?tab=products", label: "nav.profitReport", group: "navgrp.reports", perm: "report.read", icon: "wallet" },
  { href: "/dashboard/copilot", label: "nav.copilot", group: "navgrp.reports", perm: "copilot.use", icon: "sparkles" },

  // The AI seller
  { href: "/dashboard/ai?tab=test", label: "nav.aiTest", group: "navgrp.ai", screen: "ai", write: true, icon: "sparkles" },
  { href: "/dashboard/ai?tab=lessons", label: "nav.aiTeach", group: "navgrp.ai", screen: "ai", icon: "award" },

  // Setup
  { href: "/dashboard/settings?tab=profile", label: "nav.shopDetails", group: "navgrp.setup", screen: "settings", icon: "home" },
  { href: "/dashboard/settings?tab=look", label: "nav.look", group: "navgrp.setup", screen: "settings", icon: "edit" },
  { href: "/dashboard/settings?tab=sales", label: "nav.payDelivery", group: "navgrp.setup", screen: "settings", icon: "billing" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.setup", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.setup", screen: "billing", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.setup", icon: "bell" },

  // Advanced: the till's setup
  { href: "/dashboard/pos?tab=terminals", label: "nav.terminals", group: "navgrp.advPos", perm: "pos.manage", icon: "cart", advanced: true },
  { href: "/dashboard/pos?tab=cashiers", label: "nav.cashiers", group: "navgrp.advPos", perm: "pos.manage", icon: "users", advanced: true },
  { href: "/dashboard/pos?tab=issues", label: "nav.syncIssues", group: "navgrp.advPos", perm: "pos.manage", icon: "alert", advanced: true },
  { href: "/dashboard/pos?tab=settings", label: "nav.posSettings", group: "navgrp.advPos", perm: "pos.manage", icon: "settings", advanced: true },
  // Advanced: buying and moving stock
  { href: "/dashboard/purchasing?tab=orders", label: "nav.purchaseOrders", group: "navgrp.advStock", perm: "purchase.manage", icon: "file", advanced: true },
  { href: "/dashboard/purchasing?tab=suppliers", label: "nav.suppliers", group: "navgrp.advStock", perm: "purchase.manage", icon: "briefcase", advanced: true },
  { href: "/dashboard/purchasing?tab=transfers", label: "nav.transfers", group: "navgrp.advStock", perm: "purchase.manage", icon: "navigate", advanced: true },
  { href: "/dashboard/inventory?tab=moves", label: "nav.stockMoves", group: "navgrp.advStock", screen: "inventory", icon: "clock", advanced: true },
  // Advanced: marketing and growth tools
  { href: "/dashboard/marketing?tab=plan", label: "nav.contentPlan", group: "navgrp.advMarketing", screen: "marketing", icon: "calendar", advanced: true },
  { href: "/dashboard/marketing?tab=ads", label: "nav.ads", group: "navgrp.advMarketing", screen: "marketing", icon: "trending", advanced: true },
  { href: "/dashboard/marketing?tab=auto", label: "nav.autopilot", group: "navgrp.advMarketing", screen: "marketing", icon: "sparkles", advanced: true },
  { href: "/dashboard/growth?tab=segments", label: "nav.segments", group: "navgrp.advMarketing", perm: "growth.manage", icon: "users", advanced: true },
  { href: "/dashboard/growth?tab=loyalty", label: "nav.loyalty", group: "navgrp.advMarketing", perm: "growth.manage", icon: "award", advanced: true },
  { href: "/dashboard/growth?tab=feeds", label: "nav.feeds", group: "navgrp.advMarketing", perm: "growth.manage", icon: "link", advanced: true },
  { href: "/dashboard/growth?tab=exp", label: "nav.experiments", group: "navgrp.advMarketing", perm: "growth.manage", icon: "chart", advanced: true },
  { href: "/dashboard/growth?tab=builder", label: "nav.aiBuilder", group: "navgrp.advMarketing", perm: "growth.manage", icon: "sparkles", advanced: true },
  // Advanced: marketplaces and other stores
  { href: "/dashboard/channels?tab=insights", label: "nav.marketplaces", group: "navgrp.advChannels", screen: "integrations", icon: "globe", advanced: true },
  { href: "/dashboard/channels?tab=orders", label: "nav.mpOrders", group: "navgrp.advChannels", screen: "integrations", icon: "box", advanced: true },
  { href: "/dashboard/channels?tab=products", label: "nav.mpProducts", group: "navgrp.advChannels", screen: "integrations", icon: "bag", advanced: true },
  { href: "/dashboard/channels?tab=customers", label: "nav.mpCustomers", group: "navgrp.advChannels", screen: "integrations", icon: "users", advanced: true },
  { href: "/dashboard/channels?tab=platforms", label: "nav.mpPlatforms", group: "navgrp.advChannels", screen: "integrations", icon: "link", advanced: true },
  // Advanced: the AI and WhatsApp connections
  { href: "/dashboard/ai?tab=channels", label: "nav.aiChannels", group: "navgrp.advAi", screen: "ai", icon: "link", advanced: true },
  { href: "/dashboard/ai?tab=whatsapp", label: "nav.aiWhatsapp", group: "navgrp.advAi", screen: "ai", icon: "phone", advanced: true },
  { href: "/dashboard/settings?tab=whatsapp", label: "nav.ownWhatsapp", group: "navgrp.advAi", perm: ["ai.read", "ai.manage"], icon: "phone", advanced: true },
  { href: "/dashboard/ai?tab=settings", label: "nav.aiSettings", group: "navgrp.advAi", screen: "ai", icon: "settings", advanced: true },
  // Advanced: deeper reports
  { href: "/dashboard/reports?tab=customers", label: "nav.customerReport", group: "navgrp.advReports", perm: "report.read", icon: "users", advanced: true },
  { href: "/dashboard/reports?tab=operations", label: "nav.opsReport", group: "navgrp.advReports", perm: "report.read", icon: "chart", advanced: true },
  // Advanced: technical setup
  { href: "/dashboard/settings?tab=marketing", label: "nav.trackingSettings", group: "navgrp.advSetup", screen: "settings", icon: "trending", advanced: true },
  { href: "/dashboard/settings?tab=branches", label: "nav.branches", group: "navgrp.advSetup", perm: "branch.manage", icon: "pin", advanced: true },
  { href: "/dashboard/shipping?tab=carriers", label: "nav.carriers", group: "navgrp.advSetup", screen: "shipping", icon: "navigate", advanced: true },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.advSetup", screen: "domains", icon: "globe", advanced: true },
  { href: "/dashboard/developers", label: "nav.developers", group: "navgrp.advSetup", perm: "api.manage", icon: "link", advanced: true },
];

export default function StoreDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
