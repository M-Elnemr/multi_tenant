export default function Template({ children }: { children: React.ReactNode }) {
  // Route transitions are handled where they belong (dashboard shell, storefront sections), not by a global fade.
  return <>{children}</>;
}
