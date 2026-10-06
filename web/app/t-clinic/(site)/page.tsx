import Link from "next/link";
import type { Metadata } from "next";
import { backendJson } from "@/lib/backend";
import { money } from "@/lib/format";
import { getT } from "@/lib/i18n-server";

type PublicProfile = {
  clinicName: string; about?: string; phone?: string; email?: string; addressText?: string; bookingEnabled: boolean;
  doctors: { id: string; displayName: string; bio?: string; consultationDurationMinutes: number; specialties: { code: string; nameAr: string; nameEn: string }[] }[];
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
      <section className="rounded-2xl bg-gradient-to-br from-brand to-brand-2 px-6 py-14 text-center text-white">
        <h1 className="text-3xl font-bold sm:text-4xl">{p.clinicName}</h1>
        {p.about && <p className="mx-auto mt-3 max-w-xl opacity-90">{p.about}</p>}
        {p.bookingEnabled && <Link href="/book" className="mt-6 inline-block rounded-xl bg-white px-6 py-3 font-medium text-slate-900">{t("clinic.bookNow")}</Link>}
      </section>
      <section>
        <h2 className="mb-4 text-xl font-semibold">{t("clinic.doctors")}</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          {p.doctors.map((d) => (
            <div key={d.id} className="rounded-xl border bg-white p-5">
              <p className="text-lg font-semibold">{d.displayName}</p>
              <p className="text-sm text-brand">{d.specialties.map((s) => (locale === "ar" ? s.nameAr : s.nameEn)).join(" · ")}</p>
              {d.bio && <p className="mt-2 text-sm text-slate-600">{d.bio}</p>}
            </div>
          ))}
        </div>
      </section>
      <section>
        <h2 className="mb-4 text-xl font-semibold">{t("clinic.services")}</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          {p.services.map((s) => (
            <div key={s.id} className="flex items-center justify-between rounded-xl border bg-white p-4 text-sm">
              <div><p className="font-medium">{s.name}</p><p className="text-slate-500">{s.durationMinutes} {t("clinic.minutes")}</p></div>
              {s.priceMinor ? <b>{money(s.priceMinor, s.currency, locale)}</b> : null}
            </div>
          ))}
        </div>
      </section>
      <section>
        <h2 className="mb-4 text-xl font-semibold">{t("clinic.branches")}</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          {p.branches.map((b) => <div key={b.id} className="rounded-xl border bg-white p-4 text-sm"><p className="font-medium">{b.name}</p><p className="text-slate-600">{b.addressLine1} {b.city}</p>{b.phone && <p dir="ltr" className="text-slate-600">{b.phone}</p>}</div>)}
        </div>
      </section>
    </div>
  );
}
