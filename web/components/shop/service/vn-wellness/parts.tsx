import Link from "next/link";
import { Reveal } from "@/components/shop/templates/reveal";
import { money } from "@/lib/format";
import { fileUrl } from "@/lib/media";
import type { Locale } from "@/lib/i18n";
import { isOffer, serviceBody } from "../vn-core/data";
import { Pic } from "../vn-core/tools";
import type { T, VnService, VnStaff } from "../vn-core/types";

export function WlPrice({ s, locale }: { s: VnService; locale: Locale }) {
  return <span className="wl-price tnum">{isOffer(s) && <s>{money(s.priceBeforeMinor, s.currency, locale)}</s>}{money(s.priceMinor, s.currency, locale)}</span>;
}

export const whereLabel = (s: VnService, t: T) => (s.locationMode === "HOME" ? t("svh.home") : s.locationMode === "BOTH" ? t("svh.both") : t("svh.shop"));

/** One line of the treatment menu: name, a dotted leader and the price, with duration and place underneath. Long names wrap, they never push the price out. */
export function MenuLine({ s, t, locale }: { s: VnService; t: T; locale: Locale }) {
  const body = serviceBody(s);
  return (
    <li className="wl-line">
      <Link href={`/services/${s.id}`} className="wl-line-link">
        <span className="wl-line-head"><span className="wl-line-name">{s.name}</span><span className="wl-leader" aria-hidden /><WlPrice s={s} locale={locale} /></span>
        <span className="wl-line-meta">{s.durationMinutes} {t("bk.min")} · {whereLabel(s, t)}</span>
        {body && <span className="wl-line-body">{body}</span>}
      </Link>
    </li>
  );
}

export function ArchCard({ s, t, locale, i = 0 }: { s: VnService; t: T; locale: Locale; i?: number }) {
  const body = serviceBody(s);
  return (
    <Reveal delay={Math.min(i, 3) * 0.08}>
      <Link href={`/services/${s.id}`} className="wl-card">
        <span className="wl-card-pic">
          <Pic id={s.imageFileId} fallback={<span className="wl-card-ph" aria-hidden><span>{Array.from(s.name.trim())[0] ?? ""}</span></span>} className="wl-card-img" />
        </span>
        {s.categoryName && <span className="wl-kicker">{s.categoryName}</span>}
        <span className="wl-card-name">{s.name}</span>
        {body && <span className="wl-card-body">{body}</span>}
        <span className="wl-card-foot"><span className="wl-card-meta">{s.durationMinutes} {t("bk.min")}</span><WlPrice s={s} locale={locale} /></span>
      </Link>
    </Reveal>
  );
}

/** The people who really perform services (from the staff linked to services). Nothing is shown when no staff are set up. */
export function uniqueStaff(services: VnService[]): VnStaff[] {
  const m = new Map<string, VnStaff>();
  for (const s of services) for (const x of s.staff ?? []) if (!m.has(x.id)) m.set(x.id, x);
  return Array.from(m.values());
}

export function StaffRow({ staff, title, kicker }: { staff: VnStaff[]; title: string; kicker: string }) {
  if (staff.length === 0) return null;
  return (
    <section className="wl-sec wl-wrap">
      <Reveal className="wl-head"><p className="wl-kicker">{kicker}</p><h2 className="wl-h2">{title}</h2></Reveal>
      <ul className="wl-staff">
        {staff.map((x, i) => (
          <li key={x.id}><Reveal delay={Math.min(i, 4) * 0.07} className="wl-person">
            <span className="wl-person-pic"><Pic id={x.imageFileId} variant="medium" fallback={<span className="wl-person-ph" aria-hidden>{Array.from(x.name.trim())[0] ?? ""}</span>} className="wl-person-img" /></span>
            <span className="wl-person-name">{x.name}</span>
          </Reveal></li>
        ))}
      </ul>
    </section>
  );
}

export const coverUrl = (id?: string | null) => (id ? fileUrl(id, "original") : null);
