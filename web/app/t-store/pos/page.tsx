import type { Metadata } from "next";
import Terminal from "@/components/pos/terminal";

export const metadata: Metadata = { title: "POS", robots: { index: false, follow: false } };

/** The till terminal: full screen, outside the dashboard, authenticated by this browser's terminal cookie (works with no internet once set up). */
export default function PosTerminalPage() {
  return <Terminal />;
}
