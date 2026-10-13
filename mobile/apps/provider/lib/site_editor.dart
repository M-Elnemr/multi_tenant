import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:platform_core/platform_core.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'admin.dart';
import 'common.dart';
import 'forms.dart';
import 'products.dart';
import 'settings.dart';
import 'store_panel.dart';

/// One block of the public site the owner can change, hide or move.
class _Sec {
  const _Sec(this.key, this.ar, this.en, this.icon, this.editors, {this.note});
  final String key, ar, en;
  final IconData icon;
  /// What opens when the owner taps Edit: cards shown in a sheet, or (for pages that already exist in the app) a full screen.
  final List<Widget> Function(Ctx ctx, VoidCallback saved) editors;
  final String? note;
}

const _clinicKeys = ['hero', 'about', 'services', 'doctors', 'how', 'visit', 'gallery', 'insurance', 'faq', 'contact'];
const _storeKeys = ['banners', 'trust', 'categories', 'featured', 'offers', 'bestSellers', 'newest', 'whatsapp', 'branches'];

FormCard _card(Ctx c, String ar, String en, List<F> fields, VoidCallback saved, {String? path, Map<String, dynamic> Function(Map<String, dynamic>)? transform, Map<String, dynamic> Function(Map<String, dynamic>)? pick}) =>
    FormCard(ctx: c, titleAr: ar, titleEn: en, loadPath: path ?? (c.type == 'STORE' ? 'store/profile' : 'clinic/profile'), savePath: path ?? (c.type == 'STORE' ? 'store/profile' : 'clinic/profile'), fields: fields, onSaved: saved, transform: transform, pick: pick);

Widget _pushNote(String ar, String en) => Builder(builder: (c) => Padding(padding: const EdgeInsets.all(8), child: Text(S.of(c).ar ? ar : en, style: const TextStyle(color: Color(0xFF6B7C8D)))));

