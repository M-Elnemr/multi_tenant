import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", perm: "appointment.manage", icon: "dashboard" },
  { href: "/dashboard/appointments", label: "nav.appointments", perm: "appointment.manage", icon: "calendar" },
  { href: "/dashboard/queue", label: "nav.queue", perm: "appointment.manage", icon: "queue" },
  { href: "/dashboard/current", label: "nav.current", perm: "medical_note.create", icon: "stethoscope" },
  { href: "/dashboard/patients", label: "nav.patients", perm: "patient.read", icon: "patients" },
  { href: "/dashboard/schedule", label: "nav.schedule", perm: "schedule.manage", icon: "clock" },
  { href: "/dashboard/services", label: "nav.services", perm: ["schedule.manage", "appointment.manage"], icon: "services" },
  { href: "/dashboard/settings", label: "nav.settings", perm: ["settings.manage", "schedule.manage"], icon: "settings" },
  { href: "/dashboard/domains", label: "nav.domains", perm: "domain.manage", icon: "globe" },
  { href: "/dashboard/staff", label: "nav.staff", perm: "staff.manage", icon: "users" },
  { href: "/dashboard/billing", label: "nav.billing", perm: "billing.read", icon: "billing" },
  { href: "/dashboard/notifications", label: "nav.notifications", icon: "bell" },
];

export default function ClinicDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
