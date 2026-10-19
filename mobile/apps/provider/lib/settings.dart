import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';

const _govs = <(String, String, String)>[
  ('CAI', 'القاهرة', 'Cairo'), ('GIZ', 'الجيزة', 'Giza'), ('ALX', 'الإسكندرية', 'Alexandria'), ('QLY', 'القليوبية', 'Qalyubia'), ('SHR', 'الشرقية', 'Sharqia'),
  ('DKH', 'الدقهلية', 'Dakahlia'), ('GHR', 'الغربية', 'Gharbia'), ('MNF', 'المنوفية', 'Monufia'), ('BHR', 'البحيرة', 'Beheira'), ('KFS', 'كفر الشيخ', 'Kafr El Sheikh'),
  ('DMT', 'دمياط', 'Damietta'), ('PSD', 'بورسعيد', 'Port Said'), ('ISM', 'الإسماعيلية', 'Ismailia'), ('SUZ', 'السويس', 'Suez'), ('FYM', 'الفيوم', 'Faiyum'),
  ('BNS', 'بني سويف', 'Beni Suef'), ('MNY', 'المنيا', 'Minya'), ('AST', 'أسيوط', 'Asyut'), ('SHG', 'سوهاج', 'Sohag'), ('QNA', 'قنا', 'Qena'),
  ('LXR', 'الأقصر', 'Luxor'), ('ASN', 'أسوان', 'Aswan'), ('RSS', 'البحر الأحمر', 'Red Sea'), ('WAD', 'الوادي الجديد', 'New Valley'), ('MTR', 'مطروح', 'Matrouh'),
  ('NSN', 'شمال سيناء', 'North Sinai'), ('SSN', 'جنوب سيناء', 'South Sinai'),
];
String _gov(String c, bool ar) { for (final g in _govs) { if (g.$1 == c) return ar ? g.$2 : g.$3; } return c; }

List<Map<String, dynamic>> _maps(dynamic v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

Widget _page(List<Widget> children) => ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [for (var i = 0; i < children.length; i++) Padding(padding: const EdgeInsets.only(bottom: 12), child: children[i])]);

/// A card listing rows from [path] with an add button; [row] builds each row, [onAdd]/[onEdit] open the editor sheet.
class _ListCard extends StatefulWidget {
  const _ListCard({required this.ctx, required this.titleAr, required this.titleEn, required this.path, required this.row, this.onAdd, this.hintAr, this.hintEn});
  final Ctx ctx;
  final String titleAr, titleEn, path;
  final String? hintAr, hintEn;
  final Widget Function(BuildContext, Map<String, dynamic>, Future<void> Function() reload) row;
  final Future<void> Function(BuildContext, Future<void> Function() reload)? onAdd;
  @override
  State<_ListCard> createState() => _ListCardState();
}

class _ListCardState extends State<_ListCard> {
  List<Map<String, dynamic>>? items;
  Object? error;

  @override
  void initState() { super.initState(); _reload(); }

  Future<void> _reload() async {
    try {
      final r = await widget.ctx.api.get(widget.path);
      if (mounted) setState(() { items = _maps(r is Map ? (r['data'] ?? r['items'] ?? const []) : r); error = null; });
    } catch (e) {
      if (mounted) setState(() => error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        Expanded(child: Text(isAr ? widget.titleAr : widget.titleEn, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
        if (widget.onAdd != null) FilledButton.tonalIcon(style: FilledButton.styleFrom(minimumSize: const Size(0, 36)), onPressed: () => widget.onAdd!(context, _reload), icon: const Icon(Icons.add_rounded, size: 18), label: Text(isAr ? 'إضافة' : 'Add')),
      ]),
      if (widget.hintAr != null) Padding(padding: const EdgeInsets.only(top: 2), child: Text(isAr ? widget.hintAr! : widget.hintEn!, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))),
      const SizedBox(height: 6),
      if (items == null && error == null) const Padding(padding: EdgeInsets.all(14), child: Center(child: CircularProgressIndicator())),
      if (error != null) Column(children: [Text(errorText(error!)), TextButton(onPressed: () { setState(() => error = null); _reload(); }, child: Text(S.of(context).retry))]),
      if (items != null && items!.isEmpty) Padding(padding: const EdgeInsets.all(10), child: Center(child: Text(isAr ? 'لا يوجد شيء بعد' : 'Nothing yet', style: const TextStyle(color: Color(0xFF6B7C8D))))),
      if (items != null) for (final it in items!) widget.row(context, it, _reload),
    ])));
  }
}

// ---------------------------------------------------------------- Branding (both)

