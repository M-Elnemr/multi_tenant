import type { LargeSet } from "../types";
import { MinimalCart } from "./cart";
import { MinimalFooter } from "./footer";
import { MinimalHeader } from "./header";
import { MinimalHome } from "./home";
import { MinimalListing } from "./listing";
import { MinimalPdp } from "./pdp";

/** sn-minimal: Policy and Contact keep the shop's own pages (plain text pages already suit its look). */
export const minimalSet: LargeSet = { Header: MinimalHeader, Footer: MinimalFooter, Home: MinimalHome, Listing: MinimalListing, Pdp: MinimalPdp, Cart: MinimalCart };
