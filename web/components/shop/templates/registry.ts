import type { TemplateId } from "./ids";

/**
 * What makes each shop template its own design: not colours alone but the composition — how the header sits, what the hero is, how a product is
 * presented, how categories are offered and how the grid flows. Colours, type and finish live in templates.css under .shop[data-template].
 * Every variant renders the same shop data (profile, banners, categories, products), so switching template never loses content.
 */
export type HeaderKind = "bar" | "center" | "pill" | "overlay" | "loud";
export type HeroKind = "split" | "editorial" | "immersive" | "type" | "bento" | "menu" | "playful" | "carousel" | "arch";
export type CardKind = "classic" | "minimal" | "tall" | "sticker" | "spec" | "row" | "round" | "arch" | "overlay";
export type CatsKind = "chips" | "tiles" | "index" | "circles" | "menu" | "blocks";
export type GridKind = "g3" | "g4" | "g5" | "rail" | "list";
export type Category = "fashion" | "food" | "tech" | "beauty" | "home" | "general";

export type Look = { header: HeaderKind; hero: HeroKind; card: CardKind; cats: CatsKind; grid: GridKind; dark?: boolean; categories: Category[]; swatch: [string, string, string] };

export const LOOKS: Record<TemplateId, Look> = {
  original: { header: "bar", hero: "split", card: "minimal", cats: "chips", grid: "g4", categories: ["general", "home"], swatch: ["#ffffff", "#111111", "var(--brand)"] },
  atelier: { header: "center", hero: "editorial", card: "tall", cats: "index", grid: "g3", categories: ["fashion"], swatch: ["#ffffff", "#141414", "#b9a37e"] },
  street: { header: "loud", hero: "type", card: "sticker", cats: "blocks", grid: "g4", categories: ["fashion"], swatch: ["#f2f0ea", "#0a0a0a", "#d7ff3a"] },
  grove: { header: "center", hero: "arch", card: "arch", cats: "tiles", grid: "g3", categories: ["beauty", "home", "food"], swatch: ["#f3eee4", "#2f3a2c", "#b5764a"] },
  midnight: { header: "bar", hero: "bento", card: "spec", cats: "chips", grid: "rail", dark: true, categories: ["tech"], swatch: ["#0b0d12", "#e8ecf3", "#5cf2c4"] },
  maison: { header: "overlay", hero: "immersive", card: "overlay", cats: "tiles", grid: "g3", categories: ["home", "fashion", "beauty"], swatch: ["#f7f4ef", "#1d1b18", "#8a6f4e"] },
  feast: { header: "bar", hero: "menu", card: "row", cats: "menu", grid: "list", categories: ["food"], swatch: ["#fff8ef", "#2a1a10", "#e2531f"] },
  bloom: { header: "pill", hero: "playful", card: "round", cats: "circles", grid: "g3", categories: ["beauty", "general"], swatch: ["#fff6fb", "#3b2140", "#ff7ab6"] },
  oud: { header: "center", hero: "carousel", card: "classic", cats: "circles", grid: "g3", categories: ["beauty", "fashion"], swatch: ["#0f0d0a", "#f3e9d2", "#c9a24b"] },
  souq: { header: "bar", hero: "carousel", card: "classic", cats: "circles", grid: "g5", categories: ["tech", "food", "general"], swatch: ["#f4f5f7", "#14171f", "var(--brand)"] },
};

export const lookOf = (id: TemplateId): Look => LOOKS[id] ?? LOOKS.original;