Widget brandingCard(Ctx ctx) => FormCard(
      ctx: ctx, titleAr: 'الهوية والألوان', titleEn: 'Branding', loadPath: 'tenant/branding', savePath: 'tenant/branding',
      fields: const [
        F(FK.color, 'primaryColor', 'اللون الأساسي', 'Primary colour'),
        F(FK.color, 'secondaryColor', 'اللون الثانوي', 'Secondary colour'),
        F(FK.image, 'logoFileId', 'تغيير الشعار', 'Change logo', category: 'LOGO'),
        F(FK.choice, 'locale', 'اللغة الافتراضية', 'Default language', options: {'ar': 'العربية', 'en': 'English'}),
      ],
    );

// ---------------------------------------------------------------- Clinic settings

class ClinicSettings extends StatelessWidget {
  const ClinicSettings({super.key, required this.ctx});
  final Ctx ctx;

  @override
  Widget build(BuildContext context) {
    final manage = ctx.canAny(['settings.manage', 'settings.read']);
    final ro = !ctx.canWrite('settings');   // read only access shows the values without a save button
    return _page([
      if (manage) FormCard(readOnly: ro, 
        ctx: ctx, titleAr: 'بيانات العيادة والحجز', titleEn: 'Clinic and booking', loadPath: 'clinic/profile', savePath: 'clinic/profile',
        fields: const [
          F(FK.text, 'clinicName', 'اسم العيادة', 'Clinic name'),
          F(FK.ltr, 'phone', 'الهاتف', 'Phone'),
          F(FK.ltr, 'email', 'البريد الإلكتروني', 'Email'),
          F(FK.text, 'addressText', 'العنوان', 'Address'),
          F(FK.area, 'about', 'نبذة', 'About'),
          F(FK.toggle, 'bookingEnabled', 'تفعيل الحجز عبر الإنترنت', 'Online booking'),
          F(FK.toggle, 'takeNewPatients', 'استقبال مرضى جدد', 'Accept new patients'),
          F(FK.toggle, 'requiresConfirmation', 'الحجز يحتاج تأكيدًا', 'Bookings need confirmation'),
          F(FK.toggle, 'cashEnabled', 'الدفع نقدًا في العيادة', 'Cash at the clinic'),
          F(FK.number, 'minimumBookingNoticeMinutes', 'أقل مهلة للحجز (دقائق)', 'Minimum notice (minutes)'),
          F(FK.number, 'maximumDaysAhead', 'أقصى مدة للحجز المسبق (أيام)', 'Book up to (days ahead)'),
          F(FK.number, 'cancellationWindowHours', 'مهلة الإلغاء (ساعات)', 'Cancellation window (hours)'),
        ],
      ),
      if (ctx.can('schedule.manage')) _MyDoctorCard(ctx: ctx),
      if (manage) FormCard(readOnly: ro, 
        ctx: ctx, titleAr: 'موقع العيادة على الإنترنت', titleEn: 'Public website', subtitleAr: 'ما يظهر للمرضى في صفحة العيادة', subtitleEn: 'What patients see on the clinic page',
        loadPath: 'clinic/profile', savePath: 'clinic/profile',
        transform: (b) => {...b, 'coverFileId': b['coverFileId'] ?? ''},
        fields: const [
          F(FK.text, 'tagline', 'شعار قصير', 'Tagline'),
          F(FK.number, 'establishedYear', 'سنة التأسيس', 'Established year'),
          F(FK.ltr, 'whatsapp', 'واتساب', 'WhatsApp'),
          F(FK.csv, 'extraPhones', 'أرقام أخرى', 'Other phones'),
          F(FK.ltr, 'mapsUrl', 'رابط الخريطة', 'Maps link'),
          F(FK.ltr, 'facebookUrl', 'فيسبوك', 'Facebook'),
          F(FK.ltr, 'instagramUrl', 'إنستجرام', 'Instagram'),
          F(FK.ltr, 'tiktokUrl', 'تيك توك', 'TikTok'),
          F(FK.ltr, 'websiteUrl', 'الموقع', 'Website'),
          F(FK.hours, 'workingHours', 'مواعيد العمل المعلنة', 'Opening hours'),
          F(FK.image, 'coverFileId', 'صورة الغلاف', 'Cover photo'),
          F(FK.gallery, 'gallery', 'معرض الصور', 'Photo gallery'),
          F(FK.area, 'announcement', 'إعلان', 'Announcement'),
          F(FK.toggle, 'isOpen', 'العيادة تستقبل المرضى الآن', 'Open now'),
          F(FK.text, 'closedMessage', 'رسالة الإغلاق', 'Closed message'),
          F(FK.csv, 'insurance', 'شركات التأمين', 'Insurance companies'),
          F(FK.faqs, 'faqs', 'الأسئلة الشائعة', 'FAQs'),
        ],
      ),
      if (manage) _DoctorsCard(ctx: ctx),
      if (manage) _ClinicBranches(ctx: ctx),
      if (manage) brandingCard(ctx),
    ]);
  }
}

