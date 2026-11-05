import "./landing.css";

/** Landing pages (/, /shop; /tabib, /clinic, /doctor; /mohami, /lawyer (aliases share one page and one canonical)) draw their own full-bleed header and footer. */
export default function LandingLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
