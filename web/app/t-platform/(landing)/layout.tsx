import "./landing.css";

/** Landing pages (/, /shop, /tabib, /mohami) draw their own full-bleed header and footer. */
export default function LandingLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
