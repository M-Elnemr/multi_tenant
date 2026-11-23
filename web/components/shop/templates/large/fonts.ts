import { Amiri, Cormorant_Garamond, Fraunces, IBM_Plex_Sans_Arabic, Jost, Noto_Kufi_Arabic, Noto_Sans_Arabic, Nunito_Sans, Plus_Jakarta_Sans, Readex_Pro, Rubik, Tajawal } from "next/font/google";

/**
 * Fonts of the large-catalog templates (server side only, never preloaded: a font downloads only when a page really uses it). Latin and Arabic are
 * paired per template and listed together in the font stack, so each script gets its own face. next/font needs literal options.
 */
const cormorant = Cormorant_Garamond({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600"], style: ["normal", "italic"], variable: "--lf-cormorant" });
const amiri = Amiri({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "700"], variable: "--lf-amiri" });
const jost = Jost({ display: "swap", preload: false, subsets: ["latin"], weight: ["300", "400", "500", "600"], variable: "--lf-jost" });
const plexAr = IBM_Plex_Sans_Arabic({ display: "swap", preload: false, subsets: ["arabic"], weight: ["300", "400", "500", "600"], variable: "--lf-plex-ar" });
const jakarta = Plus_Jakarta_Sans({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600", "700", "800"], variable: "--lf-jakarta" });
const tajawal = Tajawal({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "700", "800"], variable: "--lf-tajawal" });
const readex = Readex_Pro({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["300", "400", "500", "600"], variable: "--lf-readex" });
const rubik = Rubik({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "500", "700", "800", "900"], variable: "--lf-rubik" });
const fraunces = Fraunces({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600", "700"], style: ["normal", "italic"], variable: "--lf-fraunces" });
const kufi = Noto_Kufi_Arabic({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "600", "700"], variable: "--lf-kufi" });
const nunito = Nunito_Sans({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "600", "700"], variable: "--lf-nunito" });
const notoAr = Noto_Sans_Arabic({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "600", "700"], variable: "--lf-noto-ar" });

/** Class names that define every large-template font variable on the storefront root. */
export const largeFontVars = [cormorant, amiri, jost, plexAr, jakarta, tajawal, readex, rubik, fraunces, kufi, nunito, notoAr].map((f) => f.variable).join(" ");
