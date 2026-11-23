"use client";
import type { ComponentType } from "react";
import type { LargeId } from "./ids";
import { EditorialCart } from "./editorial/cart";
import { MinimalCart } from "./minimal/cart";
import { BoldCart } from "./bold/cart";
import { LifestyleCart } from "./lifestyle/cart";
import { MarketCart } from "./market/cart";

/** Client-side map of each large template's cart page (the cart route is a client page). */
export const LARGE_CARTS: Partial<Record<LargeId, ComponentType>> = { "sn-market": MarketCart, "sn-editorial": EditorialCart, "sn-minimal": MinimalCart, "sn-bold": BoldCart, "sn-lifestyle": LifestyleCart };
