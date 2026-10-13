"use client";

import Link from "next/link";
import { useI18n } from "./i18n-provider";
import { Icon, type IconName } from "./icons";

const LINKS: { href: string; key: string; hint: string; icon: IconName; tone: string }[] = [
  { href: "/portal", key: "portal.appointments", hint: "ql.appointmentsHint", icon: "calendar", tone: "from-sky-500 to-blue-600" },
  { href: "/portal/record", key: "portal.record", hint: "ql.recordHint", icon: "file", tone: "from-emerald-500 to-teal-600" },
  { href: "/", key: "portal.clinicDetails", hint: "ql.clinicHint", icon: "stethoscope", tone: "from-violet-500 to-purple-600" },
  { href: "/book", key: "clinic.book", hint: "ql.bookHint", icon: "plus", tone: "from-amber-500 to-orange-600" },
];

/** Every screen of the portal as one big tappable card: nobody has to hunt for the small tabs. */
export function PortalQuickLinks({ skip }: { skip?: string }) {
  const { t } = useI18n();
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {LINKS.filter((l) => l.href !== skip).map((l, i) => (
        <Link key={l.href} href={l.href} style={{ animationDelay: `${i * 60}ms` }} className="group animate-fade-up rounded-3xl border border-slate-200/80 bg-white p-4 shadow-soft transition hover:-translate-y-0.5 hover:shadow-lg active:scale-[.97]">
          <span className={`flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br text-white shadow-md transition group-hover:scale-110 ${l.tone}`}><Icon name={l.icon} className="h-6 w-6" /></span>
          <p className="mt-3 font-bold text-ink">{t(l.key)}</p>
          <p className="mt-0.5 line-clamp-2 text-xs text-slate-500">{t(l.hint)}</p>
        </Link>
      ))}
    </div>
  );
}
