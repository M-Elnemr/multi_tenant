import type { LargeSet } from "../types";
import { BoldCart } from "./cart";
import { BoldFooter } from "./footer";
import { BoldHeader } from "./header";
import { BoldHome } from "./home";
import { BoldListing } from "./listing";
import { BoldPdp } from "./pdp";

/** sn-bold: Policy and Contact keep the shop's own pages, which pick up the template's frame colours through the token variables. */
export const boldSet: LargeSet = { Header: BoldHeader, Footer: BoldFooter, Home: BoldHome, Listing: BoldListing, Pdp: BoldPdp, Cart: BoldCart };
