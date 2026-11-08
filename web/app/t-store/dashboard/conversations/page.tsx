import { redirect } from "next/navigation";

/** The inbox moved to /dashboard/inbox (old links and notifications keep working, with the chat they point at). */
export default async function OldConversations({ searchParams }: { searchParams: Promise<{ id?: string }> }) {
  const { id } = await searchParams;
  redirect(id ? `/dashboard/inbox?id=${encodeURIComponent(id)}` : "/dashboard/inbox");
}