final Map<String, _Sec> _catalog = {
  // ---- clinic
  'hero': _Sec('hero', 'البانر العلوي', 'Top banner', Icons.image_outlined, (c, s) => [
        _card(c, 'العنوان والصورة', 'Headline and cover', const [F(FK.text, 'tagline', 'شعار قصير', 'Tagline'), F(FK.image, 'coverFileId', 'صورة الغلاف', 'Cover photo'), F(FK.area, 'announcement', 'إعلان', 'Announcement'), F(FK.toggle, 'isOpen', 'العيادة تستقبل المرضى الآن', 'Open now'), F(FK.text, 'closedMessage', 'رسالة الإغلاق', 'Closed message')], s, transform: (b) => {...b, 'coverFileId': b['coverFileId'] ?? ''}),
        brandingCard(c),
      ]),
  'about': _Sec('about', 'عن العيادة', 'About', Icons.info_outline_rounded, (c, s) => [_card(c, 'عن العيادة', 'About the clinic', const [F(FK.text, 'clinicName', 'اسم العيادة', 'Clinic name'), F(FK.area, 'about', 'نبذة', 'About'), F(FK.number, 'establishedYear', 'سنة التأسيس', 'Established year')], s)]),
  'services': _Sec('services', 'الخدمات', 'Services', Icons.medical_services_outlined, (c, s) => [ServicesScreen(ctx: c)]),
  'doctors': _Sec('doctors', 'الأطباء', 'Doctors', Icons.groups_rounded, (c, s) => [clinicDoctorsCard(c)]),
  'how': _Sec('how', 'كيف تحجز', 'How it works', Icons.route_rounded, (c, s) => [_pushNote('هذا القسم ثابت. يمكنك إخفاؤه أو نقله.', 'This section is fixed. You can hide it or move it.')]),
  'visit': _Sec('visit', 'زورونا', 'Visit us', Icons.place_outlined, (c, s) => [_card(c, 'العنوان والمواعيد', 'Address and hours', const [F(FK.text, 'addressText', 'العنوان', 'Address'), F(FK.ltr, 'mapsUrl', 'رابط الخريطة', 'Maps link'), F(FK.hours, 'workingHours', 'مواعيد العمل', 'Opening hours')], s), clinicBranchesCard(c)]),
  'gallery': _Sec('gallery', 'معرض الصور', 'Gallery', Icons.photo_library_outlined, (c, s) => [_card(c, 'معرض الصور', 'Photo gallery', const [F(FK.gallery, 'gallery', 'الصور', 'Photos')], s)]),
  'insurance': _Sec('insurance', 'التأمين', 'Insurance', Icons.verified_user_outlined, (c, s) => [_card(c, 'شركات التأمين', 'Insurance companies', const [F(FK.csv, 'insurance', 'الشركات', 'Companies')], s)]),
  'faq': _Sec('faq', 'الأسئلة الشائعة', 'Questions', Icons.help_outline_rounded, (c, s) => [_card(c, 'الأسئلة الشائعة', 'FAQs', const [F(FK.faqs, 'faqs', 'الأسئلة', 'Questions')], s)]),
  'contact': _Sec('contact', 'تواصل معنا', 'Contact', Icons.call_outlined, (c, s) => [_card(c, 'وسائل التواصل', 'Contact', const [F(FK.ltr, 'phone', 'الهاتف', 'Phone'), F(FK.ltr, 'email', 'البريد', 'Email'), F(FK.ltr, 'whatsapp', 'واتساب', 'WhatsApp'), F(FK.csv, 'extraPhones', 'أرقام أخرى', 'Other phones'), F(FK.ltr, 'facebookUrl', 'فيسبوك', 'Facebook'), F(FK.ltr, 'instagramUrl', 'إنستجرام', 'Instagram'), F(FK.ltr, 'tiktokUrl', 'تيك توك', 'TikTok'), F(FK.ltr, 'websiteUrl', 'الموقع', 'Website')], s)]),
  // ---- shop
  'banners': _Sec('banners', 'البانرات', 'Banners', Icons.view_carousel_outlined, (c, s) => [storeBannersCard(c)]),
  'trust': _Sec('trust', 'مميزات المتجر', 'Highlights', Icons.verified_outlined, (c, s) => [
        _card(c, 'مميزات أسفل البانر', 'Highlights under the banner', const [F(FK.pairs, 'trustTiles', 'المميزات (حتى 6)', 'Highlights (up to 6)')], s,
            pick: (m) => {'trustTiles': [for (final t in (m['trustTiles'] as List? ?? const [])) {'k': (t as Map)['title'], 'v': t['text']}]},
            transform: (b) => {'trustTiles': [for (final t in (b['trustTiles'] as List? ?? const [])) if ('${(t as Map)['k'] ?? ''}'.trim().isNotEmpty) {'title': t['k'], 'text': t['v'] ?? ''}]}),
        _pushNote('اتركها فارغة لعرض المميزات المعتادة (الدفع عند الاستلام، التوصيل، الاسترجاع، الدعم).', 'Leave empty for the standard four (cash on delivery, delivery, returns, support).'),
      ]),
  'categories': _Sec('categories', 'الأقسام', 'Categories', Icons.category_outlined, (c, s) => [TaxonomyPage(ctx: c)]),
  'featured': _Sec('featured', 'مختارات', 'Featured', Icons.star_outline_rounded, (c, s) => [ProductsScreen(ctx: c)], note: 'علّم المنتجات كـ "مميز" من شاشة المنتجات', ),
  'offers': _Sec('offers', 'العروض', 'Offers', Icons.local_offer_outlined, (c, s) => [ProductsScreen(ctx: c)], note: 'المنتجات التي عليها خصم تظهر هنا تلقائيًا'),
  'bestSellers': _Sec('bestSellers', 'الأكثر مبيعًا', 'Best sellers', Icons.trending_up_rounded, (c, s) => [_pushNote('يُحسب تلقائيًا من المبيعات. يمكنك إخفاؤه أو نقله.', 'Calculated from sales. You can hide it or move it.')]),
  'newest': _Sec('newest', 'الأحدث', 'Newest', Icons.fiber_new_rounded, (c, s) => [ProductsScreen(ctx: c)]),
  'whatsapp': _Sec('whatsapp', 'تواصل معنا', 'Chat with us', Icons.chat_outlined, (c, s) => [_card(c, 'واتساب والتواصل', 'WhatsApp and contact', const [F(FK.ltr, 'whatsapp', 'واتساب', 'WhatsApp'), F(FK.ltr, 'supportPhone', 'الهاتف', 'Phone'), F(FK.area, 'announcement', 'إعلان', 'Announcement')], s)]),
  'branches': _Sec('branches', 'الفروع', 'Branches', Icons.store_mall_directory_outlined, (c, s) => [storeBranchesCard(c)]),
};

