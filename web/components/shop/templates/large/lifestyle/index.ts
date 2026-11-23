import type { LargeSet } from "../types";
import { LifestyleCart } from "./cart";
import { LifestyleFooter } from "./footer";
import { LifestyleHeader } from "./header";
import { LifestyleHome } from "./home";
import { LifestyleListing } from "./listing";
import { LifestylePdp } from "./pdp";

/** sn-lifestyle: Policy and Contact keep the shop's own pages inside this template's colours. */
export const lifestyleSet: LargeSet = { Header: LifestyleHeader, Footer: LifestyleFooter, Home: LifestyleHome, Listing: LifestyleListing, Pdp: LifestylePdp, Cart: LifestyleCart };