Map<String, dynamic> _doctorToForm(Map<String, dynamic> d) => {
      'displayName': d['displayName'], 'bio': d['bio'], 'publicPhone': d['publicPhone'], 'qualifications': d['qualifications'], 'yearsExperience': d['yearsExperience'],
      'languages': d['languages'], 'profileImageFileId': d['profileImageFileId'], 'defaultAppointmentFeeMinor': d['defaultAppointmentFeeMinor'],
      'specialties': ((d['specialties'] as List?) ?? const []).map((s) => (s as Map)['code']).toList(),
    };

const _doctorFields = <F>[
  F(FK.text, 'displayName', 'الاسم الظاهر', 'Display name'),
  F(FK.image, 'profileImageFileId', 'الصورة الشخصية', 'Profile photo', category: 'AVATAR'),
  F(FK.ltr, 'publicPhone', 'هاتف الطبيب', 'Doctor phone'),
  F(FK.area, 'bio', 'نبذة عن الطبيب', 'Bio'),
  F(FK.text, 'qualifications', 'المؤهلات', 'Qualifications'),
  F(FK.number, 'yearsExperience', 'سنوات الخبرة', 'Years of experience'),
  F(FK.csv, 'languages', 'اللغات', 'Languages'),
  F(FK.money, 'defaultAppointmentFeeMinor', 'سعر الكشف', 'Consultation fee'),
];

Map<String, String> _specOptions(List<Map<String, dynamic>> specs) => {for (final s in specs) '${s['code']}': '${s['nameAr']}|${s['nameEn']}'};

class _MyDoctorCard extends StatelessWidget {
  const _MyDoctorCard({required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) => Async<List<dynamic>>(
        load: () async => [await ctx.api.get('clinic/specialties'), await ctx.api.get('clinic/doctors/me').catchError((_) => null)],
        builder: (context, r, _) {
          final me = r[1];
          if (me == null) return const SizedBox.shrink();
          final specs = _specOptions(_maps(r[0]));
          return FormCard(
            ctx: ctx, titleAr: 'ملفي كطبيب', titleEn: 'My doctor profile', initial: _doctorToForm(Map<String, dynamic>.from(me as Map)), savePath: 'clinic/doctors/me',
            transform: (b) { final sp = b.remove('specialties'); return {'fields': b, if (sp != null) 'specialties': sp}; },
            fields: [..._doctorFields, F(FK.chips, 'specialties', 'التخصصات', 'Specialties', options: specs)],
          );
        },
      );
}

class _DoctorsCard extends StatelessWidget {
  const _DoctorsCard({required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) => _ListCard(
        ctx: ctx, titleAr: 'الأطباء على الموقع', titleEn: 'Doctors on the website', path: 'clinic/doctors',
        row: (c, d, reload) => ListTile(
          contentPadding: EdgeInsets.zero,
          leading: CircleAvatar(backgroundImage: d['profileImageFileId'] == null ? null : NetworkImage(fileUrl(ctx, '${d['profileImageFileId']}')), child: d['profileImageFileId'] == null ? const Icon(Icons.person_rounded) : null),
          title: Text('${d['displayName']}', style: const TextStyle(fontWeight: FontWeight.w800)),
          trailing: const Icon(Icons.edit_outlined),
          onTap: () async {
            await Navigator.of(c).push(MaterialPageRoute(builder: (_) => Scaffold(
                  appBar: AppBar(title: Text('${d['displayName']}')),
                  body: _page([FormCard(ctx: ctx, titleAr: 'بيانات الطبيب', titleEn: 'Doctor details', initial: _doctorToForm(d), savePath: 'clinic/doctors/${d['id']}', transform: (b) { b.remove('specialties'); return {'fields': b}; }, fields: _doctorFields)]),
                )));
            await reload();
          },
        ),
      );
}

class _ClinicBranches extends StatelessWidget {
  const _ClinicBranches({required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) => _ListCard(
        ctx: ctx, titleAr: 'الفروع', titleEn: 'Locations', path: 'clinic/branches',
        onAdd: (c, reload) async {
          final name = TextEditingController(), code = TextEditingController(), city = TextEditingController();
          final isAr = S.of(c).ar;
          if (await formSheet(c, title: isAr ? 'فرع جديد' : 'New location', canSave: () => name.text.trim().isNotEmpty && code.text.trim().isNotEmpty,
              body: (x, set) => [tf(name, isAr ? 'الاسم' : 'Name', onChanged: (_) => set(() {})), tf(code, isAr ? 'الكود' : 'Code', ltr: true, onChanged: (_) => set(() {})), tf(city, isAr ? 'المدينة' : 'City')],
              onSave: () async { await ctx.api.post('clinic/branches', {'name': name.text.trim(), 'code': code.text.trim(), 'city': city.text.trim()}); })) {
            await reload();
          }
        },
        row: (c, b, reload) => ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.location_on_outlined),
          title: Text('${b['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text([b['addressLine1'], b['city']].where((e) => e != null && '$e'.isNotEmpty).join('، ')),
          trailing: const Icon(Icons.edit_outlined),
          onTap: () async {
            await Navigator.of(c).push(MaterialPageRoute(builder: (_) => Scaffold(
                  appBar: AppBar(title: Text('${b['name']}')),
                  body: _page([FormCard(ctx: ctx, titleAr: 'بيانات الفرع', titleEn: 'Location details', initial: b, savePath: 'clinic/branches/${b['id']}', fields: const [
                    F(FK.text, 'name', 'الاسم', 'Name'), F(FK.text, 'addressLine1', 'العنوان', 'Address'), F(FK.text, 'city', 'المدينة', 'City'), F(FK.ltr, 'phone', 'الهاتف', 'Phone'),
                    F(FK.ltr, 'whatsapp', 'واتساب', 'WhatsApp'), F(FK.ltr, 'mapsUrl', 'رابط الخريطة', 'Maps link'), F(FK.text, 'landmark', 'علامة مميزة', 'Landmark'), F(FK.hours, 'workingHours', 'مواعيد العمل', 'Opening hours'),
                  ])]),
                )));
            await reload();
          },
        ),
      );
}

// ---------------------------------------------------------------- Store settings

class StoreSettings extends StatelessWidget {
  const StoreSettings({super.key, required this.ctx});
  final Ctx ctx;

