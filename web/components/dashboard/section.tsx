"use client";

import { createContext, useContext } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";

/**
 * Which part of a screen is open, kept in the address (`?tab=`), so the side menu can link straight to it (flat menu, no tabs inside screens),
 * the back button works, and a copied link opens the same place. Unknown or missing values fall back to `fallback`.
 */
export function useSection<T extends string>(keys: readonly T[], fallback: T): [T, (k: T, hash?: string) => void] {
  const params = useSearchParams();
  const router = useRouter();
  const path = usePathname();
  const raw = params.get("tab") as T | null;
  const tab = raw && keys.includes(raw) ? raw : fallback;
  const go = (k: T, hash?: string) => {
    const q = new URLSearchParams(params.toString());
    q.set("tab", k);
    router.replace(`${path}?${q}${hash ? `#${hash}` : ""}`, { scroll: false });
  };
  return [tab, go];
}

/** The label of the side-menu item that is open (the shell provides it): screens split into several menu items use it as their page title. */
export const NavTitleContext = createContext<string | null>(null);
export const useNavTitle = (fallback: string) => useContext(NavTitleContext) ?? fallback;
