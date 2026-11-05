import { Aref_Ruqaa, Amiri, Archivo_Black, Baloo_Bhaijaan_2, Cinzel, Cormorant_Garamond, El_Messiri, IBM_Plex_Sans_Arabic, Lalezar, Playfair_Display, Reem_Kufi, Tajawal } from "next/font/google";

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

const archivo = Archivo_Black({ display: "swap", preload: false, subsets: ["latin"], weight: ["400"], variable: "--f-archivo" });
const lalezar = Lalezar({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400"], variable: "--f-lalezar" });
const cormorant = Cormorant_Garamond({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600"], style: ["normal", "italic"], variable: "--f-cormorant" });
const reem = Reem_Kufi({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "500", "600"], variable: "--f-reem" });
const messiri = El_Messiri({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["500", "600", "700"], variable: "--f-messiri" });

/** One class string that defines every template font variable on the shop root. */
export const templateFontVars = [amiri, playfair, tajawal, plex, baloo, aref, cinzel, archivo, lalezar, cormorant, reem, messiri].map((f) => f.variable).join(" ");
