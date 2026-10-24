import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { dateOnly } from "@/lib/format";

type Post = { slug: string; title: string; excerpt: string; body: string; publishedAt: string };

async function load(slug: string): Promise<Post | null> {
  try { return await backendJson<Post>(`/shop/blog/${encodeURIComponent(slug)}`); } catch { return null; }
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const p = await load((await params).slug);
  return p ? { title: p.title, description: p.excerpt || undefined } : {};
}

export default async function BlogPost({ params }: { params: Promise<{ slug: string }> }) {
  const p = await load((await params).slug);
  if (!p) notFound();
  const { t, locale } = await getT();
  const ld = { "@context": "https://schema.org", "@type": "BlogPosting", headline: p.title, datePublished: p.publishedAt, description: p.excerpt };
  return (
    <article className="mx-auto max-w-2xl">
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      <Link href="/blog" className="text-sm font-bold" style={{ color: "var(--brand)" }}>← {t("blog.storeTitle")}</Link>
      <h1 className="mt-3 text-3xl font-extrabold">{p.title}</h1>
      <p className="mt-1 text-xs text-[var(--s-mute)]">{dateOnly(p.publishedAt, locale)}</p>
      <div className="mt-6 space-y-4 leading-relaxed">{p.body.split(/\n{2,}/).map((para, i) => <p key={i} className="whitespace-pre-line">{para}</p>)}</div>
    </article>
  );
}
