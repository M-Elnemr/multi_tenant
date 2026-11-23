import { renderLnPage } from "@/components/lawyer/templates/render"; // DR-B

export const dynamic = "force-dynamic";

export default async function Page() {
  return renderLnPage("areas");
}
