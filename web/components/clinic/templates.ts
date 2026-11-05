/**
 * Clinic website designs. Each is its own composition (hero, how doctors and services are presented), skinned in clinic.css under
 * .clinic[data-template]. The list must match ClinicSettingsService.TEMPLATES and the CHECK in migration V79.
 */
export const CLINIC_TEMPLATE_IDS = ["clear", "luxe", "persona", "dental", "derma", "center"] as const;
export type ClinicTemplateId = (typeof CLINIC_TEMPLATE_IDS)[number];

export type ClinicHeroKind = "split" | "fullbleed" | "persona" | "dental" | "arch" | "center";
export type DoctorsKind = "cards" | "portrait" | "directory";
export type ServicesKind = "grid" | "list";
export type ClinicLook = { hero: ClinicHeroKind; doctors: DoctorsKind; services: ServicesKind; dark?: boolean; categories: ("general" | "dental" | "derma" | "center" | "specialist")[]; swatch: [string, string, string] };

export const CLINIC_LOOKS: Record<ClinicTemplateId, ClinicLook> = {
  clear: { hero: "split", doctors: "cards", services: "grid", categories: ["general", "specialist"], swatch: ["#f7fafb", "#14232b", "var(--brand)"] },
  luxe: { hero: "fullbleed", doctors: "portrait", services: "list", dark: true, categories: ["specialist", "derma"], swatch: ["#0e1622", "#efe6d2", "#c8a96a"] },
  persona: { hero: "persona", doctors: "portrait", services: "list", categories: ["specialist", "general"], swatch: ["#f6f3ee", "#1a1d22", "var(--brand)"] },
  dental: { hero: "dental", doctors: "cards", services: "grid", categories: ["dental"], swatch: ["#ffffff", "#0d2b3a", "#19b6c9"] },
  derma: { hero: "arch", doctors: "portrait", services: "list", categories: ["derma", "specialist"], swatch: ["#f8f1ec", "#3d2c2a", "#c98b7a"] },
  center: { hero: "center", doctors: "directory", services: "grid", categories: ["center", "general"], swatch: ["#f3f6fa", "#0f2440", "var(--brand)"] },
};
export const clinicLookOf = (id: ClinicTemplateId): ClinicLook => CLINIC_LOOKS[id] ?? CLINIC_LOOKS.clear;
