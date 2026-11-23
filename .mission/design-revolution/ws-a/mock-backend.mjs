// Local stand-in for the clinic backend, ONLY to look at the five cn-* templates in a browser (WS-A).
// Scenario comes from the host prefix: localhost = many doctors (Arabic) | en.localhost = many (English) | one.localhost = 1 doctor (Arabic)
// enone.localhost = 1 doctor English | bare.localhost = no images, long names, little data | closed.localhost = booking disabled
// Run: PORT=8411 node mock-backend.mjs   (web: API_BASE_URL=http://localhost:8411)
import http from "node:http";
import { readFileSync, existsSync } from "node:fs";

const PORT = +(process.env.PORT || 8411);
const IMG = process.env.DEMO_IMAGES || "/Users/elnemr/IdeaProjects/multi_tenant/infra/scripts/.demo-images";
const FILES = { cover1: "c_waiting.jpg", d1: "c_doctor.jpg", d2: "c_doctor.jpg", d3: "c_doctor.jpg", g1: "c_exam.jpg", g2: "c_ped.jpg", g3: "c_reception2.jpg", g4: "c_toys.jpg", g5: "c_reception1.jpg", logo: "c_toys.jpg" };

const SPEC = {
  cardiology: { code: "cardiology", nameAr: "القلب والأوعية الدموية", nameEn: "Cardiology" },
  derma: { code: "dermatology", nameAr: "الأمراض الجلدية والتجميل", nameEn: "Dermatology" },
  peds: { code: "pediatrics", nameAr: "طب الأطفال", nameEn: "Pediatrics" },
  ortho: { code: "orthopedics", nameAr: "جراحة العظام", nameEn: "Orthopedics" },
  other: { code: "other", nameAr: "أخرى", nameEn: "Other" },
};
const hours = (o = "10:00", c = "22:00") => Object.fromEntries(["sat", "sun", "mon", "tue", "wed", "thu", "fri"].map((d) => [d, { open: o, close: c, closed: d === "fri" }]));
const mk = (ar, doctors, services, extra = {}) => ({
  template: "clear", clinicName: ar ? "مركز النيل للتخصصات الطبية" : "Nile Specialty Medical Center",
  tagline: ar ? "رعاية طبية متكاملة بيد أطباء يستمعون إليك" : "Complete medical care from doctors who listen",
  about: ar ? "افتتح المركز عام ٢٠١٠ ليقدم خدمة طبية متخصصة في بيئة هادئة ومريحة. نؤمن بأن الوقت الكافي مع المريض هو أساس التشخيص السليم، ولذلك نخصص لكل زيارة وقتها الكامل.\nنعمل بفريق من الاستشاريين في عدة تخصصات ونوفر معامل وأشعة داخل المركز."
    : "The center opened in 2010 to offer specialist care in a calm, comfortable setting. We believe enough time with the patient is the basis of a sound diagnosis, so every visit gets its full length.\nOur consultants cover several specialties and we run labs and imaging in-house.",
  phone: "+20 100 123 4567", email: "hello@nile-clinic.example", addressText: ar ? "٢٢ شارع التحرير، الدقي، الجيزة" : "22 Tahrir St, Dokki, Giza", bookingEnabled: true, patientPortalEnabled: true, queueCount: 0,
  whatsapp: "+201001234567", facebookUrl: "https://facebook.com/x", instagramUrl: "https://instagram.com/x", mapsUrl: "https://maps.google.com/?q=cairo",
  workingHours: hours(), coverFileId: "cover1", gallery: ["g1", "g2", "g3", "g4", "g5"], announcement: "", isOpen: true, insurance: ar ? ["مصر للتأمين", "أكسا", "بوبا العربية", "أليانز"] : ["Misr Insurance", "AXA", "Bupa Arabia", "Allianz"],
  faqs: ar ? [{ q: "هل يمكنني إلغاء الموعد؟", a: "نعم، يمكنك الإلغاء قبل الموعد بـ ٢٤ ساعة على الأقل من خلال الاتصال بنا." }, { q: "ماذا أحضر معي في الزيارة الأولى؟", a: "الهوية وأي تقارير أو تحاليل سابقة وقائمة بالأدوية التي تتناولها." }, { q: "هل تقبلون التأمين الطبي؟", a: "نتعامل مع عدة شركات تأمين، تواصل معنا لتأكيد شركتك قبل الزيارة." }]
    : [{ q: "Can I cancel my appointment?", a: "Yes, call us at least 24 hours before your appointment." }, { q: "What should I bring to my first visit?", a: "Your ID, any previous reports or tests and a list of medicines you take." }, { q: "Do you accept medical insurance?", a: "We work with several insurers; contact us to confirm yours before the visit." }],
  establishedYear: 2010, siteLayout: null, cardEnabled: false, cashEnabled: true,
  doctors, services, branches: [
    { id: "b1", name: ar ? "الفرع الرئيسي - الدقي" : "Main branch - Dokki", addressLine1: ar ? "٢٢ شارع التحرير" : "22 Tahrir St", city: ar ? "الجيزة" : "Giza", district: ar ? "الدقي" : "Dokki", phone: "+20 100 123 4567", mapsUrl: "https://maps.google.com/?q=dokki", landmark: ar ? "بجوار محطة مترو الدقي" : "Next to Dokki metro", workingHours: hours() },
    { id: "b2", name: ar ? "فرع المعادي" : "Maadi branch", addressLine1: ar ? "١٠ شارع ٩" : "10 Road 9", city: ar ? "القاهرة" : "Cairo", district: ar ? "المعادي" : "Maadi", phone: "+20 100 765 4321", workingHours: hours("12:00", "20:00") },
  ], ...extra,
});
const doc = (id, ar, nameAr, nameEn, specs, o = {}) => ({ id, displayName: ar ? nameAr : nameEn, bio: ar ? "استشاري متخصص حاصل على الزمالة البريطانية، يهتم بالتشخيص الدقيق وشرح الخطة العلاجية للمريض بوضوح.\nيستقبل الحالات في الدقي والمعادي." : "A consultant with a British fellowship, focused on precise diagnosis and explaining the treatment plan clearly.\nSees patients in Dokki and Maadi.", consultationDurationMinutes: 30, defaultAppointmentFeeMinor: 40000, currency: "EGP", gender: "male", specialties: specs, profileImageFileId: "d1", yearsExperience: 14, qualifications: ar ? "دكتوراه جامعة القاهرة، زمالة الكلية الملكية البريطانية" : "MD Cairo University, FRCP (UK)", languages: ar ? ["العربية", "الإنجليزية"] : ["Arabic", "English"], publicPhone: "", ...o });
const svc = (id, ar, nameAr, nameEn, d, p, desc) => ({ id, name: ar ? nameAr : nameEn, description: desc ? (ar ? desc[0] : desc[1]) : undefined, durationMinutes: d, priceMinor: p, currency: "EGP", visitType: "CONSULTATION" });

