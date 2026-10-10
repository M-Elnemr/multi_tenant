import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", group: "navgrp.clinic", perm: "appointment.manage", icon: "dashboard" },
  { href: "/dashboard/appointments", label: "nav.appointments", group: "navgrp.clinic", perm: "appointment.manage", icon: "calendar" },
  { href: "/dashboard/queue", label: "nav.queue", group: "navgrp.clinic", perm: "appointment.manage", icon: "queue" },
  { href: "/dashboard/current", label: "nav.current", group: "navgrp.clinic", perm: "medical_note.create", icon: "stethoscope" },
  { href: "/dashboard/patients", label: "nav.patients", group: "navgrp.records", perm: "patient.read", icon: "patients" },
  { href: "/dashboard/schedule", label: "nav.schedule", group: "navgrp.setup", perm: "schedule.manage", icon: "clock" },
  { href: "/dashboard/services", label: "nav.services", group: "navgrp.setup", perm: ["schedule.manage", "appointment.manage"], icon: "services" },
  { href: "/dashboard/settings", label: "nav.settings", group: "navgrp.setup", perm: ["settings.manage", "schedule.manage"], icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", group: "navgrp.account", perm: "domain.manage", icon: "globe" },
  { href: "/dashboard/staff", label: "nav.staff", group: "navgrp.account", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", group: "navgrp.account", perm: "billing.read", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", group: "navgrp.account", icon: "bell" },
];

export default function ClinicDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
