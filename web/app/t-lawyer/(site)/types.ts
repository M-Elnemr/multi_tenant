export type LawyerSite = {
  displayName: string; title: string; headline: string; bio: string; photoUrl?: string | null; coverUrl?: string | null; barNumber: string; barLevel: string; yearsExperience: number;
  officeAddress: string; officeCity: string; mapUrl: string; phone: string; whatsapp: string; email: string; template: "classic" | "modern" | "heritage"; bookingEnabled: boolean; slotMinutes: number;
  practiceAreas: { key: string; title: string; description: string; icon: string }[]; languages: string[]; education: { title: string; place: string; year: string }[];
  achievements: { value: string; label: string }[]; testimonials: { name: string; text: string; rating: number }[]; faq: { q: string; a: string }[]; social: Record<string, string>;
  services: { id: string; name: string; description: string; durationMinutes: number; priceMinor: number | null; mode: "OFFICE" | "ONLINE" | "PHONE" }[];
  hours: { weekday: number; start: string; end: string }[];
};