  @override
  Widget build(BuildContext context) {
    final manage = ctx.canAny(['settings.manage', 'settings.read']);
    final ship = ctx.canAny(['shipping.manage', 'shipping.read']);
    final ro = !ctx.canWrite('settings');   // read only access shows the values without a save button
    return _page([
      if (manage) FormCard(readOnly: ro, 
        ctx: ctx, titleAr: 'بيانات التواصل والمواعيد', titleEn: 'Contact and hours', loadPath: 'store/profile', savePath: 'store/profile',
        transform: (b) => {...b, 'coverFileId': b['coverFileId'] ?? ''},
        fields: const [
          F(FK.ltr, 'supportPhone', 'الهاتف', 'Phone'),
          F(FK.ltr, 'whatsapp', 'واتساب', 'WhatsApp'),
          F(FK.ltr, 'supportEmail', 'البريد الإلكتروني', 'Email'),
          F(FK.csv, 'extraPhones', 'أرقام أخرى', 'Other phones'),
          F(FK.area, 'addressText', 'العنوان', 'Address'),
          F(FK.ltr, 'mapsUrl', 'رابط الخريطة', 'Maps link'),
          F(FK.ltr, 'facebookUrl', 'فيسبوك', 'Facebook'),
          F(FK.ltr, 'instagramUrl', 'إنستجرام', 'Instagram'),
          F(FK.ltr, 'tiktokUrl', 'تيك توك', 'TikTok'),
          F(FK.ltr, 'websiteUrl', 'الموقع', 'Website'),
          F(FK.hours, 'workingHours', 'مواعيد العمل', 'Opening hours'),
          F(FK.image, 'coverFileId', 'صورة الغلاف', 'Cover photo'),
          F(FK.toggle, 'isOpen', 'المتجر يستقبل الطلبات', 'Store is open'),
          F(FK.text, 'closedMessage', 'رسالة الإغلاق', 'Closed message'),
          F(FK.area, 'announcement', 'إعلان', 'Announcement'),
        ],
      ),
      if (manage) FormCard(readOnly: ro, 
        ctx: ctx, titleAr: 'بيانات المتجر والسياسات', titleEn: 'Store details and policies', loadPath: 'store/profile', savePath: 'store/profile',
        fields: const [
          F(FK.text, 'storeName', 'اسم المتجر', 'Store name'),
          F(FK.text, 'shortDescription', 'وصف قصير', 'Short description'),
          F(FK.money, 'minOrderMinor', 'أقل قيمة للطلب', 'Minimum order'),
          F(FK.number, 'returnWindowDays', 'مدة الإرجاع (أيام)', 'Return window (days)'),
          F(FK.ltr, 'taxId', 'الرقم الضريبي', 'Tax ID'),
          F(FK.toggle, 'vatIncluded', 'الأسعار شاملة الضريبة', 'Prices include VAT'),
          F(FK.area, 'about', 'عن المتجر', 'About'),
          F(FK.area, 'shippingPolicy', 'سياسة الشحن', 'Shipping policy'),
          F(FK.area, 'returnPolicy', 'سياسة الإرجاع', 'Return policy'),
          F(FK.area, 'privacyPolicy', 'سياسة الخصوصية', 'Privacy policy'),
          F(FK.area, 'termsText', 'الشروط والأحكام', 'Terms'),
        ],
      ),
      if (manage) _StoreCategories(ctx: ctx),
      if (manage) brandingCard(ctx),
      if (ship) _PaymentMethods(ctx: ctx),
      if (ship) _ShippingMethods(ctx: ctx),
      if (ship) _Zones(ctx: ctx),
      if (manage) _Banners(ctx: ctx),
      if (manage) FormCard(readOnly: ro, 
        ctx: ctx, titleAr: 'الإعلانات والتحليلات', titleEn: 'Ads and analytics', subtitleAr: 'الصق المعرّفات من Meta وTikTok وGoogle', subtitleEn: 'Paste your Meta, TikTok and Google IDs',
        loadPath: 'store/profile', savePath: 'store/profile',
        fields: const [F(FK.ltr, 'metaPixelId', 'Meta Pixel ID', 'Meta Pixel ID'), F(FK.ltr, 'tiktokPixelId', 'TikTok Pixel ID', 'TikTok Pixel ID'), F(FK.ltr, 'gaId', 'Google Analytics ID', 'Google Analytics ID')],
      ),
      if (ctx.can('branch.manage')) _StoreBranches(ctx: ctx),
    ]);
  }
}

class _StoreCategories extends StatelessWidget {
  const _StoreCategories({required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) => Async<List<dynamic>>(
        load: () async => [await ctx.api.get('store/business-categories'), await ctx.api.get('store/profile')],
        builder: (context, r, _) {
          final prof = Map<String, dynamic>.from(r[1] as Map);
          return FormCard(
            ctx: ctx, titleAr: 'تصنيفات النشاط', titleEn: 'Business categories',
            initial: {'categories': _maps(prof['categories']).map((c) => c['code']).toList()},
            savePath: 'store/profile/categories', method: 'PUT',
            fields: [F(FK.chips, 'categories', 'اختر التصنيفات', 'Choose categories', options: _specOptions(_maps(r[0])))],
          );
        },
      );
}

const _payLabels = {'CARD': 'بطاقة|Card', 'CASH_ON_DELIVERY': 'الدفع عند الاستلام|Cash on delivery', 'CASH_AT_CLINIC': 'نقدًا في العيادة|Cash at the clinic', 'WALLET': 'محفظة إلكترونية|Wallet', 'INSTAPAY': 'إنستاباي|InstaPay'};

class _PaymentMethods extends StatelessWidget {
  const _PaymentMethods({required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) => _ListCard(
        ctx: ctx, titleAr: 'طرق الدفع', titleEn: 'Payment methods', path: 'store/payment-methods',
        row: (c, m, reload) {
          final isAr = S.of(c).ar;
          final l = (_payLabels[m['method']] ?? '${m['method']}|${m['method']}').split('|');
          return SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(l[isAr ? 0 : 1]), value: m['enabled'] == true, onChanged: (v) async {
            if (await run(c, () => ctx.api.put('store/payment-methods', {'method': m['method'], 'enabled': v}))) await reload();
          });
        },
      );
}

class _ShippingMethods extends StatelessWidget {
  const _ShippingMethods({required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) => _ListCard(
        ctx: ctx, titleAr: 'طرق الشحن', titleEn: 'Shipping methods', path: 'store/shipping-methods',
        onAdd: (c, reload) async {
          final isAr = S.of(c).ar;
          var type = 'FIXED';
          final name = TextEditingController(), fee = TextEditingController(), free = TextEditingController();
          if (await formSheet(c, title: isAr ? 'طريقة شحن جديدة' : 'New shipping method', canSave: () => name.text.trim().isNotEmpty,
              body: (x, set) => [
                    DropdownButtonFormField<String>(
                      initialValue: type,
                      decoration: InputDecoration(labelText: isAr ? 'النوع' : 'Type'),
                      items: [DropdownMenuItem(value: 'PICKUP', child: Text(isAr ? 'استلام من الفرع' : 'Pickup')), DropdownMenuItem(value: 'FIXED', child: Text(isAr ? 'سعر ثابت' : 'Fixed fee')), DropdownMenuItem(value: 'FREE_ABOVE', child: Text(isAr ? 'مجاني فوق مبلغ' : 'Free above amount'))],
                      onChanged: (v) => set(() => type = v ?? 'FIXED'),
                    ),
                    const SizedBox(height: 10),
                    tf(name, isAr ? 'الاسم' : 'Name', onChanged: (_) => set(() {})),
                    tf(fee, isAr ? 'التكلفة' : 'Fee', number: true, ltr: true),
                    if (type == 'FREE_ABOVE') tf(free, isAr ? 'مجاني فوق' : 'Free above', number: true, ltr: true),
                  ],
              onSave: () async { await ctx.api.post('store/shipping-methods', {'type': type, 'name': name.text.trim(), 'feeMinor': toMinor(fee.text.isEmpty ? '0' : fee.text), if (type == 'FREE_ABOVE') 'freeAboveMinor': toMinor(free.text)}); })) {
            await reload();
          }
        },
        row: (c, m, reload) => SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: Text('${m['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text('${m['type']} · ${fmtMoney(m['feeMinor'] as num?)} ${ctx.currency}'),
          value: m['isActive'] == true,
          onChanged: (v) async { if (await run(c, () => ctx.api.put('store/shipping-methods/${m['id']}/active', {'active': v}))) await reload(); },
        ),
      );
}

class _Zones extends StatelessWidget {
  const _Zones({required this.ctx});
  final Ctx ctx;

