"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { GoogleButton } from "./google-button";

function Inner() {
  const router = useRouter();
  const next = useSearchParams().get("next");
  return <GoogleButton onSignedIn={() => { router.replace(next && next.startsWith("/") && !next.startsWith("//") ? next : "/"); router.refresh(); }} />;
}

export function ShopSignIn() { return <Suspense><Inner /></Suspense>; }
