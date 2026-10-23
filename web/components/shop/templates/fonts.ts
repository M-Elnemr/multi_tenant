import { Aref_Ruqaa, Amiri, Baloo_Bhaijaan_2, Cinzel, IBM_Plex_Sans_Arabic, Playfair_Display, Tajawal } from "next/font/google";

/**
 * Display fonts of the shop templates (server side only). They are never preloaded: the browser downloads one only when a page really uses it
 * (templates.css names them), so a shop on the original design pays nothing. next/font needs literal options, so they are spelled out each time.
 */
const amiri = Amiri({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "700"], variable: "--f-amiri" });
const playfair = Playfair_Display({ display: "swap", preload: false, subsets: ["latin"], variable: "--f-playfair" });
const tajawal = Tajawal({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "500", "700", "800"], variable: "--f-tajawal" });
const plex = IBM_Plex_Sans_Arabic({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "500", "600", "700"], variable: "--f-plex" });
const baloo = Baloo_Bhaijaan_2({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "600", "800"], variable: "--f-baloo" });
const aref = Aref_Ruqaa({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "700"], variable: "--f-aref" });
const cinzel = Cinzel({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "600", "700"], variable: "--f-cinzel" });

/** One class string that defines every template font variable on the shop root. */
export const templateFontVars = [amiri, playfair, tajawal, plex, baloo, aref, cinzel].map((f) => f.variable).join(" ");
