import { IBM_Plex_Sans_Arabic, Inter, Newsreader } from "next/font/google";

/** ln-global type: Newsreader (headlines, Latin), Inter (text), IBM Plex Sans Arabic (Arabic text and headlines). Never preloaded. */
const news = Newsreader({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600"], style: ["normal", "italic"], variable: "--lg-f-news" });
const inter = Inter({ display: "swap", preload: false, subsets: ["latin"], variable: "--lg-f-inter" });
const plexAr = IBM_Plex_Sans_Arabic({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "600", "700"], variable: "--lg-f-plexar" });
export const lgFonts = [news, inter, plexAr].map((f) => f.variable).join(" ");
