import type { Metadata } from "next";
import { AboutSection, ContactBand, DoctorsSection, FaqSection, GallerySection, Hero, HowSection, InsuranceSection, ServicesSection, VisitSection, mainPhone } from "@/components/clinic/sections";
import { backendJson, currentHost } from "@/lib/backend";
import { DAYS } from "@/lib/hours";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";
import { type ClinicSite } from "@/lib/clinic-site";
import { fileUrl } from "@/lib/media";

const load = () => backendJson<ClinicSite>("/clinic/public/profile");

export async function generateMetadata(): Promise<Metadata> {
  const p = await load().catch(() => null);
  if (!p) return {};
  const description = (p.tagline || p.about || p.clinicName).slice(0, 160);
  return { title: { absolute: p.clinicName }, description, openGraph: { title: p.clinicName, description, images: p.coverFileId ? [fileUrl(p.coverFileId, "medium")] : undefined } };
}

const SCHEMA_DAY: Record<string, string> = { sat: "Saturday", sun: "Sunday", mon: "Monday", tue: "Tuesday", wed: "Wednesday", thu: "Thursday", fri: "Friday" };

export default async function ClinicHome() {
  const { t, locale } = await getT();
  const p = await load();
  const info = await resolveHost(await currentHost());
  const logo = info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id ?? null : null;
  const book = p.bookingEnabled ? "/book" : undefined;
  const phone = mainPhone(p);
  const sameAs = [p.facebookUrl, p.instagramUrl, p.tiktokUrl, p.websiteUrl].filter(Boolean);
  const ld = {
    "@context": "https://schema.org", "@type": "MedicalClinic", name: p.clinicName, description: p.tagline || p.about, telephone: phone || undefined, email: p.email || undefined,
    address: p.addressText ? { "@type": "PostalAddress", streetAddress: p.addressText } : undefined, sameAs: sameAs.length ? sameAs : undefined,
    openingHoursSpecification: p.workingHours ? DAYS.filter((d) => p.workingHours?.[d] && !p.workingHours[d].closed).map((d) => ({ "@type": "OpeningHoursSpecification", dayOfWeek: SCHEMA_DAY[d], opens: p.workingHours![d].open, closes: p.workingHours![d].close })) : undefined,
    medicalSpecialty: p.doctors.flatMap((d) => d.specialties.map((s) => s.nameEn)).filter((x, i, a) => a.indexOf(x) === i),
  };
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      <Hero p={p} t={t} logoId={logo} hrefBook={book} />
      <AboutSection p={p} t={t} />
      <ServicesSection services={p.services} t={t} locale={locale} hrefBook={book} />
      <DoctorsSection doctors={p.doctors} t={t} locale={locale} phone={phone} hrefBook={book} />
      <HowSection t={t} />
      <VisitSection p={p} t={t} />
      <GallerySection ids={p.gallery ?? []} t={t} />
      <InsuranceSection items={p.insurance} t={t} />
      <FaqSection items={p.faqs} t={t} />
      <ContactBand p={p} t={t} hrefBook={book} />
    </>
  );
}
