import { Suspense } from "react";
import { TrackClient } from "./track-client";

export default async function Track({ searchParams }: { searchParams: Promise<{ n?: string; p?: string }> }) {
  const { n, p } = await searchParams;
  return <Suspense><TrackClient initialNumber={n ?? ""} initialPhone={p ?? ""} /></Suspense>;
}
