/** Inner pages sit in a centred container; the home page (one level up) is full-bleed. */
export default function InnerLayout({ children }: { children: React.ReactNode }) {
  return <div className="s-container animate-fade-up py-8 sm:py-10">{children}</div>;
}
