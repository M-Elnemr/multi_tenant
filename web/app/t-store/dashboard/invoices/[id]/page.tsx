"use client";

import { use } from "react";
import InvoiceDoc from "@/components/dashboard/invoice-doc";

export default function InvoicePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <InvoiceDoc id={id} />;
}
