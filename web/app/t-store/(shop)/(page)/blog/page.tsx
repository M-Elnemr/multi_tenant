import type { Metadata } from "next";
import Link from "next/link";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { dateOnly } from "@/lib/format";

type Post = { slug: string; title: string; excerpt: string; publishedAt: string };

export const metadata: Metadata = { title: "Blog" };

export default async function Blog() {
  const { t, locale } = await getT();
  const posts = await backendJson<Post[]>("/shop/blog").catch(() => [] as Post[]);
  return (
    <>
      <h1 className="s-section-title mb-6">{t("blog.storeTitle")}</h1>
      {posts.length === 0 ? <p className="s-card p-10 text-center text-[var(--s-mute)]">{t("blog.storeEmpty")}</p> : (
        <ul className="grid gap-4 md:grid-cols-2">{posts.map((p) => (
          <li key={p.slug}><Link href={`/blog/${encodeURIComponent(p.slug)}`} className="s-card block p-6 hover:shadow-md"><span className="block text-lg font-extrabold">{p.title}</span>{p.excerpt && <span className="mt-2 block text-[var(--s-mute)]">{p.excerpt}</span>}<span className="mt-3 block text-xs text-[var(--s-mute)]">{dateOnly(p.publishedAt, locale)}</span></Link></li>
        ))}</ul>
      )}
    </>
  );
}
