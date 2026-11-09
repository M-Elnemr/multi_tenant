import { DashboardShell, type NavItem, type RailSection } from "@/components/dashboard/shell";

/**
 * The shop's menu: an icon rail of sections (home, inbox, selling, catalog, customers, marketing, AI seller, reports, settings), each opening its items in the panel next
 * to it. Flat: every screen part has its own item (`?tab=`), so nobody hunts for tabs inside a screen. What needs experience sits folded under «Advanced» in its section;
 * the AI and WhatsApp items are never folded. Order inside a section = how often a shop uses it.
 */
const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", icon: "dashboard", quick: true, rail: "home" },

  // Selling: the counter and the orders of the day
  { href: "/dashboard/orders", label: "nav.orders", group: "navgrp.sell", screen: "orders", icon: "box", quick: true, rail: "sell" },
  { href: "/dashboard/pos", label: "nav.pos", group: "navgrp.sell", perm: "pos.use", icon: "cart", rail: "sell" },
  { href: "/dashboard/pos?tab=shifts", label: "nav.shifts", group: "navgrp.sell", perm: "pos.manage", icon: "wallet", rail: "sell" },
  { href: "/dashboard/returns", label: "nav.returns", group: "navgrp.sell", screen: "orders", icon: "return", rail: "sell" },
  { href: "/dashboard/shipping?tab=shipments", label: "nav.shipments", group: "navgrp.sell", screen: "shipping", icon: "navigate", rail: "sell" },
  { href: "/dashboard/invoices", label: "nav.invoices", group: "navgrp.sell", perm: "invoice.manage", icon: "receipt", rail: "sell" },

  // Core business: the AI replies on social and WhatsApp
  { href: "/dashboard/inbox", label: "nav.inbox", group: "navgrp.inbox", screen: "ai", icon: "chat", rail: "inbox", quick: true, badge: { path: "store/chat/conversations/counts", field: "needsReply" } },
  { href: "/dashboard/inbox/comments", label: "nav.comments", group: "navgrp.inbox", screen: "ai", icon: "edit", rail: "inbox", badge: { path: "store/chat/conversations/counts", field: "unrepliedComments", tone: "brand" } },
  { href: "/dashboard/inbox/followups", label: "nav.followups", group: "navgrp.inbox", screen: "ai", icon: "clock", rail: "inbox" },
  { href: "/dashboard/ai?tab=channels", label: "nav.aiChannels", group: "navgrp.ai", screen: "ai", icon: "link", rail: "ai" },
  { href: "/dashboard/ai?tab=whatsapp", label: "nav.aiWhatsapp", group: "navgrp.ai", screen: "ai", icon: "phone", rail: "ai" },
  { href: "/dashboard/settings?tab=whatsapp", label: "nav.ownWhatsapp", group: "navgrp.ai", perm: ["ai.read", "ai.manage"], icon: "phone", rail: "ai" },
  { href: "/dashboard/ai?tab=test", label: "nav.aiTest", group: "navgrp.ai", screen: "ai", write: true, icon: "sparkles", rail: "ai" },
  { href: "/dashboard/ai?tab=lessons", label: "nav.aiTeach", group: "navgrp.ai", screen: "ai", icon: "award", rail: "ai" },
  { href: "/dashboard/ai?tab=settings", label: "nav.aiSettings", group: "navgrp.ai", screen: "ai", icon: "settings", rail: "ai" },

  // Customers and messages
  { href: "/dashboard/customers", label: "nav.customers", group: "navgrp.service", screen: "customers", icon: "users", rail: "customers" },
  { href: "/dashboard/questions", label: "nav.questions", group: "navgrp.inbox", screen: "ai", icon: "mail", rail: "inbox" },
  { href: "/dashboard/reviews", label: "nav.reviews", group: "navgrp.service", screen: "reviews", icon: "star", rail: "customers" },

  // Products and stock
  { href: "/dashboard/products", label: "nav.products", group: "navgrp.stock", screen: "products", icon: "bag", quick: true, rail: "catalog" },
  { href: "/dashboard/inventory?tab=stock", label: "nav.inventory", group: "navgrp.stock", screen: "inventory", icon: "inventory", rail: "catalog" },
  { href: "/dashboard/pos?tab=counts", label: "nav.stockCount", group: "navgrp.stock", perm: ["inventory.adjust", "pos.manage"], icon: "check", rail: "catalog" },
  { href: "/dashboard/products/labels", label: "nav.labels", group: "navgrp.stock", perm: ["pos.use", "product.update"], icon: "print", rail: "catalog" },
  { href: "/dashboard/offers", label: "nav.offers", group: "navgrp.stock", screen: "coupons", icon: "coupon", rail: "catalog" },

  // Marketing
  { href: "/dashboard/marketing?tab=posts", label: "nav.posts", group: "navgrp.marketing", screen: "marketing", icon: "trending", rail: "marketing" },
  { href: "/dashboard/growth?tab=segments", label: "nav.segments", group: "navgrp.marketing", perm: "growth.manage", icon: "users", rail: "marketing" },
  { href: "/dashboard/carts", label: "nav.carts", group: "navgrp.marketing", perm: ["cart.read", "cart.manage"], icon: "bag", rail: "marketing" },

  // Reports
  { href: "/dashboard/reports?tab=overview", label: "nav.reports", group: "navgrp.reports", perm: "report.read", icon: "chart", rail: "reports" },
  { href: "/dashboard/reports?tab=sales", label: "nav.salesReport", group: "navgrp.reports", perm: "report.read", icon: "trending", rail: "reports" },
  { href: "/dashboard/reports?tab=products", label: "nav.profitReport", group: "navgrp.reports", perm: "report.read", icon: "wallet", rail: "reports" },
  { href: "/dashboard/copilot", label: "nav.copilot", group: "navgrp.reports", perm: "copilot.use", icon: "sparkles", rail: "reports" },

  // Setup
  { href: "/dashboard/settings?tab=profile", label: "nav.shopDetails", group: "navgrp.setup", screen: "settings", icon: "home", rail: "settings" },
  { href: "/dashboard/settings?tab=look", label: "nav.look", group: "navgrp.setup", screen: "settings", icon: "edit", rail: "settings" },
  { href: "/dashboard/settings?tab=sales", label: "nav.payDelivery", group: "navgrp.setup", screen: "settings", icon: "billing", rail: "settings" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.setup", perm: "staff.manage", icon: "users", rail: "settings" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.setup", screen: "billing", icon: "billing", rail: "settings" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.setup", icon: "bell", rail: "settings" },

  // Advanced: the till's setup
  { href: "/dashboard/pos?tab=terminals", label: "nav.terminals", group: "navgrp.advPos", perm: "pos.manage", icon: "cart", advanced: true, rail: "sell" },
  { href: "/dashboard/pos?tab=cashiers", label: "nav.cashiers", group: "navgrp.advPos", perm: "pos.manage", icon: "users", advanced: true, rail: "sell" },
  { href: "/dashboard/pos?tab=issues", label: "nav.syncIssues", group: "navgrp.advPos", perm: "pos.manage", icon: "alert", advanced: true, rail: "sell" },
  { href: "/dashboard/pos?tab=settings", label: "nav.posSettings", group: "navgrp.advPos", perm: "pos.manage", icon: "settings", advanced: true, rail: "sell" },
  // Advanced: buying and moving stock
  { href: "/dashboard/purchasing?tab=orders", label: "nav.purchaseOrders", group: "navgrp.advStock", perm: "purchase.manage", icon: "file", advanced: true, rail: "catalog" },
  { href: "/dashboard/purchasing?tab=suppliers", label: "nav.suppliers", group: "navgrp.advStock", perm: "purchase.manage", icon: "briefcase", advanced: true, rail: "catalog" },
  { href: "/dashboard/purchasing?tab=transfers", label: "nav.transfers", group: "navgrp.advStock", perm: "purchase.manage", icon: "navigate", advanced: true, rail: "catalog" },
  { href: "/dashboard/inventory?tab=moves", label: "nav.stockMoves", group: "navgrp.advStock", screen: "inventory", icon: "clock", advanced: true, rail: "catalog" },
  // Advanced: marketing and growth tools
  { href: "/dashboard/marketing?tab=plan", label: "nav.contentPlan", group: "navgrp.advMarketing", screen: "marketing", icon: "calendar", advanced: true, rail: "marketing" },
  { href: "/dashboard/marketing?tab=ads", label: "nav.ads", group: "navgrp.advMarketing", screen: "marketing", icon: "trending", advanced: true, rail: "marketing" },
  { href: "/dashboard/marketing?tab=auto", label: "nav.autopilot", group: "navgrp.advMarketing", screen: "marketing", icon: "sparkles", advanced: true, rail: "marketing" },
  { href: "/dashboard/growth?tab=loyalty", label: "nav.loyalty", group: "navgrp.advMarketing", perm: "growth.manage", icon: "award", advanced: true, rail: "marketing" },
  { href: "/dashboard/growth?tab=feeds", label: "nav.feeds", group: "navgrp.advMarketing", perm: "growth.manage", icon: "link", advanced: true, rail: "marketing" },
  { href: "/dashboard/growth?tab=exp", label: "nav.experiments", group: "navgrp.advMarketing", perm: "growth.manage", icon: "chart", advanced: true, rail: "marketing" },
  { href: "/dashboard/growth?tab=builder", label: "nav.aiBuilder", group: "navgrp.advMarketing", perm: "growth.manage", icon: "sparkles", advanced: true, rail: "marketing" },
  // Advanced: marketplaces and other stores
  { href: "/dashboard/channels?tab=insights", label: "nav.marketplaces", group: "navgrp.advChannels", screen: "integrations", icon: "globe", advanced: true, rail: "reports" },
  { href: "/dashboard/channels?tab=orders", label: "nav.mpOrders", group: "navgrp.advChannels", screen: "integrations", icon: "box", advanced: true, rail: "reports" },
  { href: "/dashboard/channels?tab=products", label: "nav.mpProducts", group: "navgrp.advChannels", screen: "integrations", icon: "bag", advanced: true, rail: "reports" },
  { href: "/dashboard/channels?tab=customers", label: "nav.mpCustomers", group: "navgrp.advChannels", screen: "integrations", icon: "users", advanced: true, rail: "reports" },
  { href: "/dashboard/channels?tab=platforms", label: "nav.mpPlatforms", group: "navgrp.advChannels", screen: "integrations", icon: "link", advanced: true, rail: "reports" },
  // Advanced: the AI and WhatsApp connections
  // Advanced: deeper reports
  { href: "/dashboard/reports?tab=customers", label: "nav.customerReport", group: "navgrp.advReports", perm: "report.read", icon: "users", advanced: true, rail: "reports" },
  { href: "/dashboard/reports?tab=operations", label: "nav.opsReport", group: "navgrp.advReports", perm: "report.read", icon: "chart", advanced: true, rail: "reports" },
  // Advanced: technical setup
  { href: "/dashboard/settings?tab=marketing", label: "nav.trackingSettings", group: "navgrp.advSetup", screen: "settings", icon: "trending", advanced: true, rail: "settings" },
  { href: "/dashboard/settings?tab=branches", label: "nav.branches", group: "navgrp.advSetup", perm: "branch.manage", icon: "pin", advanced: true, rail: "settings" },
  { href: "/dashboard/shipping?tab=carriers", label: "nav.carriers", group: "navgrp.advSetup", screen: "shipping", icon: "navigate", advanced: true, rail: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.advSetup", screen: "domains", icon: "globe", advanced: true, rail: "settings" },
  { href: "/dashboard/developers", label: "nav.developers", group: "navgrp.advSetup", perm: "api.manage", icon: "link", advanced: true, rail: "settings" },
];

/** One icon per section on the rail; the section's items open in the panel next to it. */
const RAILS: RailSection[] = [
  { key: "home", label: "rail.home", icon: "dashboard" },
  { key: "inbox", label: "rail.inbox", icon: "inbox" },
  { key: "sell", label: "rail.sell", icon: "box" },
  { key: "catalog", label: "rail.catalog", icon: "bag" },
  { key: "customers", label: "rail.customers", icon: "users" },
  { key: "marketing", label: "rail.marketing", icon: "trending" },
  { key: "ai", label: "rail.ai", icon: "sparkles" },
  { key: "reports", label: "rail.reports", icon: "chart" },
  { key: "settings", label: "rail.settings", icon: "settings" },
];

export default function StoreDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV} rails={RAILS} fullBleed={["/dashboard/inbox"]}>{children}</DashboardShell>;
}
