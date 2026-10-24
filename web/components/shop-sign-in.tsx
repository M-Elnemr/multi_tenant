"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { GoogleButton } from "./google-button";
import { safeNext } from "./google-handoff";
import { PhoneSignIn } from "./phone-sign-in";

function Inner() {
  const router = useRouter();
  const next = safeNext(useSearchParams().get("next"));
  const done = () => { router.replace(next); router.refresh(); };
  return <><GoogleButton next={next} onSignedIn={done} /><PhoneSignIn onSignedIn={done} /></>;
}

export function ShopSignIn() { return <Suspense><Inner /></Suspense>; }
