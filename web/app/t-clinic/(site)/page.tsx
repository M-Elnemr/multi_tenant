import Link from "next/link";
import type { Metadata } from "next";
import { Avatar } from "@/components/ui";
import { backendJson } from "@/lib/backend";
import { money } from "@/lib/format";
import { getT } from "@/lib/i18n-server";

type PublicProfile = {
  clinicName: string; about?: string; phone?: string; email?: string; addressText?: string; bookingEnabled: boolean; queueCount?: number;
  doctors: { id: string; displayName: string; bio?: string; publicPhone?: string; consultationDurationMinutes: number; otherSpecialty?: string | null; specialties: { code: string; nameAr: string; nameEn: string }[] }[];
  services: { id: string; name: string; description?: string; durationMinutes: number; priceMinor?: number; currency: string }[];
  branches: { id: string; name: string; addressLine1?: string; city?: string; phone?: string }[];
};

const load = () => backendJson<PublicProfile>("/clinic/public/profile");

export async function generateMetadata(): Promise<Metadata> {
  const p = await load().catch(() => null);
  return p ? { title: { absolute: p.clinicName }, description: p.about?.slice(0, 160) ?? p.clinicName } : {};
}

export default async function ClinicHome() {
  const { t, locale } = await getT();
  const p = await load();
  const ld = { "@context": "https://schema.org", "@type": "MedicalClinic", name: p.clinicName, telephone: p.phone, email: p.email, address: p.addressText ? { "@type": "PostalAddress", streetAddress: p.addressText } : undefined };
  return (
    <div className="space-y-10">
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      <section className="bg-brand-gradient relative animate-fade-up overflow-hidden rounded-3xl px-6 py-16 text-center text-white shadow-lift"><div className="pointer-events-none absolute -end-16 -top-16 h-64 w-64 rounded-full bg-white/10 [animation:drift_12s_ease-in-out_infinite]" /><div className="pointer-events-none absolute -bottom-20 start-0 h-56 w-56 rounded-full bg-white/10 [animation:drift_16s_ease-in-out_infinite_reverse]" />
        <h1 className="relative text-3xl font-extrabold sm:text-5xl">{p.clinicName}</h1>
        {p.about && <p className="relative mx-auto mt-3 max-w-xl text-lg opacity-90">{p.about}</p>}
        {p.bookingEnabled && <Link href="/book" className="relative mt-7 inline-block rounded-2xl bg-white px-7 py-3 font-semibold text-slate-900 shadow-lg transition hover:-translate-y-0.5 active:scale-[.97]">{t("clinic.bookNow")}</Link>}
      </section>
      <section className="grid gap-3 sm:grid-cols-3">
        <div className="rounded-2xl border border-slate-200/80 bg-white p-4 text-sm shadow-soft"><p className="mb-1 text-xs font-medium text-slate-500">{t("clinic.phone")}</p>{p.phone ? <a href={`tel:${p.phone}`} dir="ltr" className="font-semibold text-brand">{p.phone}</a> : <p>-</p>}</div>
        <div className="rounded-2xl border border-slate-200/80 bg-white p-4 text-sm shadow-soft"><p className="mb-1 text-xs font-medium text-slate-500">{t("clinic.address")}</p><p className="font-semibold">{p.addressText || "-"}</p></div>
        <div className="rounded-2xl border border-slate-200/80 bg-white p-4 text-sm shadow-soft"><p className="mb-1 text-xs font-medium text-slate-500">{t("clinic.queueNow")}</p><p className="text-2xl font-extrabold text-brand">{p.queueCount ?? 0}</p></div>
      </section>
      <section>
        <h2 className="mb-4 text-xl font-bold">{t("clinic.doctors")}</h2>
        <div className="stagger grid gap-4 sm:grid-cols-2">
          {p.doctors.map((d, i) => (
            <div key={d.id} style={{ "--i": i } as React.CSSProperties} className="hover-lift flex gap-4 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-soft"><Avatar name={d.displayName} className="h-14 w-14 text-lg" /><div className="min-w-0">
              <p className="text-lg font-semibold">{d.displayName}</p>
              <p className="text-sm text-brand">{d.specialties.map((s) => (s.code === "other" && d.otherSpecialty ? d.otherSpecialty : locale === "ar" ? s.nameAr : s.nameEn)).join(" · ")}</p>
              {d.bio && <p className="mt-2 text-sm text-slate-600">{d.bio}</p>}
              {d.publicPhone && <a href={`tel:${d.publicPhone}`} dir="ltr" className="mt-2 inline-block text-sm font-semibold text-brand">{d.publicPhone}</a>}
            </div></div>
          ))}
        </div>
      </section>
      <section>
        <h2 className="mb-4 text-xl font-bold">{t("clinic.services")}</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          {p.services.map((s) => (
            <div key={s.id} className="hover-lift flex items-center justify-between rounded-2xl border border-slate-200/80 bg-white p-4 text-sm shadow-soft">
              <div><p className="font-medium">{s.name}</p><p className="text-slate-500">{s.durationMinutes} {t("clinic.minutes")}</p></div>
              {s.priceMinor ? <b>{money(s.priceMinor, s.currency, locale)}</b> : null}
            </div>
          ))}
        </div>
      </section>
      <section>
        <h2 className="mb-4 text-xl font-bold">{t("clinic.branches")}</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          {p.branches.map((b) => <div key={b.id} className="rounded-2xl border border-slate-200/80 bg-white p-4 text-sm shadow-soft"><p className="font-semibold">{b.name}</p><p className="text-slate-600">{b.addressLine1} {b.city}</p>{b.phone && <p dir="ltr" className="text-slate-600">{b.phone}</p>}</div>)}
        </div>
      </section>
    </div>
  );
}
