import { DashboardShell, type NavItem, type RailSection } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.clinic", screen: "appointments", icon: "dashboard", rail: "home", quick: true },
  { href: "/dashboard/appointments", label: "nav.appointments", group: "navgrp.clinic", screen: "appointments", icon: "calendar", rail: "today", quick: true },
  { href: "/dashboard/queue", label: "nav.queue", group: "navgrp.clinic", screen: "appointments", icon: "queue", rail: "today", quick: true },
  { href: "/dashboard/current", label: "nav.current", group: "navgrp.clinic", screen: "exam", icon: "stethoscope", rail: "today" },
  { href: "/dashboard/patients", label: "nav.patients", group: "navgrp.records", screen: "patients", icon: "patients", rail: "patients", quick: true },
  { href: "/dashboard/schedule", label: "nav.schedule", group: "navgrp.setup", screen: "schedule", icon: "clock", rail: "setup" },
  { href: "/dashboard/services", label: "nav.services", group: "navgrp.setup", screen: ["schedule", "appointments"], icon: "services", rail: "setup" },
  { href: "/dashboard/website", label: "nav.websiteDesign", group: "navgrp.setup", screen: "settings", write: true, icon: "palette", rail: "setup" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", screen: ["settings", "schedule"], icon: "settings", rail: "setup" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.account", screen: "domains", icon: "globe", rail: "account" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users", rail: "account" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", screen: "billing", icon: "billing", rail: "account" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell", rail: "account" },
];

const RAILS: RailSection[] = [
  { key: "home", label: "rail.home", icon: "dashboard" },
  { key: "today", label: "rail.today", icon: "calendar" },
  { key: "patients", label: "rail.patients", icon: "patients" },
  { key: "setup", label: "rail.clinicSetup", icon: "stethoscope" },
  { key: "account", label: "rail.account", icon: "settings" },
];

export default function ClinicDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV} rails={RAILS}>{children}</DashboardShell>;
}
