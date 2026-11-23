import type { Locale } from "@/lib/i18n";
import type { ClinicSite } from "@/lib/clinic-site";
import type { CnId } from "./ids";

export type T = (k: string, v?: Record<string, string | number>) => string;

/** Public booking settings that ride along on the public profile but are not part of ClinicSite. */
export type CnProfile = ClinicSite & { cardEnabled?: boolean; cashEnabled?: boolean };

/** Everything a template page needs, resolved once on the server. */
export type Ctx = {
  id: CnId; p: CnProfile; t: T; /** Every cn.* string for this language, handed to client components (they cannot receive `t`). */ dict: Record<string, string>; locale: Locale; logo: string | null; name: string;
  /** "/book" when online booking is on, otherwise undefined (templates then lead with call / WhatsApp). */
  book?: string; phone: string; portal: boolean; inFrame: boolean; previewing: boolean; whiteLabel: boolean;
};
