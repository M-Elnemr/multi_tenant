import { renderLnPage } from "@/components/lawyer/templates/render"; // DR-B

export const dynamic = "force-dynamic";

export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  const { key } = await params;
  return renderLnPage("areas", { areaKey: decodeURIComponent(key) });
}