/// "My page": the public site on top (reloads after every change) and a button for every section below.
/// Drag a section to move it, flip the switch to hide it, tap Edit to change what is inside.
class MyPageScreen extends StatefulWidget {
  const MyPageScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<MyPageScreen> createState() => _MyPageScreenState();
}

class _MyPageScreenState extends State<MyPageScreen> {
  Ctx get ctx => widget.ctx;
  late final WebViewController _web = WebViewController()..setJavaScriptMode(JavaScriptMode.unrestricted)..setBackgroundColor(Colors.white);
  List<String> _order = [], _hidden = [];
  bool _loaded = false, _preview = true, _saving = false, _previewLoading = true;
  Object? _error;

  List<String> get _defaults => ctx.type == 'STORE' ? _storeKeys : _clinicKeys;
  String get _profilePath => ctx.type == 'STORE' ? 'store/profile' : 'clinic/profile';

  @override
  void initState() {
    super.initState();
    _web.setNavigationDelegate(NavigationDelegate(onPageStarted: (_) { if (mounted) setState(() => _previewLoading = true); }, onPageFinished: (_) { if (mounted) setState(() => _previewLoading = false); }));
    _reloadPreview();
    _load();
  }

  void _reloadPreview() => _web.loadRequest(Uri.parse('${ctx.api.baseUrl}/?preview=${DateTime.now().millisecondsSinceEpoch}'));

