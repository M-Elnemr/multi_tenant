/**
 * Copy for the service-business landing page (elmanassa.shop/services): salons, barbers, plumbers, appliance repair, water-filter maintenance, cleaning...
 * Every claim maps to a real feature of the SERVICE business type: services with duration, price, extras, photos and questions; working hours, breaks, special days,
 * vacations and capacity; free times computed by the system (the assistant offers only those); the calendar; the assistant booking, moving and cancelling
 * and selling products in the same chat; delivery / pickup / both for products. Prices are not written here: the pricing block reads lib/pricing.ts.
 */
export type ServiceCopy = typeof en;

const en = {
  meta: {
    title: "Elmanassa for service businesses: bookings, calendar and an AI that books for you",
    description: "Salons, barbers, repair and maintenance: your services, working hours and capacity, a calendar, and an assistant that answers customers and books real free times day and night.",
  },
  nav: { how: "How it works", chat: "The assistant", calendar: "Calendar", pricing: "Pricing", faq: "Questions", others: "Shops & clinics", login: "Log in", cta: "Start free" },
  hero: {
    eyebrow: "For service businesses",
    title1: "Fill your diary.",
    title2: "Even while",
    title3: "you are working.",
    sub: "Customers ask, your AI answers with your real prices and books a free time that fits your hours and your capacity. You see the day in one calendar.",
    cta: "Start free",
    cta2: "See it work",
    trust: ["Free to start", "Arabic & English", "No card needed"],
    phoneTitle: "Book a visit",
    phoneService: "Haircut + beard",
    phoneDay: "Tomorrow",
    phoneConfirm: "Request booking",
    phoneNote: "Only free times are shown",
  },
  chat: {
    eyebrow: "The assistant",
    title: "It talks like your best receptionist",
    sub: "It knows your services, prices, extras and areas. It asks what you need to know, shows only real free times, and books. The same chat can sell a product.",
    tabs: [
      { key: "salon", label: "Salon" },
      { key: "plumber", label: "Plumber" },
      { key: "ac", label: "Appliance repair" },
      { key: "filter", label: "Water filters" },
    ],
    convos: {
      salon: [
        ["c", "I want a haircut tomorrow"],
        ["a", "Welcome ❤️ Regular haircut is 30 min and EGP 150. Tomorrow I have 12:00, 1:30 or 4:00. Which suits you?"],
        ["c", "1:30"],
        ["a", "Booked: tomorrow 1:30 PM. Your request is registered and we confirm it shortly. Want our keratin shampoo with it? 🧴"],
      ],
      plumber: [
        ["c", "Water is leaking under the sink, can someone come today?"],
        ["a", "Sorry to hear that. We visit your home. Which area, and is the water switched off?"],
        ["c", "Nasr City, yes it's off"],
        ["a", "We cover Nasr City. Visit is EGP 200 and I have 5:00 PM today. Send me your address and I'll book it."],
      ],
      ac: [
        ["c", "My AC is not cooling"],
        ["a", "I'll note it for the technician. Which brand, and how old is the unit?"],
        ["c", "LG, 3 years"],
        ["a", "Thanks. A home visit takes about an hour. Free tomorrow: 10:00, 12:00, 3:00. Which one?"],
      ],
      filter: [
        ["c", "I need maintenance for my 7-stage filter"],
        ["a", "Maintenance is EGP 400 and takes an hour at your home. Add a new candle set for EGP 150 (15 min extra)?"],
        ["c", "Yes please"],
        ["a", "Added. Total EGP 550. Tomorrow I have 11:00 or 2:00. Which?"],
      ],
    } as Record<string, string[][]>,
  },
  how: {
    eyebrow: "How it works",
    title: "Set it once. Then it runs.",
    steps: [
      { title: "Add your services", body: "Name, price, duration, photos, extras and the questions you need answered before a visit." },
      { title: "Set your hours and capacity", body: "Working hours with breaks, days off, vacations, special days, and how many customers you serve at the same time." },
      { title: "Share your booking page", body: "The assistant answers on WhatsApp, Instagram and Messenger and your page takes bookings with real free times." },
      { title: "Run the day from the calendar", body: "Confirm, move, cancel, mark done or no-show. Customers are told for you." },
    ],
  },
  features: {
    eyebrow: "Built for appointments",
    title: "Everything a service business needs, nothing it doesn't",
    items: [
      { icon: "briefcase", title: "A real service catalog", body: "Categories, photos, a promotion price, duration, extras that lengthen and price the visit, and your policy." },
      { icon: "clock", title: "Hours that think", body: "Breaks, days off, vacations, special days and your timezone. Longer services never overrun your closing time." },
      { icon: "users", title: "Capacity you control", body: "Four chairs or one technician: the system never books more customers than you can serve at once." },
      { icon: "sparkles", title: "Books by itself", body: "The assistant only offers free times from your system, checks again before booking, and never double books." },
      { icon: "pin", title: "At the shop or at home", body: "Per service: at your place, at the customer's home with their address, or both. Service areas included." },
      { icon: "bag", title: "Sell products too", body: "Shampoo, replacement filters, spare parts: products stay secondary. Delivery, pickup or both, your choice." },
    ],
  },
  calendar: {
    eyebrow: "The diary",
    title: "Your whole day on one screen",
    sub: "Day, week or month. Requests waiting for you stand out, and one tap confirms, moves or cancels. Works on your phone with one hand.",
    today: "Today",
    items: [
      { time: "10:00", name: "Haircut", who: "Karim", status: "Confirmed" },
      { time: "11:30", name: "Colour", who: "Mona", status: "Waiting for you" },
      { time: "14:00", name: "Filter maintenance (home)", who: "Ahmed", status: "Confirmed" },
    ],
    stats: [{ label: "Appointments today", value: "3" }, { label: "Waiting for you", value: "1" }, { label: "Booked by the assistant", value: "2" }],
  },
  pricing: {
    eyebrow: "Pricing",
    title: "Free to start. You pay for what the assistant does.",
    sub: "No subscription for your business. Usage is paid from a prepaid credit, at the same rates as every shop on the platform.",
    freeTitle: "Your service business",
    freePrice: "Free",
    freeNote: "no card",
    freeItems: ["Services, calendar, hours and capacity", "Your booking page and website", "Customers, products and orders", "Owner app on your phone"],
    giftTitle: "Free credit to start",
    giftBody: "Every new business gets free credit once so the assistant can start answering today.",
    rateAi: "per block of replies in an assistant chat",
    rateMsg: "per message you send to a customer",
    cta: "Start free",
  },
  faq: {
    title: "Questions",
    items: [
      { q: "Can the assistant invent a free time?", a: "No. Free times come from your hours, breaks, closures, capacity and existing bookings. The assistant can only offer those, and the system checks again at the moment of booking." },
      { q: "What if two customers ask for the last slot?", a: "Only one booking is created. The other customer is told it was just taken and is offered the next free times." },
      { q: "Do I have to confirm every booking?", a: "Your choice: confirm automatically when the time is free, or receive requests and approve each. A request nobody answers can expire so the time is free again." },
      { q: "I have a team. Can each person have a schedule?", a: "Today your capacity is how many customers you can serve at the same time, per business and per service. Individual staff schedules are not available yet." },
      { q: "Can I also sell products?", a: "Yes. Your shop is part of the same business, with variants, stock, delivery or pickup. Services stay the main focus of your dashboard." },
    ],
  },
  final: { title: "Let your diary fill itself.", sub: "Add your services and hours, and the assistant starts booking.", cta: "Start free", cta2: "See pricing" },
  footer: { tag: "Bookings, calendar and an AI that books for you.", product: "Product", company: "Platform", shops: "For shops", clinics: "For clinics", lawyers: "For lawyers", priv: "Privacy" },
};

