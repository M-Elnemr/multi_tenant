import { Alexandria, Almarai, Bricolage_Grotesque, Cairo, Cormorant_Garamond, IBM_Plex_Sans_Arabic, Jost, JetBrains_Mono, Manrope, Noto_Naskh_Arabic, Plus_Jakarta_Sans, Readex_Pro, Sora, Space_Grotesk, Tajawal } from "next/font/google";

/**
 * Fonts of the five vn-* service designs (server side only; never preloaded, the browser fetches a font only when a page of that design uses it).
 * Each design pairs a Latin face with an Arabic face chosen for the same character, so Arabic is set as carefully as English.
 */
const corm = Cormorant_Garamond({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600"], style: ["normal", "italic"], variable: "--f-vn-corm" });
const naskh = Noto_Naskh_Arabic({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "600", "700"], variable: "--f-vn-naskh" });
const jost = Jost({ display: "swap", preload: false, subsets: ["latin"], weight: ["300", "400", "500", "600"], variable: "--f-vn-jost" });
const almarai = Almarai({ display: "swap", preload: false, subsets: ["arabic"], weight: ["300", "400", "700", "800"], variable: "--f-vn-almarai" });
const brico = Bricolage_Grotesque({ display: "swap", preload: false, subsets: ["latin"], variable: "--f-vn-brico" });
const cairo = Cairo({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "600", "700", "800"], variable: "--f-vn-cairo" });
const readex = Readex_Pro({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "500", "600"], variable: "--f-vn-readex" });
const grotesk = Space_Grotesk({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "700"], variable: "--f-vn-grotesk" });
const plexAr = IBM_Plex_Sans_Arabic({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "600", "700"], variable: "--f-vn-plexar" });
const mono = JetBrains_Mono({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "700"], variable: "--f-vn-mono" });
const sora = Sora({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "600", "700"], variable: "--f-vn-sora" });
const manrope = Manrope({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600", "700"], variable: "--f-vn-manrope" });
const alex = Alexandria({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "600", "700"], variable: "--f-vn-alex" });
const jakarta = Plus_Jakarta_Sans({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600", "700", "800"], variable: "--f-vn-jakarta" });
const tajawal = Tajawal({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "700", "800"], variable: "--f-vn-tajawal" });

/** One class string that defines every vn-* font variable on the shop root. */
export const vnFontVars = [corm, naskh, jost, almarai, brico, cairo, readex, grotesk, plexAr, mono, sora, manrope, alex, jakarta, tajawal].map((f) => f.variable).join(" ");
