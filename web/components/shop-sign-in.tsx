"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { GoogleButton } from "./google-button";
import { safeNext } from "./google-handoff";

function Inner() {
  const router = useRouter();
  const next = safeNext(useSearchParams().get("next"));
  return <GoogleButton next={next} onSignedIn={() => { router.replace(next); router.refresh(); }} />;
}

export function ShopSignIn() { return <Suspense><Inner /></Suspense>; }
