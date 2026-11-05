import { VisitTracker } from "@/components/visit-tracker";

/** The platform site: the landing pages ((landing)) carry their own full-bleed header; the other pages ((site)) share the plain one; /register is full-screen. */
export default function PlatformRoot({ children }: { children: React.ReactNode }) {
  return <><VisitTracker />{children}</>;
}
