"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { useMe } from "./hooks";

/** A patient who still has the temporary password from the clinic is sent to change it before anything else. */
export function ForcePasswordChange() {
  const { me } = useMe();
  const path = usePathname();
  const router = useRouter();
  const must = me?.mustChangePassword === true;
  useEffect(() => { if (must && path !== "/portal/account") router.replace("/portal/account"); }, [must, path, router]);
  return null;
}