  Future<void> _edit(BuildContext c, Map<String, dynamic>? z, List<Map<String, dynamic>> all, Future<void> Function() reload) async {
    final isAr = S.of(c).ar;
    final name = TextEditingController(text: '${z?['name'] ?? ''}');
    final fee = TextEditingController(text: fmtMoney(z?['feeMinor'] as num?));
    final free = TextEditingController(text: z?['freeAboveMinor'] == null ? '' : fmtMoney(z!['freeAboveMinor'] as num));
    final cod = TextEditingController(text: z == null || z['codFeeMinor'] == 0 ? '' : fmtMoney(z['codFeeMinor'] as num?));
    final eMin = TextEditingController(text: '${z?['etaMinDays'] ?? 1}'), eMax = TextEditingController(text: '${z?['etaMaxDays'] ?? 3}');
    final sel = <String>{...((z?['governorateCodes'] as List?) ?? const []).cast<String>()};
    final taken = <String>{for (final o in all) if (o['id'] != z?['id']) ...((o['governorateCodes'] as List?) ?? const []).cast<String>()};
    if (await formSheet(c, title: z == null ? (isAr ? 'منطقة شحن جديدة' : 'New zone') : (isAr ? 'تعديل المنطقة' : 'Edit zone'), canSave: () => name.text.trim().isNotEmpty && sel.isNotEmpty,
        body: (x, set) => [
              tf(name, isAr ? 'اسم المنطقة' : 'Zone name', onChanged: (_) => set(() {})),
              Text('${isAr ? 'المحافظات' : 'Governorates'} (${sel.length})', style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 6),
              Wrap(spacing: 6, runSpacing: 6, children: [
                for (final g in _govs)
                  FilterChip(
                    label: Text(isAr ? g.$2 : g.$3),
                    selected: sel.contains(g.$1),
                    selectedColor: Theme.of(x).colorScheme.primary,
                    checkmarkColor: Colors.white,
                    labelStyle: TextStyle(fontWeight: FontWeight.w700, color: sel.contains(g.$1) ? Colors.white : (taken.contains(g.$1) ? const Color(0xFFA0AAB6) : const Color(0xFF1F2A37))),
                    onSelected: taken.contains(g.$1) && !sel.contains(g.$1) ? null : (v) => set(() => v ? sel.add(g.$1) : sel.remove(g.$1)),
                  ),
              ]),
              const SizedBox(height: 12),
              tf(fee, isAr ? 'التكلفة' : 'Fee', number: true, ltr: true),
              tf(free, isAr ? 'مجاني فوق (اختياري)' : 'Free above (optional)', number: true, ltr: true),
              tf(cod, isAr ? 'رسوم الدفع عند الاستلام' : 'Cash on delivery fee', number: true, ltr: true),
              Row(children: [Expanded(child: tf(eMin, isAr ? 'أقل مدة (أيام)' : 'Min days', number: true, ltr: true)), const SizedBox(width: 10), Expanded(child: tf(eMax, isAr ? 'أقصى مدة (أيام)' : 'Max days', number: true, ltr: true))]),
            ],
        onSave: () async {
          final body = {'name': name.text.trim(), 'governorateCodes': sel.toList(), 'feeMinor': toMinor(fee.text.isEmpty ? '0' : fee.text), 'freeAboveMinor': free.text.trim().isEmpty ? null : toMinor(free.text), 'codFeeMinor': toMinor(cod.text.isEmpty ? '0' : cod.text), 'etaMinDays': int.tryParse(eMin.text) ?? 1, 'etaMaxDays': int.tryParse(eMax.text) ?? 3};
          if (z == null) { await ctx.api.post('store/shipping-zones', body); } else { await ctx.api.patch('store/shipping-zones/${z['id']}', body); }
        })) {
      await reload();
    }
  }

