import type { Metadata } from "next";
import { MarketerAuth } from "@/components/marketer/auth";

export const metadata: Metadata = { title: "Marketers | Elmanassa", robots: { index: true } };

export default function MarketerHome() { return <MarketerAuth />; }
