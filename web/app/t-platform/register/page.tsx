import { currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";
import { RegisterWizard } from "./wizard";

export default async function Register({ searchParams }: { searchParams: Promise<{ type?: string }> }) {
  const { type } = await searchParams;
  const info = await resolveHost(await currentHost());
  return <RegisterWizard rootDomain={info.kind === "PLATFORM" ? info.rootDomain : ""} initialType={type === "CLINIC" ? "CLINIC" : type === "STORE" ? "STORE" : null} />;
}
