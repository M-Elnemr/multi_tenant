import { DashboardShell, type NavItem } from "@/components/dashboard/shell";

const NAV: NavItem[] = [
  { href: "/dashboard", label: "nav.overview", perm: "appointment.manage", icon: "📊" },
  { href: "/dashboard/appointments", label: "nav.appointments", perm: "appointment.manage", icon: "📅" },
  { href: "/dashboard/patients", label: "nav.patients", perm: "patient.read", icon: "🧑‍⚕️" },
  { href: "/dashboard/schedule", label: "nav.schedule", perm: "schedule.manage", icon: "🕒" },
  { href: "/dashboard/services", label: "nav.services", perm: ["schedule.manage", "appointment.manage"], icon: "💊" },
  { href: "/dashboard/settings", label: "nav.settings", perm: ["settings.manage", "schedule.manage"], icon: "⚙️" },
  { href: "/dashboard/domains", label: "nav.domains", perm: "domain.manage", icon: "🌐" },
  { href: "/dashboard/staff", label: "nav.staff", perm: "staff.manage", icon: "🧑‍💼" },
  { href: "/dashboard/billing", label: "nav.billing", perm: "billing.read", icon: "💳" },
  { href: "/dashboard/notifications", label: "nav.notifications", icon: "🔔" },
];

export default function ClinicDashboardLayout({ children }: { children: React.ReactNode }) {
  return <DashboardShell nav={NAV}>{children}</DashboardShell>;
}
