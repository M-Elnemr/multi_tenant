"use client";

import { useApi } from "@/components/hooks";

/** True only when the platform has patient accounts switched on. While false, the dashboard hides every patient-login screen and hint. */
export function usePatientPortal(): boolean {
  const { data } = useApi<{ patientPortalEnabled?: boolean }>("clinic/public/profile");
  return data?.patientPortalEnabled === true;
}
