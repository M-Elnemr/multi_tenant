import type { Metadata } from "next";
import { AboutSection, ContactBand, DoctorsSection, FaqSection, GallerySection, Hero, HowSection, InsuranceSection, ServicesSection, VisitSection, mainPhone } from "@/components/clinic/sections";
import { backendJson, currentHost } from "@/lib/backend";
import { DAYS } from "@/lib/hours";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";
import { type ClinicSite } from "@/lib/clinic-site";
import { fileUrl } from "@/lib/media";
import { CLINIC_SECTIONS, arrange } from "@/lib/site-layout";
import { wantsEdit } from "@/lib/edit-mode";
import { EditBar, EditFrame, EditProvider } from "@/components/site-edit";

const load = () => backendJson<ClinicSite>("/clinic/public/profile");

export async function generateMetadata(): Promise<Metadata> {
  const p = await load().catch(() => null);
  if (!p) return {};
  const description = (p.tagline || p.about || p.clinicName).slice(0, 160);
  return { title: { absolute: p.clinicName }, description, openGraph: { title: p.clinicName, description, images: p.coverFileId ? [fileUrl(p.coverFileId, "medium")] : undefined } };
}

const SCHEMA_DAY: Record<string, string> = { sat: "Saturday", sun: "Sunday", mon: "Monday", tue: "Tuesday", wed: "Wednesday", thu: "Thursday", fri: "Friday" };

export default async function ClinicHome({ searchParams }: { searchParams?: Promise<Record<string, string | string[] | undefined>> }) {
  const { t, locale } = await getT();
  const edit = await wantsEdit(searchParams);
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
  const nodes: Record<(typeof CLINIC_SECTIONS)[number], React.ReactNode> = {
    hero: <Hero p={p} t={t} logoId={logo} hrefBook={book} />,
    about: <AboutSection p={p} t={t} />,
    services: <ServicesSection services={p.services} t={t} locale={locale} hrefBook={book} />,
    doctors: <DoctorsSection doctors={p.doctors} t={t} locale={locale} phone={phone} hrefBook={book} />,
    how: <HowSection t={t} />,
    visit: <VisitSection p={p} t={t} />,
    gallery: <GallerySection ids={p.gallery ?? []} t={t} />,
    insurance: <InsuranceSection items={p.insurance} t={t} />,
    faq: <FaqSection items={p.faqs} t={t} />,
    contact: <ContactBand p={p} t={t} hrefBook={book} />,
  };
  const sections = arrange(CLINIC_SECTIONS, p.siteLayout, edit);
  const order = arrange(CLINIC_SECTIONS, p.siteLayout, true).map((x) => x.key as string);
  const hiddenKeys = p.siteLayout?.hidden ?? [];
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      {edit ? (
        <EditProvider>
          {sections.map(({ key, hidden }) => <EditFrame key={key} kind="clinic" section={key} hidden={hidden} order={order} hiddenKeys={hiddenKeys}>{nodes[key]}</EditFrame>)}
          <EditBar kind="clinic" order={order} hiddenKeys={hiddenKeys} />
        </EditProvider>
      ) : sections.map(({ key }) => <div key={key} className="contents">{nodes[key]}</div>)}
    </>
  );
}
