import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.office", icon: "dashboard" },
  { href: "/dashboard/schedule", label: "lw.nav.schedule", group: "navgrp.office", screen: "schedule", icon: "calendar" },
  { href: "/dashboard/bookings", label: "lw.nav.bookings", group: "navgrp.office", screen: "bookings", icon: "clock" },
  { href: "/dashboard/cases", label: "lw.nav.cases", group: "navgrp.work", screen: "cases", icon: "briefcase" },
  { href: "/dashboard/clients", label: "lw.nav.clients", group: "navgrp.work", screen: "clients", icon: "users" },
  { href: "/dashboard/finance", label: "lw.nav.finance", group: "navgrp.work", screen: "finance", icon: "wallet" },
  { href: "/dashboard/search", label: "lw.nav.search", group: "navgrp.work", screen: ["cases", "clients"], icon: "search" },
  { href: "/dashboard/website", label: "lw.nav.website", group: "navgrp.setup", screen: "settings", icon: "globe" },
  { href: "/dashboard/services", label: "nav.services", group: "navgrp.setup", screen: "settings", icon: "services" },
  { href: "/dashboard/hours", label: "lw.nav.hours", group: "navgrp.setup", screen: ["schedule", "settings"], icon: "clock" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", screen: "settings", icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.account", screen: "domains", icon: "link" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", screen: "billing", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell" },
];

export default function LawyerDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