  Future<void> _load() async {
    try {
      final p = await ctx.api.get(_profilePath) as Map;
      final layout = Map<String, dynamic>.from((p['siteLayout'] as Map?) ?? const {});
      final chosen = ((layout['order'] as List?) ?? const []).cast<String>().where(_defaults.contains).toList();
      if (!mounted) return;
      setState(() { _order = [...{...chosen, ..._defaults}]; _hidden = ((layout['hidden'] as List?) ?? const []).cast<String>(); _loaded = true; _error = null; });
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _saveLayout() async {
    setState(() => _saving = true);
    final ok = await run(context, () => ctx.api.patch(_profilePath, {'siteLayout': {'order': _order, 'hidden': _hidden}}));
    if (!mounted) return;
    setState(() => _saving = false);
    if (ok) { _reloadPreview(); } else { _load(); }
  }

  Future<void> _edit(_Sec sec) async {
    final s = S.of(context);
    HapticFeedback.selectionClick();
    final widgets = sec.editors(ctx, _reloadPreview);
    // a full existing screen (services, products...) opens as a page; the form cards open in a sheet
    if (widgets.length == 1 && (widgets.first is ServicesScreen || widgets.first is ProductsScreen || widgets.first is TaxonomyPage)) {
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(s.ar ? sec.ar : sec.en)), body: widgets.first)));
    } else {
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        showDragHandle: true,
        useSafeArea: true,
        builder: (c) => DraggableScrollableSheet(
          expand: false, initialChildSize: 0.9, minChildSize: 0.5, maxChildSize: 0.95,
          builder: (c, scroll) => ListView(controller: scroll, padding: EdgeInsets.fromLTRB(16, 0, 16, 24 + MediaQuery.of(c).viewInsets.bottom), children: [
            Text(s.ar ? sec.ar : sec.en, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
            if (sec.note != null && s.ar) Padding(padding: const EdgeInsets.only(top: 4), child: Text(sec.note!, style: const TextStyle(color: Color(0xFF6B7C8D)))),
            const SizedBox(height: 8),
            for (final w in widgets) Padding(padding: const EdgeInsets.only(bottom: 12), child: w),
          ]),
        ),
      );
    }
    if (mounted) { _reloadPreview(); _load(); }
  }

  Future<void> _quick(String ar, String en, List<Widget> Function() build) async {
    final s = S.of(context);
    await showModalBottomSheet<void>(
      context: context, isScrollControlled: true, showDragHandle: true, useSafeArea: true,
      builder: (c) => DraggableScrollableSheet(expand: false, initialChildSize: 0.9, minChildSize: 0.5, maxChildSize: 0.95, builder: (c, scroll) => ListView(controller: scroll, padding: EdgeInsets.fromLTRB(16, 0, 16, 24 + MediaQuery.of(c).viewInsets.bottom), children: [
        Text(s.ar ? ar : en, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
        const SizedBox(height: 8),
        ...build(),
      ])),
    );
    if (mounted) _reloadPreview();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final scheme = Theme.of(context).colorScheme;
    final h = MediaQuery.of(context).size.height;
    final shop = ctx.type == 'STORE';
    return Scaffold(
      appBar: AppBar(
        title: Text(s.ar ? 'صفحتي' : 'My page'),
        actions: [
          IconButton(tooltip: s.ar ? 'فتح الموقع' : 'Open site', icon: const Icon(Icons.open_in_new_rounded), onPressed: () => launch('${ctx.api.baseUrl}/')),
          IconButton(tooltip: s.ar ? 'تحديث' : 'Refresh', icon: const Icon(Icons.refresh_rounded), onPressed: _reloadPreview),
        ],
      ),
      body: Column(children: [
        AnimatedContainer(
          duration: const Duration(milliseconds: 320),
          curve: Curves.easeOutCubic,
          height: _preview ? h * 0.40 : 0,
          margin: EdgeInsets.fromLTRB(16, 0, 16, _preview ? 6 : 0),
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(22), border: Border.all(color: const Color(0xFFD5DEE9), width: 2), boxShadow: const [BoxShadow(color: Color(0x22102A43), blurRadius: 16, offset: Offset(0, 6))]),
          child: _preview ? Stack(children: [WebViewWidget(controller: _web), if (_previewLoading) const Positioned(top: 0, left: 0, right: 0, child: LinearProgressIndicator(minHeight: 3))]) : null,
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
          child: Row(children: [
            Expanded(child: OutlinedButton.icon(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 44)), onPressed: () => _quick('الألوان والشعار', 'Colours and logo', () => [brandingCard(ctx)]), icon: const Icon(Icons.palette_outlined), label: Text(s.ar ? 'الألوان والشعار' : 'Colours & logo'))),
            const SizedBox(width: 8),
            Expanded(child: OutlinedButton.icon(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 44)), onPressed: () => _quick(shop ? 'بيانات المتجر' : 'بيانات العيادة', shop ? 'Store details' : 'Clinic details', () => [_card(ctx, shop ? 'اسم المتجر ووصفه' : 'اسم العيادة', shop ? 'Store name and description' : 'Clinic name', shop ? const [F(FK.text, 'storeName', 'اسم المتجر', 'Store name'), F(FK.text, 'shortDescription', 'وصف قصير', 'Short description'), F(FK.area, 'about', 'عن المتجر', 'About'), F(FK.image, 'coverFileId', 'صورة الغلاف', 'Cover photo')] : const [F(FK.text, 'clinicName', 'اسم العيادة', 'Clinic name'), F(FK.text, 'tagline', 'شعار قصير', 'Tagline'), F(FK.image, 'coverFileId', 'صورة الغلاف', 'Cover photo')], _reloadPreview, transform: (b) => {...b, 'coverFileId': b['coverFileId'] ?? ''})]), icon: const Icon(Icons.badge_outlined), label: Text(s.ar ? 'الاسم والصورة' : 'Name & cover'))),
            const SizedBox(width: 4),
            IconButton.filledTonal(tooltip: _preview ? (s.ar ? 'إخفاء المعاينة' : 'Hide preview') : (s.ar ? 'إظهار المعاينة' : 'Show preview'), onPressed: () => setState(() => _preview = !_preview), icon: AnimatedRotation(turns: _preview ? 0.5 : 0, duration: const Duration(milliseconds: 300), child: const Icon(Icons.expand_more_rounded))),
          ]),
        ),
        Padding(padding: const EdgeInsets.fromLTRB(20, 12, 20, 2), child: Row(children: [
          Expanded(child: Text(s.ar ? 'أقسام صفحتك — اسحب للترتيب، وعدّل أو أخفِ أي قسم' : 'Your page sections — drag to reorder, edit or hide any', style: const TextStyle(fontWeight: FontWeight.w800, color: Color(0xFF51667A)))),
          if (_saving) const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
        ])),
        Expanded(
          child: !_loaded
              ? (_error != null ? errorState(_error!, () { setState(() => _error = null); _load(); }) : const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(height: 70), SizedBox(height: 10), Skeleton(height: 70), SizedBox(height: 10), Skeleton(height: 70)])))
              : ReorderableListView.builder(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 30),
                  itemCount: _order.length,
                  buildDefaultDragHandles: false,
                  onReorderStart: (_) => HapticFeedback.lightImpact(),
                  onReorderItem: (from, to) {
                    setState(() { final k = _order.removeAt(from); _order.insert(to, k); });
                    HapticFeedback.selectionClick();
                    _saveLayout();
                  },
                  proxyDecorator: (child, i, a) => Material(color: Colors.transparent, elevation: 8, borderRadius: BorderRadius.circular(20), child: child),
                  itemBuilder: (c, i) {
                    final key = _order[i];
                    final sec = _catalog[key]!;
                    final hidden = _hidden.contains(key);
                    return AnimatedOpacity(
                      key: ValueKey(key),
                      duration: const Duration(milliseconds: 250),
                      opacity: hidden ? 0.5 : 1,
                      child: Card(
                        child: InkWell(
                          borderRadius: BorderRadius.circular(20),
                          onTap: () => _edit(sec),
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(6, 8, 10, 8),
                            child: Row(children: [
                              ReorderableDragStartListener(index: i, child: const Padding(padding: EdgeInsets.all(8), child: Icon(Icons.drag_indicator_rounded, color: Color(0xFF8A97A5)))),
                              Container(padding: const EdgeInsets.all(9), decoration: BoxDecoration(color: scheme.primary.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(12)), child: Icon(sec.icon, color: scheme.primary)),
                              const SizedBox(width: 12),
                              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                                Text(s.ar ? sec.ar : sec.en, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15)),
                                Text(hidden ? (s.ar ? 'مخفي عن الزوار' : 'Hidden from visitors') : (s.ar ? 'ظاهر' : 'Visible'), style: TextStyle(fontSize: 12, color: hidden ? const Color(0xFFD14343) : const Color(0xFF1E9E5A), fontWeight: FontWeight.w700)),
                              ])),
                              Switch(value: !hidden, onChanged: (v) { setState(() { if (v) { _hidden.remove(key); } else { _hidden.add(key); } }); HapticFeedback.selectionClick(); _saveLayout(); }),
                              FilledButton.tonal(style: FilledButton.styleFrom(minimumSize: const Size(0, 38), padding: const EdgeInsets.symmetric(horizontal: 14)), onPressed: () => _edit(sec), child: Text(s.ar ? 'تعديل' : 'Edit')),
                            ]),
                          ),
                        ),
                      ),
                    );
                  },
                ),
        ),
      ]),
    );
  }
}
