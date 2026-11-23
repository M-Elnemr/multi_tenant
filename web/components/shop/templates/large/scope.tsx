"use client";

import { createContext, useContext } from "react";
import type { LargeId } from "./ids";

const Ctx = createContext<LargeId | null>(null);

/** Tells client pages (cart, errors) which large template is rendering, so they can pick its own layout. null on every other design. */
export function LargeScope({ id, children }: { id: LargeId; children: React.ReactNode }) {
  return <Ctx.Provider value={id}>{children}</Ctx.Provider>;
}
export const useLargeTpl = () => useContext(Ctx);
