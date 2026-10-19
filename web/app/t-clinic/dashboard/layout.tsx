import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.clinic", screen: "appointments", icon: "dashboard" },
  { href: "/dashboard/appointments", label: "nav.appointments", group: "navgrp.clinic", screen: "appointments", icon: "calendar" },
  { href: "/dashboard/queue", label: "nav.queue", group: "navgrp.clinic", screen: "appointments", icon: "queue" },
  { href: "/dashboard/current", label: "nav.current", group: "navgrp.clinic", screen: "exam", icon: "stethoscope" },
  { href: "/dashboard/patients", label: "nav.patients", group: "navgrp.records", screen: "patients", icon: "patients" },
  { href: "/dashboard/schedule", label: "nav.schedule", group: "navgrp.setup", screen: "schedule", icon: "clock" },
  { href: "/dashboard/services", label: "nav.services", group: "navgrp.setup", screen: ["schedule", "appointments"], icon: "services" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", screen: ["settings", "schedule"], icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.account", screen: "domains", icon: "globe" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", screen: "billing", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell" },
];

export default function ClinicDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
