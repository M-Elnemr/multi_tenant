import type { Metadata } from "next";
import { MarketerApp } from "@/components/marketer/app";

export const metadata: Metadata = { title: "Marketer dashboard | Elmanassa", robots: { index: false } };

export default function MarketerAppPage() { return <MarketerApp />; }