  @override
  Widget build(BuildContext context) {
    List<Map<String, dynamic>> latest = [];
    return _ListCard(
      ctx: ctx, titleAr: 'الشحن حسب المحافظة', titleEn: 'Delivery by governorate', hintAr: 'سعر ومدة التوصيل لكل مجموعة محافظات', hintEn: 'Fee and delivery time for each group of governorates', path: 'store/shipping-zones',
      onAdd: (c, reload) async {
        final r = await ctx.api.get('store/shipping-zones');
        latest = _maps(r);
        if (c.mounted) await _edit(c, null, latest, reload);
      },
      row: (c, z, reload) {
        final isAr = S.of(c).ar;
        return ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text('${z['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text('${((z['governorateCodes'] as List?) ?? const []).map((e) => _gov('$e', isAr)).join('، ')}\n${fmtMoney(z['feeMinor'] as num?)} ${ctx.currency} · ${z['etaMinDays']}-${z['etaMaxDays']} ${isAr ? 'يوم' : 'days'}'),
          isThreeLine: true,
          trailing: PopupMenuButton<String>(
            onSelected: (v) async {
              if (v == 'edit') { final all = _maps(await ctx.api.get('store/shipping-zones')); if (c.mounted) await _edit(c, z, all, reload); }
              if (v == 'toggle' && c.mounted && await run(c, () => ctx.api.patch('store/shipping-zones/${z['id']}', {'isActive': z['isActive'] != true}))) await reload();
              if (v == 'delete' && c.mounted && await confirm(c, isAr ? 'حذف المنطقة؟' : 'Delete this zone?') && c.mounted && await run(c, () => ctx.api.delete('store/shipping-zones/${z['id']}'))) await reload();
            },
            itemBuilder: (_) => [
              PopupMenuItem(value: 'edit', child: Text(isAr ? 'تعديل' : 'Edit')),
              PopupMenuItem(value: 'toggle', child: Text(z['isActive'] == true ? (isAr ? 'إخفاء' : 'Hide') : (isAr ? 'إظهار' : 'Show'))),
              PopupMenuItem(value: 'delete', child: Text(isAr ? 'حذف' : 'Delete')),
            ],
          ),
        );
      },
    );
  }
}

class _Banners extends StatelessWidget {
  const _Banners({required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) => _ListCard(
        ctx: ctx, titleAr: 'بانرات الصفحة الرئيسية', titleEn: 'Homepage banners', hintAr: 'حتى 8 صور عريضة مع عنوان ورابط اختياري', hintEn: 'Up to 8 wide pictures with an optional headline and link', path: 'store/banners',
        onAdd: (c, reload) async {
          final isAr = S.of(c).ar;
          String? img;
          final title = TextEditingController(), sub = TextEditingController(), link = TextEditingController();
          if (await formSheet(c, title: isAr ? 'بانر جديد' : 'New banner', canSave: () => img != null,
              body: (x, set) => [
                    OutlinedButton.icon(
                      icon: const Icon(Icons.add_photo_alternate_outlined),
                      label: Text(img == null ? (isAr ? 'اختر صورة' : 'Choose picture') : (isAr ? 'تم رفع الصورة ✓' : 'Picture uploaded ✓')),
                      onPressed: () async { String? id; if (await run(x, () async { id = await pickAndUpload(x, ctx.api, 'PRODUCT_IMAGE'); }) && id != null) set(() => img = id); },
                    ),
                    const SizedBox(height: 10),
                    tf(title, isAr ? 'العنوان' : 'Headline'),
                    tf(sub, isAr ? 'عنوان فرعي' : 'Subtitle'),
                    tf(link, isAr ? 'الرابط (مثال /products?onSale=true)' : 'Link (e.g. /products?onSale=true)', ltr: true),
                  ],
              onSave: () async { await ctx.api.post('store/banners', {'imageFileId': img, 'title': title.text.trim(), 'subtitle': sub.text.trim(), 'linkUrl': link.text.trim()}); })) {
            await reload();
          }
        },
        row: (c, b, reload) {
          final isAr = S.of(c).ar;
          return ListTile(
            contentPadding: EdgeInsets.zero,
            leading: ClipRRect(borderRadius: BorderRadius.circular(8), child: Image.network(fileUrl(ctx, '${b['imageFileId']}'), width: 64, height: 44, fit: BoxFit.cover, errorBuilder: (_, __, ___) => const SizedBox(width: 64, height: 44))),
            title: Text(('${b['title'] ?? ''}').isEmpty ? (isAr ? 'بدون عنوان' : 'No headline') : '${b['title']}', maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text('${b['linkUrl'] ?? ''}', maxLines: 1, overflow: TextOverflow.ellipsis, textDirection: TextDirection.ltr),
            trailing: Row(mainAxisSize: MainAxisSize.min, children: [
              Switch(value: b['isActive'] == true, onChanged: (v) async { if (await run(c, () => ctx.api.patch('store/banners/${b['id']}', {'isActive': v}))) await reload(); }),
              IconButton(icon: const Icon(Icons.delete_outline), onPressed: () async { if (await confirm(c, isAr ? 'حذف البانر؟' : 'Delete this banner?') && c.mounted && await run(c, () => ctx.api.delete('store/banners/${b['id']}'))) await reload(); }),
            ]),
          );
        },
      );
}

class _StoreBranches extends StatelessWidget {
  const _StoreBranches({required this.ctx});
  final Ctx ctx;

  Future<void> _edit(BuildContext c, Map<String, dynamic>? b, Future<void> Function() reload) async {
    final isAr = S.of(c).ar;
    final isNew = b == null;
    final name = TextEditingController(text: '${b?['name'] ?? ''}'), code = TextEditingController(text: '${b?['code'] ?? ''}'), phone = TextEditingController(text: '${b?['phone'] ?? ''}');
    final wa = TextEditingController(text: '${b?['whatsapp'] ?? ''}'), addr = TextEditingController(text: '${b?['addressLine1'] ?? ''}'), area = TextEditingController(text: '${b?['area'] ?? ''}');
    final landmark = TextEditingController(text: '${b?['landmark'] ?? ''}'), maps = TextEditingController(text: '${b?['mapsUrl'] ?? ''}');
    var gov = (b?['governorateCode'] as String?) ?? 'CAI';
    var pickup = b?['isPickup'] == true;
    if (await formSheet(c, title: isNew ? (isAr ? 'فرع جديد' : 'New branch') : (isAr ? 'تعديل الفرع' : 'Edit branch'), canSave: () => name.text.trim().isNotEmpty && code.text.trim().isNotEmpty,
        body: (x, set) => [
              tf(name, isAr ? 'الاسم' : 'Name', onChanged: (_) => set(() {})),
              tf(code, isAr ? 'الكود' : 'Code', ltr: true, onChanged: (_) => set(() {})),
              tf(phone, isAr ? 'الهاتف' : 'Phone', ltr: true),
              tf(wa, 'WhatsApp', ltr: true),
              DropdownButtonFormField<String>(initialValue: gov, decoration: InputDecoration(labelText: isAr ? 'المحافظة' : 'Governorate'), items: [for (final g in _govs) DropdownMenuItem(value: g.$1, child: Text(isAr ? g.$2 : g.$3))], onChanged: (v) => set(() => gov = v ?? gov)),
              const SizedBox(height: 10),
              tf(area, isAr ? 'المنطقة' : 'Area'),
              tf(addr, isAr ? 'العنوان' : 'Address'),
              tf(landmark, isAr ? 'علامة مميزة' : 'Landmark'),
              tf(maps, isAr ? 'رابط الخريطة' : 'Maps link', ltr: true),
              SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(isAr ? 'متاح للاستلام' : 'Pickup point'), value: pickup, onChanged: (v) => set(() => pickup = v)),
            ],
        onSave: () async {
          final body = {'name': name.text.trim(), 'code': code.text.trim(), 'phone': phone.text.trim(), 'whatsapp': wa.text.trim(), 'address': addr.text.trim(), 'governorateCode': gov, 'area': area.text.trim(), 'landmark': landmark.text.trim(), 'mapsUrl': maps.text.trim(), 'isPickup': pickup};
          if (isNew) { await ctx.api.post('store/branches', body); } else { await ctx.api.patch('store/branches/${b['id']}', body); }
        })) {
      await reload();
    }
  }

  @override
  Widget build(BuildContext context) => _ListCard(
        ctx: ctx, titleAr: 'الفروع', titleEn: 'Branches', path: 'store/branches',
        onAdd: (c, reload) => _edit(c, null, reload),
        row: (c, b, reload) => ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text('${b['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text([b['addressLine1'], b['area'], _gov('${b['governorateCode'] ?? ''}', S.of(c).ar)].where((e) => e != null && '$e'.isNotEmpty).join('، ')),
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            Switch(value: b['isActive'] == true, onChanged: (v) async { if (await run(c, () => ctx.api.patch('store/branches/${b['id']}', {'isActive': v}))) await reload(); }),
            IconButton(icon: const Icon(Icons.edit_outlined), onPressed: () => _edit(c, b, reload)),
          ]),
        ),
      );
}