function dataset(host) {
  const h = host.split(":")[0].split(".")[0];
  const ar = !["en", "enone"].includes(h);
  const S = (id, a, e, d, p, desc) => svc(id, ar, a, e, d, p, desc);
  if (h === "bare") {
    return mk(ar, [
      doc("d1", ar, "الدكتور محمد عبد الرحمن إبراهيم السيد الشافعي استشاري أمراض القلب والأوعية الدموية والقسطرة التداخلية", "Doctor Mohamed Abdelrahman Ibrahim Elsayed Elshafei Consultant Cardiologist and Interventional Catheterisation Specialist", [SPEC.cardiology, SPEC.peds], { profileImageFileId: null, bio: undefined, yearsExperience: null, qualifications: undefined, languages: undefined, defaultAppointmentFeeMinor: null }),
      doc("d2", ar, "د. سلمى", "Dr. Salma", [SPEC.other], { profileImageFileId: null, otherSpecialty: ar ? "علاج طبيعي للأطفال وذوي الاحتياجات الخاصة" : "Paediatric physiotherapy", bio: ar ? "نبذة قصيرة." : "Short bio." }),
    ], [S("s1", "كشف", "Visit", 15, null), S("s2", "متابعة بعد الجراحة وتقييم نتائج الفحوصات المخبرية والأشعة", "Post-surgery follow-up and review of lab and imaging results", 45, 150000)],
    { coverFileId: null, gallery: [], about: undefined, tagline: undefined, faqs: [], insurance: [], email: undefined, whatsapp: undefined, mapsUrl: undefined, facebookUrl: undefined, instagramUrl: undefined, establishedYear: null, workingHours: null, branches: [], addressText: undefined, phone: "+20 100 123 4567" });
  }
  const many = [
    doc("d1", ar, "د. أحمد منصور", "Dr. Ahmed Mansour", [SPEC.cardiology], { yearsExperience: 18 }),
    doc("d2", ar, "د. ليلى حسن", "Dr. Layla Hassan", [SPEC.derma], { gender: "female", yearsExperience: 11, defaultAppointmentFeeMinor: 50000 }),
    doc("d3", ar, "د. كريم فؤاد", "Dr. Karim Fouad", [SPEC.peds, SPEC.other], { otherSpecialty: ar ? "حساسية الأطفال" : "Child allergy", yearsExperience: 9, defaultAppointmentFeeMinor: 30000 }),
    doc("d4", ar, "د. منى الشريف", "Dr. Mona Elsherif", [SPEC.ortho], { gender: "female", profileImageFileId: "d2", yearsExperience: 15 }),
    doc("d5", ar, "د. عمر يوسف", "Dr. Omar Youssef", [SPEC.cardiology, SPEC.ortho], { profileImageFileId: "d3", yearsExperience: 7 }),
    doc("d6", ar, "د. هدى سالم", "Dr. Hoda Salem", [SPEC.derma], { gender: "female", profileImageFileId: null, yearsExperience: 5 }),
  ];
  const services = [
    S("s1", "كشف استشاري", "Consultation", 30, 40000, ["كشف كامل مع مراجعة التاريخ المرضي وخطة العلاج.", "Full consultation with history review and a treatment plan."]),
    S("s2", "متابعة", "Follow-up", 15, 20000), S("s3", "رسم قلب بالمجهود", "Stress ECG", 45, 90000, ["رسم قلب أثناء المجهود لتقييم كفاءة عضلة القلب.", "ECG during exercise to assess heart function."]),
    S("s4", "تنظيف بشرة عميق", "Deep facial cleansing", 60, 70000), S("s5", "جلسة ليزر", "Laser session", 40, 120000), S("s6", "تطعيمات الأطفال", "Child vaccination", 20, 25000),
    S("s7", "أشعة تشخيصية", "Diagnostic imaging", 30, null), S("s8", "علاج طبيعي", "Physiotherapy", 50, 60000), S("s9", "استشارة عبر الإنترنت", "Online consultation", 20, 30000),
  ];
  if (h === "one" || h === "enone") return mk(ar, [many[0]], services.slice(0, 4), { gallery: ["g1", "g2"], branches: mk(ar, [], []).branches.slice(0, 1) });
  if (h === "closed") return mk(ar, many.slice(0, 3), services, { bookingEnabled: false, announcement: ar ? "إجازة العيد من ١٠ إلى ١٢ الشهر الجاري" : "Closed for the holiday on the 10th to 12th" });
  return mk(ar, many, services);
}

