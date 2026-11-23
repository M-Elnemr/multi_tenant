import type { LargeSet } from "../types";
import { MarketFooter } from "./footer";
import { MarketHeader } from "./header";
import { MarketHome } from "./home";
import { MarketListing } from "./listing";
import { MarketPdp } from "./pdp";
import { MarketCart } from "./cart";

/** sn-market: Policy and Contact are not redesigned, they use the shop's own pages inside this template's colours. */
export const marketSet: LargeSet = { Header: MarketHeader, Footer: MarketFooter, Home: MarketHome, Listing: MarketListing, Pdp: MarketPdp, Cart: MarketCart };
