import type { LargeSet } from "../types";
import { EditorialCart } from "./cart";
import { EditorialFooter } from "./footer";
import { EditorialHeader } from "./header";
import { EditorialHome } from "./home";
import { EditorialListing } from "./listing";
import { EditorialContact, EditorialPolicy } from "./pages";
import { EditorialPdp } from "./pdp";

export const editorialSet: LargeSet = { Header: EditorialHeader, Footer: EditorialFooter, Home: EditorialHome, Listing: EditorialListing, Pdp: EditorialPdp, Cart: EditorialCart, Policy: EditorialPolicy, Contact: EditorialContact };
