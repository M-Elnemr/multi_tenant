/**
 * The ten website designs of SERVICE businesses. They share the data (services, hours, products) and differ in composition: how the hero is built, how a service is shown,
 * the palette, type and finish (service-templates.css, scoped to .shop[data-template=...]). Only a SERVICE business can choose or preview these (backend and picker).
 */
export const SERVICE_TEMPLATE_IDS = ["lumiere", "barbershop", "homefix", "fixit", "aqua", "sparkle", "garage", "pro", "slot", "bazaar"] as const;
export type ServiceTemplateId = (typeof SERVICE_TEMPLATE_IDS)[number];
export const isServiceTemplate = (v: unknown): v is ServiceTemplateId => typeof v === "string" && (SERVICE_TEMPLATE_IDS as readonly string[]).includes(v);

export type SvcHero = "split" | "type" | "overlay" | "list" | "plans" | "tiles" | "slots";
export type SvcCard = "tall" | "row" | "tile" | "photo";
export type ServiceLook = { hero: SvcHero; card: SvcCard; dark?: boolean; categories: string[]; swatch: [string, string, string]; header: "bar" | "center" | "pill" | "overlay" | "loud" };

export const SERVICE_LOOKS: Record<ServiceTemplateId, ServiceLook> = {
  lumiere: { hero: "split", card: "tall", header: "center", categories: ["beauty"], swatch: ["#fbf4ef", "#2b1b17", "#c58a6a"] },
  barbershop: { hero: "list", card: "row", header: "loud", dark: true, categories: ["beauty"], swatch: ["#0e0e10", "#f2efe9", "#e8b33a"] },
  homefix: { hero: "overlay", card: "tile", header: "bar", categories: ["repair", "home"], swatch: ["#f4f7fb", "#10263f", "#f28c28"] },
  fixit: { hero: "tiles", card: "tile", header: "bar", dark: true, categories: ["repair"], swatch: ["#0b1220", "#e6edf7", "#38bdf8"] },
  aqua: { hero: "plans", card: "tile", header: "pill", categories: ["home", "repair"], swatch: ["#f0f9ff", "#0c3a5b", "#0ea5e9"] },
  sparkle: { hero: "type", card: "photo", header: "pill", categories: ["home"], swatch: ["#f6fffb", "#12332b", "#14b8a6"] },
  garage: { hero: "list", card: "row", header: "loud", dark: true, categories: ["auto"], swatch: ["#121212", "#f5f5f0", "#ffcf00"] },
  pro: { hero: "overlay", card: "photo", header: "bar", categories: ["general"], swatch: ["#ffffff", "#0f172a", "#2563eb"] },
  slot: { hero: "slots", card: "row", header: "center", categories: ["general", "beauty"], swatch: ["#ffffff", "#111111", "#6d5efc"] },
  bazaar: { hero: "tiles", card: "photo", header: "bar", categories: ["general"], swatch: ["#f4f5f7", "#14171f", "#e11d48"] },
};