const send = (res, code, body, type = "application/json") => { res.writeHead(code, { "content-type": type, "cache-control": "no-store" }); res.end(typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body)); };
const readBody = (req) => new Promise((r) => { let b = ""; req.on("data", (c) => (b += c)); req.on("end", () => r(b ? JSON.parse(b) : {})); });
const booked = new Map();

http.createServer(async (req, res) => {
  const host = String(req.headers["x-forwarded-host"] || req.headers.host || "localhost");
  const u = new URL(req.url, "http://x");
  const p = u.pathname.replace(/^\/api\/v1/, "");
  const ds = dataset(host);
  const authed = /Bearer tok-/.test(String(req.headers.authorization || ""));
  if (p === "/tenant/resolve") return send(res, 200, { kind: "TENANT", id: "t1", slug: "nile", name: ds.clinicName, type: "CLINIC", status: "ACTIVE", locale: host.startsWith("en") ? "en" : "ar", currency: "EGP", timezone: "Africa/Cairo", host, branding: { primary_color: "#0f766e", logo_file_id: host.startsWith("bare") ? undefined : "logo" } });
  if (p === "/clinic/public/profile") return send(res, 200, ds);
  if (p === "/clinic/public/working-days") return send(res, 200, { weekdays: [1, 2, 3, 4, 6, 7] });
  if (p === "/clinic/public/slots") {
    const d = u.searchParams.get("date"); const n = +d.slice(-2);
    if (n % 5 === 0) return send(res, 200, []);
    if (n % 7 === 0) return send(res, 500, { code: "INTERNAL_ERROR", message: "boom" });
    return send(res, 200, ["09:00", "09:30", "10:00", "11:30", "12:00", "13:30", "16:00", "17:30"].map((t) => `${d}T${t}:00+02:00`));
  }
  const f = p.match(/^\/files\/([^/]+)\/content$/);
  if (f && FILES[f[1]] && existsSync(`${IMG}/${FILES[f[1]]}`)) return send(res, 200, readFileSync(`${IMG}/${FILES[f[1]]}`), "image/jpeg");
  if (f) return send(res, 404, { code: "NOT_FOUND" });
  if (p === "/auth/login") { const b = await readBody(req); if (b.password === "wrong") return send(res, 401, { code: "INVALID_CREDENTIALS", message: "bad" }); return send(res, 200, { accessToken: "tok-1", refreshToken: "ref-1", expiresIn: 900, user: {} }); }
  if (p === "/auth/me") return authed ? send(res, 200, { id: "u1", firstName: "Sara", lastName: "Ali", roles: ["PATIENT"], permissions: [] }) : send(res, 401, { code: "UNAUTHENTICATED", message: "no" });
  if (p === "/portal/patients") return send(res, 200, [{ id: "pt1", firstName: "Sara", lastName: "Ali", patientCode: "P-1" }, { id: "pt2", firstName: "Adam", lastName: "Ali", patientCode: "P-2" }]);
  if (p === "/portal/appointments" && req.method === "POST") {
    const b = await readBody(req);
    if (b.patientNote === "fail") return send(res, 409, { code: "SLOT_TAKEN", message: "taken" });
    const id = "ap" + (booked.size + 1); booked.set(id, b);
    return send(res, 200, { id, status: "CONFIRMED", priceMinor: 40000 });
  }
  send(res, 404, { code: "NOT_FOUND", message: p });
}).listen(PORT, () => console.log("mock backend on", PORT));
