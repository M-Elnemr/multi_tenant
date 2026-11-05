import { currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";
import type { Metadata } from "next";
import { RegisterWizard } from "./wizard";

export const metadata: Metadata = { title: "Start free · ابدأ مجانًا" };

export default async function Register({ searchParams }: { searchParams: Promise<{ type?: string }> }) {
  const { type } = await searchParams;
  const info = await resolveHost(await currentHost());
  return <RegisterWizard rootDomain={info.kind === "PLATFORM" ? info.rootDomain : ""} initialType={type === "CLINIC" ? "CLINIC" : type === "STORE" ? "STORE" : type === "LAWYER" ? "LAWYER" : null} />;
}
