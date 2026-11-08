import { DashboardShell, type NavItem, type RailSection } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.office", icon: "dashboard", rail: "home", quick: true },
  { href: "/dashboard/schedule", label: "lw.nav.schedule", group: "navgrp.office", screen: "schedule", icon: "calendar", rail: "office", quick: true },
  { href: "/dashboard/bookings", label: "lw.nav.bookings", group: "navgrp.office", screen: "bookings", icon: "clock", rail: "office" },
  { href: "/dashboard/cases", label: "lw.nav.cases", group: "navgrp.work", screen: "cases", icon: "briefcase", rail: "cases", quick: true },
  { href: "/dashboard/clients", label: "lw.nav.clients", group: "navgrp.work", screen: "clients", icon: "users", rail: "cases", quick: true },
  { href: "/dashboard/finance", label: "lw.nav.finance", group: "navgrp.work", screen: "finance", icon: "wallet", rail: "cases" },
  { href: "/dashboard/search", label: "lw.nav.search", group: "navgrp.work", screen: ["cases", "clients"], icon: "search", rail: "cases" },
  { href: "/dashboard/website", label: "lw.nav.website", group: "navgrp.setup", screen: "settings", icon: "globe", rail: "setup" },
  { href: "/dashboard/services", label: "nav.services", group: "navgrp.setup", screen: "settings", icon: "services", rail: "setup" },
  { href: "/dashboard/hours", label: "lw.nav.hours", group: "navgrp.setup", screen: ["schedule", "settings"], icon: "clock", rail: "setup" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", screen: "settings", icon: "settings", rail: "setup" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.account", screen: "domains", icon: "link", rail: "account" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users", rail: "account" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", screen: "billing", icon: "billing", rail: "account" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell", rail: "account" },
];

const RAILS: RailSection[] = [
  { key: "home", label: "rail.home", icon: "dashboard" },
  { key: "office", label: "rail.office", icon: "calendar" },
  { key: "cases", label: "rail.cases", icon: "briefcase" },
  { key: "setup", label: "rail.officeSetup", icon: "scale" },
  { key: "account", label: "rail.account", icon: "settings" },
];

export default function LawyerDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV} rails={RAILS}>{children}</DashboardShell>;
}