const ar: ServiceCopy = {
  meta: {
    title: "المنصة لأنشطة الخدمات: حجوزات وتقويم وذكاء اصطناعي بيحجز عنك",
    description: "صالونات وحلاقين وصيانة: خدماتك ومواعيد شغلك وسعتك، وتقويم، ومساعد بيرد على العملاء وبيحجز أوقات فاضية حقيقية ليل ونهار.",
  },
  nav: { how: "إزاي بيشتغل", chat: "المساعد", calendar: "التقويم", pricing: "الأسعار", faq: "أسئلة", others: "المتاجر والعيادات", login: "تسجيل الدخول", cta: "ابدأ مجاناً" },
  hero: {
    eyebrow: "لأنشطة الخدمات",
    title1: "املا مفكرتك.",
    title2: "حتى وإنت",
    title3: "شغال.",
    sub: "العميل بيسأل، والمساعد بيرد بأسعارك الحقيقية وبيحجز وقت فاضل يناسب مواعيدك وسعتك. وإنت بتشوف يومك في تقويم واحد.",
    cta: "ابدأ مجاناً",
    cta2: "شوفه شغال",
    trust: ["مجاني للبداية", "عربي وإنجليزي", "من غير كارت"],
    phoneTitle: "احجز زيارة",
    phoneService: "حلاقة + دقن",
    phoneDay: "بكرة",
    phoneConfirm: "اطلب الحجز",
    phoneNote: "بتظهر الأوقات الفاضية بس",
  },
  chat: {
    eyebrow: "المساعد",
    title: "بيتكلم زي أحسن موظف استقبال عندك",
    sub: "عارف خدماتك وأسعارك وإضافاتك ومناطقك. بيسأل عن اللي محتاج يعرفه، وبيعرض أوقات فاضية حقيقية بس، وبيحجز. والمحادثة نفسها ممكن تبيع منتج.",
    tabs: [
      { key: "salon", label: "صالون" },
      { key: "plumber", label: "سباك" },
      { key: "ac", label: "صيانة أجهزة" },
      { key: "filter", label: "فلاتر مياه" },
    ],
    convos: {
      salon: [
        ["c", "عايز أحجز حلاقة بكرة"],
        ["a", "أهلاً بحضرتك ❤️ الحلاقة العادية ٣٠ دقيقة بـ ١٥٠ جنيه. بكرة متاح ١٢:٠٠ أو ١:٣٠ أو ٤:٠٠. أنهي معاد يناسبك؟"],
        ["c", "١:٣٠"],
        ["a", "تم تسجيل طلبك بكرة ١:٣٠ ظهراً وهنأكده حالاً. تحب تضيف شامبو الكيراتين معاه؟ 🧴"],
      ],
      plumber: [
        ["c", "في تسريب مية تحت الحوض، ممكن حد ييجي النهارده؟"],
        ["a", "سلامتك. بنزور البيت. في أنهي منطقة، والمية مقفولة؟"],
        ["c", "مدينة نصر، أيوه مقفولة"],
        ["a", "بنخدم مدينة نصر. الزيارة ٢٠٠ جنيه ومتاح النهارده ٥:٠٠ مساءً. ابعتلي العنوان وأحجزلك."],
      ],
      ac: [
        ["c", "التكييف مش بيبرد"],
        ["a", "هسجلها للفني. ماركة التكييف إيه وعمره كام؟"],
        ["c", "إل جي، ٣ سنين"],
        ["a", "تمام. زيارة البيت حوالي ساعة. بكرة متاح ١٠:٠٠ أو ١٢:٠٠ أو ٣:٠٠. أنهي واحد؟"],
      ],
      filter: [
        ["c", "عايز صيانة لفلتر ٧ مراحل"],
        ["a", "الصيانة ٤٠٠ جنيه وبتاخد ساعة في بيتك. تحب تضيف طقم شمع جديد بـ ١٥٠ جنيه (١٥ دقيقة زيادة)؟"],
        ["c", "أيوه"],
        ["a", "اتضاف. الإجمالي ٥٥٠ جنيه. بكرة متاح ١١:٠٠ أو ٢:٠٠. أنهي واحد؟"],
      ],
    },
  },
  how: {
    eyebrow: "إزاي بيشتغل",
    title: "اظبطه مرة. وبعدها بيشتغل لوحده.",
    steps: [
      { title: "ضيف خدماتك", body: "الاسم والسعر والمدة والصور والإضافات والأسئلة اللي محتاج إجابتها قبل الزيارة." },
      { title: "حدد مواعيدك وسعتك", body: "مواعيد شغل بفترات راحة، وأيام إجازة، وإجازات طويلة، وأيام خاصة، وكام عميل بتخدمهم في نفس الوقت." },
      { title: "شارك صفحة الحجز", body: "المساعد بيرد على واتساب وإنستجرام وماسنجر وصفحتك بتاخد حجوزات بأوقات فاضية حقيقية." },
      { title: "دير يومك من التقويم", body: "أكد وغيّر وألغي وعلّم تم أو ماجاش. والعملاء بيتبلغوا عنك." },
    ],
  },
  features: {
    eyebrow: "مصمم للمواعيد",
    title: "كل اللي نشاط خدمات محتاجه، ومفيش حاجة زيادة",
    items: [
      { icon: "briefcase", title: "كتالوج خدمات حقيقي", body: "تصنيفات وصور وسعر عرض ومدة وإضافات بتطول الزيارة وبتزود سعرها وسياستك." },
      { icon: "clock", title: "مواعيد بتفكر", body: "فترات راحة وإجازات وأيام خاصة ومنطقتك الزمنية. الخدمة الطويلة عمرها ما بتعدي ميعاد الإقفال." },
      { icon: "users", title: "سعة إنت اللي بتتحكم فيها", body: "أربع كراسي أو فني واحد: النظام عمره ما بيحجز عملاء أكتر من اللي تقدر تخدمهم في نفس الوقت." },
      { icon: "sparkles", title: "بيحجز لوحده", body: "المساعد بيعرض بس أوقات فاضية من نظامك، وبيتأكد تاني قبل الحجز، وعمره ما بيحجز مرتين." },
      { icon: "pin", title: "في المحل أو في البيت", body: "لكل خدمة: عندك، أو في بيت العميل بعنوانه، أو الاتنين. ومناطق الخدمة كمان." },
      { icon: "bag", title: "بيع منتجات كمان", body: "شامبو وفلاتر بديلة وقطع غيار: المنتجات ثانوية. توصيل أو استلام أو الاتنين، إنت بتختار." },
    ],
  },
  calendar: {
    eyebrow: "المفكرة",
    title: "يومك كله على شاشة واحدة",
    sub: "يوم أو أسبوع أو شهر. الطلبات اللي مستنياك بتبان، وضغطة واحدة بتأكد أو بتغير أو بتلغي. وبتشتغل على موبايلك بإيد واحدة.",
    today: "النهارده",
    items: [
      { time: "١٠:٠٠", name: "حلاقة", who: "كريم", status: "مؤكد" },
      { time: "١١:٣٠", name: "صبغة", who: "منى", status: "مستني ردك" },
      { time: "٢:٠٠", name: "صيانة فلتر (في البيت)", who: "أحمد", status: "مؤكد" },
    ],
    stats: [{ label: "مواعيد النهارده", value: "٣" }, { label: "مستنية ردك", value: "١" }, { label: "حجزها المساعد", value: "٢" }],
  },
  pricing: {
    eyebrow: "الأسعار",
    title: "مجاني للبداية. وبتدفع على اللي المساعد بيعمله.",
    sub: "مفيش اشتراك لنشاطك. الاستخدام بيتدفع من رصيد مسبق، بنفس أسعار كل المتاجر على المنصة.",
    freeTitle: "نشاط الخدمات بتاعك",
    freePrice: "مجاني",
    freeNote: "من غير كارت",
    freeItems: ["الخدمات والتقويم والمواعيد والسعة", "صفحة الحجز وموقعك", "العملاء والمنتجات والطلبات", "تطبيق المالك على موبايلك"],
    giftTitle: "رصيد مجاني للبداية",
    giftBody: "كل نشاط جديد بياخد رصيد مجاني مرة واحدة عشان المساعد يبدأ يرد النهارده.",
    rateAi: "لكل مجموعة ردود في محادثة المساعد",
    rateMsg: "لكل رسالة بتبعتها لعميل",
    cta: "ابدأ مجاناً",
  },
  faq: {
    title: "أسئلة",
    items: [
      { q: "المساعد ممكن يخترع وقت فاضي؟", a: "لا. الأوقات الفاضية بتتحسب من مواعيدك وفترات الراحة والإجازات والسعة والحجوزات الموجودة. المساعد يقدر يعرض دي بس، والنظام بيتأكد تاني لحظة الحجز." },
      { q: "لو عميلين طلبوا آخر ميعاد؟", a: "حجز واحد بس بيتعمل. العميل التاني بيتقاله إنه اتاخد دلوقتي وبيتعرض عليه أقرب أوقات فاضية." },
      { q: "لازم أأكد كل حجز؟", a: "إنت بتختار: تأكيد تلقائي لو الوقت فاضي، أو تستقبل طلبات وتوافق على كل واحد. والطلب اللي محدش رد عليه ممكن ينتهي والوقت يرجع فاضي." },
      { q: "عندي فريق. كل واحد ممكن يبقى له جدول؟", a: "دلوقتي السعة هي كام عميل تقدر تخدمهم في نفس الوقت، للنشاط ولكل خدمة. جداول الموظفين الفردية لسه مش متاحة." },
      { q: "أقدر أبيع منتجات كمان؟", a: "أيوه. متجرك جزء من نفس النشاط، بأحجام ومخزون وتوصيل أو استلام. والخدمات هي التركيز الرئيسي للوحة تحكمك." },
    ],
  },
  final: { title: "سيب مفكرتك تتملي لوحدها.", sub: "ضيف خدماتك ومواعيدك والمساعد يبدأ يحجز.", cta: "ابدأ مجاناً", cta2: "شوف الأسعار" },
  footer: { tag: "حجوزات وتقويم وذكاء اصطناعي بيحجز عنك.", product: "المنتج", company: "المنصة", shops: "للمتاجر", clinics: "للعيادات", lawyers: "للمحامين", priv: "الخصوصية" },
};

export function serviceCopy(locale: string): ServiceCopy { return locale === "ar" ? ar : en; }
