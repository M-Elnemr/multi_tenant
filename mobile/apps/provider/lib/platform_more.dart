import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:platform_core/platform_core.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'clinic_panel.dart' show NotificationsScreen;
import 'common.dart';
import 'train_room.dart';

/// The rest of the platform owner's website, as screens in the app: notifications, categories, visitors, the AI assistant's lessons and Meta setup, AI usage and cost,
/// the WhatsApp wallet, the platform's own WhatsApp number, the audit log and account security. Each one talks to the same API as its page on the admin website.
class AdminMore extends StatelessWidget {
  const AdminMore({super.key, required this.api, required this.perms, required this.onSignOut});
  final ApiClient api;
  final List<String> perms;
  final VoidCallback onSignOut;

  Ctx get _ctx => Ctx(api: api, currency: 'EGP', perms: perms, host: '', type: 'PLATFORM');

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    void open(String title, Widget body) => Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(title)), body: body)));
    final items = <(IconData, String, String, VoidCallback)>[
      (Icons.notifications_none_rounded, ar ? 'الإشعارات' : 'Notifications', ar ? 'طلبات الدفع والتنبيهات' : 'Pay requests and alerts', () => open(ar ? 'الإشعارات' : 'Notifications', NotificationsScreen(ctx: _ctx))),
      (Icons.category_rounded, ar ? 'التصنيفات' : 'Categories', ar ? 'قائمة التصنيفات وطلبات المتاجر' : 'The standard list and shops\' requests', () => open(ar ? 'التصنيفات' : 'Categories', TaxonomyScreen(api: api))),
      (Icons.groups_rounded, ar ? 'الزوار' : 'Visitors', ar ? 'زيارات الموقع والأجهزة والدول' : 'Site visits, devices and countries', () => open(ar ? 'الزوار' : 'Visitors', VisitorsScreen(api: api))),
      (Icons.auto_awesome_rounded, ar ? 'المساعد الذكي' : 'AI assistant', ar ? 'دروس البيع وإعداد ميتا' : 'Selling lessons and Meta setup', () => open(ar ? 'المساعد الذكي' : 'AI assistant', AdminAiScreen(api: api))),
      (Icons.insights_rounded, ar ? 'استهلاك الذكاء الاصطناعي' : 'AI usage', ar ? 'التوكنز والتكلفة لكل متجر وموديل' : 'Tokens and cost per shop and model', () => open(ar ? 'استهلاك الذكاء الاصطناعي' : 'AI usage', AiUsageScreen(api: api))),
      (Icons.account_balance_wallet_rounded, ar ? 'محفظة واتساب' : 'WhatsApp wallet', ar ? 'طلبات الشحن والأرصدة' : 'Top-up requests and balances', () => open(ar ? 'محفظة واتساب' : 'WhatsApp wallet', WalletScreen(api: api))),
      (Icons.chat_rounded, ar ? 'واتساب المنصة' : 'Platform WhatsApp', ar ? 'رقم المنصة لإرسال الأكواد' : 'The number that sends the codes', () => open(ar ? 'واتساب المنصة' : 'Platform WhatsApp', PlatformWhatsAppScreen(api: api))),
      (Icons.fact_check_rounded, ar ? 'سجل المراجعة' : 'Audit log', ar ? 'من عمل إيه ومتى' : 'Who did what and when', () => open(ar ? 'سجل المراجعة' : 'Audit log', AuditScreen(api: api))),
      (Icons.shield_rounded, ar ? 'أمان الحساب' : 'Account security', ar ? 'الدخول بخطوتين والخروج من كل الأجهزة' : 'Two-step sign-in and sign out everywhere', () => open(ar ? 'أمان الحساب' : 'Account security', SecurityScreen(api: api, onSignOut: onSignOut))),
    ];
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: items.length,
      separatorBuilder: (_, __) => const SizedBox(height: 10),
      itemBuilder: (_, i) {
        final it = items[i];
        return PressScale(
          onTap: it.$4,
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18), boxShadow: const [BoxShadow(color: Color(0x14102B46), blurRadius: 14, offset: Offset(0, 5))]),
            child: Row(children: [
              CircleAvatar(backgroundColor: Theme.of(context).colorScheme.primary.withValues(alpha: 0.1), child: Icon(it.$1, color: Theme.of(context).colorScheme.primary)),
              const SizedBox(width: 12),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(it.$2, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15)), const SizedBox(height: 2), Text(it.$3, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))])),
              const Icon(Icons.chevron_right_rounded),
            ]),
          ),
        );
      },
    );
  }
}

// ---------------------------------------------------------------- helpers

/// Loads once, shows a spinner / the error with a retry, then builds. [reload] re-runs the load.
class _Loader extends StatefulWidget {
  const _Loader({super.key, required this.load, required this.builder});
  final Future<dynamic> Function() load;
  final Widget Function(BuildContext context, dynamic data, Future<void> Function() reload) builder;
  @override
  State<_Loader> createState() => _LoaderState();
}

class _LoaderState extends State<_Loader> {
  dynamic _data;
  Object? _error;
  bool _loading = true;

  @override
  void initState() { super.initState(); _reload(); }

  Future<void> _reload() async {
    if (mounted) setState(() { _loading = _data == null; _error = null; });
    try { final d = await widget.load(); if (mounted) setState(() { _data = d; _loading = false; }); } catch (e) { if (mounted) setState(() { _error = e; _loading = false; }); }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null && _data == null) return errorState(_error!, _reload);
    return widget.builder(context, _data, _reload);
  }
}

Widget _card(Widget child, {EdgeInsets? padding}) => Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: padding ?? const EdgeInsets.all(14),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18), boxShadow: const [BoxShadow(color: Color(0x14102B46), blurRadius: 14, offset: Offset(0, 5))]),
      child: child,
    );

Widget _stat(String label, String value, {Color? tone}) => Expanded(
      child: Container(
        margin: const EdgeInsets.all(4),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(16), boxShadow: const [BoxShadow(color: Color(0x14102B46), blurRadius: 12, offset: Offset(0, 4))]),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 11.5)),
          const SizedBox(height: 4),
          FittedBox(fit: BoxFit.scaleDown, alignment: AlignmentDirectional.centerStart, child: Text(value, style: TextStyle(fontWeight: FontWeight.w900, fontSize: 18, color: tone))),
        ]),
      ),
    );

String _n(Object? v) {
  final x = (v as num?)?.round() ?? 0;
  return x.toString().replaceAllMapped(RegExp(r'(\d)(?=(\d{3})+$)'), (m) => '${m[1]},');
}

String _usd(Object? v) {
  final x = (v as num?)?.toDouble() ?? 0;
  return '\$${x < 1 ? x.toStringAsFixed(4) : x.toStringAsFixed(2)}';
}

String _egp(Object? minor) => '${(((minor as num?) ?? 0) / 100).toStringAsFixed(2)} EGP';

String _pct(num a, num b) => b > 0 ? '${(a / b * 100).round()}%' : '–';

Future<String?> _ask(BuildContext c, String title, {String? hint, String initial = '', bool secretLike = false, TextInputType? type}) async {
  final ar = AppLocale.isAr;
  final ctl = TextEditingController(text: initial);
  final r = await showDialog<String>(
    context: c,
    builder: (d) => AlertDialog(
      title: Text(title),
      content: TextField(controller: ctl, autofocus: true, keyboardType: type, textDirection: secretLike ? TextDirection.ltr : null, decoration: InputDecoration(hintText: hint)),
      actions: [TextButton(onPressed: () => Navigator.pop(d), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, ctl.text.trim()), child: Text(ar ? 'تأكيد' : 'OK'))],
    ),
  );
  ctl.dispose();
  return r;
}

Future<bool> _confirm(BuildContext c, String text) async {
  final ar = AppLocale.isAr;
  return await showDialog<bool>(
        context: c,
        builder: (d) => AlertDialog(content: Text(text), actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'تأكيد' : 'Confirm'))]),
      ) ==
      true;
}

String _day(DateTime d) => '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

// ---------------------------------------------------------------- categories

class TaxonomyScreen extends StatefulWidget {
  const TaxonomyScreen({super.key, required this.api});
  final ApiClient api;
  @override
  State<TaxonomyScreen> createState() => _TaxonomyScreenState();
}

class _TaxonomyScreenState extends State<TaxonomyScreen> {
  int _gen = 0;
  String _filter = '';

  Future<void> _edit(Map<String, dynamic>? node, {int? parentId}) async {
    final ar = AppLocale.isAr;
    final nameAr = TextEditingController(text: node?['nameAr'] as String? ?? ''), nameEn = TextEditingController(text: node?['nameEn'] as String? ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (d) => AlertDialog(
        title: Text(node != null ? (ar ? 'تعديل التصنيف' : 'Edit category') : (ar ? 'تصنيف جديد' : 'New category')),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: nameAr, decoration: InputDecoration(labelText: ar ? 'الاسم بالعربي' : 'Name in Arabic')),
          const SizedBox(height: 10),
          TextField(controller: nameEn, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ar ? 'الاسم بالإنجليزي' : 'Name in English')),
        ]),
        actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'حفظ' : 'Save'))],
      ),
    );
    final a = nameAr.text.trim(), e = nameEn.text.trim();
    nameAr.dispose();
    nameEn.dispose();
    if (ok != true || a.isEmpty || e.isEmpty || !mounted) return;
    final saved = await run(context, () async {
      if (node != null) { await widget.api.patch('platform/taxonomy/${node['id']}', {'nameAr': a, 'nameEn': e}); }
      else { await widget.api.post('platform/taxonomy', {'parentId': parentId, 'nameAr': a, 'nameEn': e}); }
    });
    if (saved && mounted) setState(() => _gen++);
  }

  Future<void> _toggle(Map<String, dynamic> n) async {
    if (await run(context, () async { await widget.api.patch('platform/taxonomy/${n['id']}', {'isHidden': n['isHidden'] != true}); }) && mounted) setState(() => _gen++);
  }

  Future<void> _review(Map<String, dynamic> r) async {
    final ar = AppLocale.isAr;
    final nameAr = TextEditingController(text: '${r['name'] ?? ''}'), nameEn = TextEditingController(), note = TextEditingController();
    final approve = await showDialog<bool?>(
      context: context,
      builder: (d) => AlertDialog(
        title: Text('${r['name']}'),
        content: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${r['shopName'] ?? ''}${(r['note'] ?? '').toString().isEmpty ? '' : ' · ${r['note']}'}', style: const TextStyle(color: Color(0xFF6B7C8D))),
            const SizedBox(height: 10),
            TextField(controller: nameAr, decoration: InputDecoration(labelText: ar ? 'الاسم بالعربي' : 'Name in Arabic')),
            const SizedBox(height: 10),
            TextField(controller: nameEn, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ar ? 'الاسم بالإنجليزي' : 'Name in English')),
            const SizedBox(height: 10),
            TextField(controller: note, decoration: InputDecoration(labelText: ar ? 'ملاحظة للمتجر' : 'Note to the shop')),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(d), child: Text(ar ? 'إغلاق' : 'Close')),
          TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'رفض' : 'Reject')),
          FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'قبول' : 'Approve')),
        ],
      ),
    );
    final body = {'approve': approve, 'parentId': r['parentId'], 'nameAr': nameAr.text.trim(), 'nameEn': nameEn.text.trim(), 'note': note.text.trim()};
    nameAr.dispose(); nameEn.dispose(); note.dispose();
    if (approve == null || !mounted) return;
    if (approve && (body['nameAr'] as String).isEmpty || approve && (body['nameEn'] as String).isEmpty) { toast(context, ar ? 'اكتب الاسمين بالعربي والإنجليزي' : 'Write both names', isError: true); return; }
    if (await run(context, () async { await widget.api.post('platform/taxonomy/requests/${r['id']}/decision', body); }) && mounted) setState(() => _gen++);
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(onPressed: () => _edit(null), icon: const Icon(Icons.add_rounded), label: Text(ar ? 'تصنيف' : 'Category')),
      body: _Loader(
        key: ValueKey('tx$_gen'),
        load: () async => [await widget.api.get('platform/taxonomy'), await widget.api.get('platform/taxonomy/requests', query: {'status': 'PENDING'})],
        builder: (context, data, reload) {
          final nodes = (data[0] as List).cast<Map<String, dynamic>>();
          final reqs = (data[1] as List).cast<Map<String, dynamic>>();
          final f = _filter.trim().toLowerCase();
          final shown = nodes.where((n) => f.isEmpty || '${n['nameAr']}'.contains(_filter.trim()) || '${n['nameEn']}'.toLowerCase().contains(f) || '${n['slug']}'.contains(f)).toList();
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 90), children: [
              if (reqs.isNotEmpty)
                _card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('📬 ${ar ? 'طلبات المتاجر' : 'Shops\' requests'} (${reqs.length})', style: const TextStyle(fontWeight: FontWeight.w900)),
                  for (final r in reqs) ListTile(contentPadding: EdgeInsets.zero, title: Text('${r['name']}'), subtitle: Text('${r['shopName'] ?? ''}'), trailing: FilledButton.tonal(onPressed: () => _review(r), child: Text(ar ? 'مراجعة' : 'Review'))),
                ])),
              TextField(decoration: InputDecoration(prefixIcon: const Icon(Icons.search_rounded), hintText: ar ? 'بحث' : 'Search'), onChanged: (v) => setState(() => _filter = v)),
              const SizedBox(height: 10),
              for (final n in shown)
                Padding(
                  padding: EdgeInsetsDirectional.only(start: ((n['level'] as num?)?.toInt() ?? 1).clamp(1, 3) * 14.0 - 14),
                  child: _card(
                    padding: const EdgeInsets.fromLTRB(12, 8, 6, 8),
                    Row(children: [
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${n['icon'] ?? ''} ${n['nameAr']}', style: TextStyle(fontWeight: FontWeight.w800, color: n['isHidden'] == true ? const Color(0xFF9AA9BA) : null)),
                        Text('${n['nameEn']} · ${n['ownProducts'] ?? 0} ${ar ? 'منتج' : 'products'}${n['isHidden'] == true ? ' · ${ar ? 'مخفي' : 'hidden'}' : ''}', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)),
                      ])),
                      PopupMenuButton<String>(
                        onSelected: (v) { if (v == 'edit') { _edit(n); } else if (v == 'sub') { _edit(null, parentId: (n['id'] as num).toInt()); } else { _toggle(n); } },
                        itemBuilder: (_) => [
                          PopupMenuItem(value: 'edit', child: Text(ar ? 'تعديل' : 'Edit')),
                          if (((n['level'] as num?)?.toInt() ?? 1) < 3) PopupMenuItem(value: 'sub', child: Text(ar ? 'إضافة تصنيف فرعي' : 'Add subcategory')),
                          PopupMenuItem(value: 'hide', child: Text(n['isHidden'] == true ? (ar ? 'إظهار' : 'Show') : (ar ? 'إخفاء' : 'Hide'))),
                        ],
                      ),
                    ]),
                  ),
                ),
            ]),
          );
        },
      ),
    );
  }
}

// ---------------------------------------------------------------- visitors

class VisitorsScreen extends StatefulWidget {
  const VisitorsScreen({super.key, required this.api});
  final ApiClient api;
  @override
  State<VisitorsScreen> createState() => _VisitorsScreenState();
}

class _VisitorsScreenState extends State<VisitorsScreen> {
  DateTime _from = DateTime.now().subtract(const Duration(days: 6)), _to = DateTime.now();

  Future<void> _pick(bool from) async {
    final d = await showDatePicker(context: context, initialDate: from ? _from : _to, firstDate: DateTime(2025), lastDate: DateTime.now());
    if (d != null) setState(() { if (from) { _from = d; if (_to.isBefore(d)) _to = d; } else { _to = d; if (_from.isAfter(d)) _from = d; } });
  }

  String get _end => _day(_to.add(const Duration(days: 1)));   // the API's "to" is exclusive

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    final q = {'from': _day(_from), 'to': _end};
    return PagedList(
      key: ValueKey('${_day(_from)}${_day(_to)}'),
      api: widget.api,
      path: 'platform/analytics/visitors',
      query: q,
      searchHint: ar ? 'صفحة (مثلاً /products)' : 'Page (e.g. /products)',
      searchParam: 'path',
      emptyText: ar ? 'مفيش زوار في الفترة دي' : 'No visitors in this period',
      header: Column(children: [
        Row(children: [
          Expanded(child: OutlinedButton.icon(onPressed: () => _pick(true), icon: const Icon(Icons.event_rounded), label: Text('${ar ? 'من' : 'From'} ${_day(_from)}'))),
          const SizedBox(width: 8),
          Expanded(child: OutlinedButton.icon(onPressed: () => _pick(false), icon: const Icon(Icons.event_rounded), label: Text('${ar ? 'إلى' : 'To'} ${_day(_to)}'))),
        ]),
        _Loader(key: ValueKey('vs${_day(_from)}${_day(_to)}'), load: () => widget.api.get('platform/analytics/summary', query: q), builder: (context, s, _) {
          final t = (s['totals'] as Map?) ?? {};
          String ms(Object? v) { final sec = ((v as num?) ?? 0) ~/ 1000; return sec < 60 ? '${sec}s' : '${sec ~/ 60}m ${sec % 60}s'; }
          Widget top(String title, Object? rows) => (rows is List && rows.isNotEmpty)
              ? _card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(title, style: const TextStyle(fontWeight: FontWeight.w900)),
                  const SizedBox(height: 6),
                  for (final r in rows.take(6)) Padding(padding: const EdgeInsets.symmetric(vertical: 2), child: Row(children: [Expanded(child: Text('${r['name']}', maxLines: 1, overflow: TextOverflow.ellipsis, textDirection: TextDirection.ltr)), Text(_n(r['views']), style: const TextStyle(fontWeight: FontWeight.w800))])),
                ]))
              : const SizedBox.shrink();
          return Padding(padding: const EdgeInsets.only(top: 8), child: Column(children: [
            Row(children: [_stat(ar ? 'زوار' : 'Visitors', _n(t['visitors'])), _stat(ar ? 'جلسات' : 'Sessions', _n(t['sessions']))]),
            Row(children: [_stat(ar ? 'مشاهدات' : 'Page views', _n(t['pageViews'])), _stat(ar ? 'متوسط الوقت' : 'Avg time', ms(t['avgMs']))]),
            top(ar ? 'أكثر الصفحات' : 'Top pages', s['topPages']),
            top(ar ? 'الدول' : 'Countries', s['countries']),
            top(ar ? 'الأجهزة' : 'Devices', s['devices']),
            top(ar ? 'المتصفحات' : 'Browsers', s['browsers']),
            top(ar ? 'المصادر' : 'Referrers', s['referrers']),
          ]));
        }),
      ]),
      itemBuilder: (context, v, reload) => _card(
        InkWell(
          onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(ar ? 'رحلة الزائر' : 'Visitor timeline')), body: _Timeline(api: widget.api, id: '${v['visitorId']}')))),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(dotted([v['country'], v['city'], v['deviceType'], v['browser']]), style: const TextStyle(fontWeight: FontWeight.w800)),
            const SizedBox(height: 2),
            Text('${_n(v['pageViews'])} ${ar ? 'مشاهدة' : 'views'} · ${_n(v['sessions'])} ${ar ? 'جلسة' : 'sessions'} · ${fmtDate('${v['lastSeen']}')}', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
            if (v['ip'] != null) Text('${v['ip']}', textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF9AA9BA), fontSize: 12)),
          ]),
        ),
      ),
    );
  }
}

class _Timeline extends StatelessWidget {
  const _Timeline({required this.api, required this.id});
  final ApiClient api;
  final String id;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return _Loader(load: () => api.get('platform/analytics/visitors/$id'), builder: (context, d, reload) {
      final views = ((d['views'] as List?) ?? []).cast<Map<String, dynamic>>();
      if (views.isEmpty) return emptyState(ar ? 'مفيش بيانات' : 'No data');
      final last = views.first;
      return ListView(padding: const EdgeInsets.all(16), children: [
        _card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          kv('IP', '${last['ip'] ?? '-'}', ltr: true),
          kv(ar ? 'الدولة' : 'Country', dotted([last['country'], last['city']])),
          kv(ar ? 'الجهاز' : 'Device', dotted([last['deviceType'], last['browser'], last['os']])),
          kv(ar ? 'اللغة' : 'Language', '${last['language'] ?? '-'}'),
        ])),
        for (final v in views)
          ListTile(
            dense: true,
            title: Text('${v['path']}${(v['query'] ?? '').toString().isEmpty ? '' : '?${v['query']}'}', textDirection: TextDirection.ltr, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text('${fmtDate('${v['viewedAt']}')}${v['durationMs'] != null ? ' · ${((v['durationMs'] as num) / 1000).round()}s' : ''}'),
          ),
      ]);
    });
  }
}

// ---------------------------------------------------------------- AI assistant: lessons and Meta setup

/// Two tabs: the lessons and Meta setup, and the training room (chat as a customer, turn a reply into a lesson).
class AdminAiScreen extends StatelessWidget {
  const AdminAiScreen({super.key, required this.api});
  final ApiClient api;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return DefaultTabController(
      length: 2,
      child: Column(children: [
        TabBar(tabs: [Tab(text: ar ? 'الدروس' : 'Lessons'), Tab(text: ar ? 'غرفة التدريب' : 'Training room')]),
        Expanded(child: TabBarView(children: [_AdminLessons(api: api), TrainRoomScreen(api: api)])),
      ]),
    );
  }
}

class _AdminLessons extends StatefulWidget {
  const _AdminLessons({required this.api});
  final ApiClient api;
  @override
  State<_AdminLessons> createState() => _AdminAiScreenState();
}

class _AdminAiScreenState extends State<_AdminLessons> {
  int _gen = 0;

  Future<void> _add() async {
    final ar = AppLocale.isAr;
    final ins = TextEditingController(), q = TextEditingController(), a = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (d) => AlertDialog(
        title: Text(ar ? 'درس جديد' : 'New lesson'),
        content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: ins, minLines: 2, maxLines: 4, decoration: InputDecoration(labelText: ar ? 'القاعدة أو التعليمات' : 'Rule or instruction')),
          const SizedBox(height: 10),
          TextField(controller: q, decoration: InputDecoration(labelText: ar ? 'مثال: سؤال العميل (اختياري)' : 'Example customer question (optional)')),
          const SizedBox(height: 10),
          TextField(controller: a, minLines: 2, maxLines: 4, decoration: InputDecoration(labelText: ar ? 'الرد المثالي (اختياري)' : 'Ideal reply (optional)')),
        ])),
        actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'حفظ' : 'Save'))],
      ),
    );
    final body = {'instruction': ins.text.trim(), 'exampleQuestion': q.text.trim(), 'exampleAnswer': a.text.trim()};
    ins.dispose(); q.dispose(); a.dispose();
    if (ok != true || !mounted) return;
    if (await run(context, () async { await widget.api.post('platform/chat/lessons', body); }) && mounted) setState(() => _gen++);
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(onPressed: _add, icon: const Icon(Icons.add_rounded), label: Text(ar ? 'درس' : 'Lesson')),
      body: _Loader(
        key: ValueKey('ai$_gen'),
        load: () async => [await widget.api.get('platform/chat/lessons'), await widget.api.get('platform/chat/meta')],
        builder: (context, data, reload) {
          final lessons = (data[0] as List).cast<Map<String, dynamic>>();
          final meta = data[1] as Map<String, dynamic>;
          Widget copyRow(String label, Object? v) => Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Row(children: [
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(label, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)), SelectableText('$v', textDirection: TextDirection.ltr, style: const TextStyle(fontFamily: 'monospace', fontSize: 12))])),
                  IconButton(icon: const Icon(Icons.copy_rounded, size: 18), onPressed: () { Clipboard.setData(ClipboardData(text: '$v')); toast(context, ar ? 'اتنسخ' : 'Copied'); }),
                ]),
              );
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 90), children: [
              _card(Text(ar ? 'الدروس دي بتتطبق على كل المتاجر. القواعد الأساسية للمنصة بتغلب أي درس.' : 'These lessons apply to every shop. The platform\'s core rules always win over a lesson.', style: const TextStyle(color: Color(0xFF6B7C8D)))),
              if (lessons.isEmpty) emptyState(ar ? 'مفيش دروس لسه' : 'No lessons yet'),
              for (final l in lessons)
                _card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(child: Text('${l['instruction'] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w700))),
                    IconButton(icon: const Icon(Icons.delete_outline_rounded), onPressed: () async {
                      if (!await _confirm(context, ar ? 'تحذف الدرس ده؟' : 'Delete this lesson?') || !mounted) return;
                      if (await run(this.context, () async { await widget.api.delete('platform/chat/lessons/${l['id']}'); }) && mounted) setState(() => _gen++);
                    }),
                  ]),
                  if ((l['exampleQuestion'] ?? '').toString().isNotEmpty) Text('${ar ? 'العميل' : 'Customer'}: ${l['exampleQuestion']}', style: const TextStyle(fontSize: 12.5, color: Color(0xFF6B7C8D))),
                  if ((l['exampleAnswer'] ?? '').toString().isNotEmpty) Text('${ar ? 'الرد' : 'Reply'}: ${l['exampleAnswer']}', style: const TextStyle(fontSize: 12.5, color: Color(0xFF6B7C8D))),
                ])),
              _card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(ar ? 'ربط فيسبوك / إنستجرام / واتساب (ميتا)' : 'Facebook / Instagram / WhatsApp (Meta) setup', style: const TextStyle(fontWeight: FontWeight.w900)),
                if (meta['configured'] != true) Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'ميتا مش متظبطة على السيرفر لسه.' : 'Meta is not set up on the server yet.', style: const TextStyle(color: Color(0xFFD98200)))),
                if (meta['configured'] == true) ...[
                  copyRow('Webhook URL', meta['webhookUrl']),
                  copyRow('Verify token', meta['verifyToken']),
                  copyRow('Redirect URI', meta['redirectUri']),
                  if (meta['loginReady'] != true) Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'ناقص App ID لتسجيل الدخول بفيسبوك.' : 'The app id for Facebook login is missing.', style: const TextStyle(color: Color(0xFFD98200)))),
                ],
              ])),
            ]),
          );
        },
      ),
    );
  }
}

// ---------------------------------------------------------------- AI usage

class AiUsageScreen extends StatefulWidget {
  const AiUsageScreen({super.key, required this.api});
  final ApiClient api;
  @override
  State<AiUsageScreen> createState() => _AiUsageScreenState();
}

class _AiUsageScreenState extends State<AiUsageScreen> {
  DateTime _day = DateTime.now();
  String _model = 'all';   // 'all' | 'glm' | 'qwen' | 'anthropic': every number below counts only that model

  bool get _today => _day.year == DateTime.now().year && _day.month == DateTime.now().month && _day.day == DateTime.now().day;

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
        child: Row(children: [
          IconButton(icon: const Icon(Icons.chevron_left_rounded), onPressed: () => setState(() => _day = _day.subtract(const Duration(days: 1)))),
          Expanded(child: OutlinedButton(onPressed: () async {
            final d = await showDatePicker(context: context, initialDate: _day, firstDate: DateTime(2025), lastDate: DateTime.now());
            if (d != null) setState(() => _day = d);
          }, child: Text(_day.toString().substring(0, 10)))),
          IconButton(icon: const Icon(Icons.chevron_right_rounded), onPressed: _today ? null : () => setState(() => _day = _day.add(const Duration(days: 1)))),
        ]),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
        child: SizedBox(
          width: double.infinity,
          child: SegmentedButton<String>(
            showSelectedIcon: false,
            segments: [ButtonSegment(value: 'all', label: Text(ar ? 'الكل' : 'All')), const ButtonSegment(value: 'glm', label: Text('GLM')), const ButtonSegment(value: 'qwen', label: Text('Qwen')), const ButtonSegment(value: 'anthropic', label: Text('Claude'))],
            selected: {_model},
            onSelectionChanged: (v) => setState(() => _model = v.first),
          ),
        ),
      ),
      Expanded(
        child: _Loader(
          key: ValueKey('u${_day.toString().substring(0, 10)}$_model'),
          load: () => widget.api.get('platform/ai-usage', query: {'day': _day.toString().substring(0, 10), if (_model != 'all') 'model': _model}),
          builder: (context, d, reload) {
            final t = d['totals'] as Map<String, dynamic>;
            final models = ((d['byModel'] as List?) ?? []).cast<Map<String, dynamic>>();
            final shops = ((d['shops'] as List?) ?? []).cast<Map<String, dynamic>>();
            return RefreshIndicator(
              onRefresh: reload,
              child: ListView(padding: const EdgeInsets.fromLTRB(12, 8, 12, 24), children: [
                Row(children: [_stat(ar ? 'توكنز' : 'Tokens', _n(((t['input'] as num?) ?? 0) + ((t['output'] as num?) ?? 0))), _stat(ar ? 'طلبات' : 'Requests', _n(t['requests']))]),
                Row(children: [_stat(ar ? 'رد فوري' : 'Instant', _n(t['instant']), tone: const Color(0xFF1E9E5A)), _stat(ar ? 'توفير الكاش' : 'Cache hit', _pct((t['cached'] as num?) ?? 0, (t['input'] as num?) ?? 0)), _stat(ar ? 'التكلفة' : 'Cost', _usd(t['costUsd']), tone: const Color(0xFFD14343))]),
                const SizedBox(height: 8),
                Text(ar ? 'كل موديل على حدة' : 'Each model', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
                const SizedBox(height: 6),
                for (final key in const ['glm', 'qwen', 'anthropic'])
                  () {
                    final m = models.cast<Map<String, dynamic>?>().firstWhere((x) => x?['model'] == key, orElse: () => null);
                    final tokens = m == null ? 0 : ((m['input'] as num?) ?? 0) + ((m['output'] as num?) ?? 0);
                    final reqs = (m?['requests'] as num?) ?? 0;
                    return PressScale(
                      onTap: () => setState(() => _model = key),
                      child: Container(
                        margin: const EdgeInsets.only(bottom: 10),
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: _model == key ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.08) : Colors.white,
                          border: Border.all(color: _model == key ? Theme.of(context).colorScheme.primary : const Color(0xFFE2E8F0)),
                          borderRadius: BorderRadius.circular(18),
                        ),
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text(key == 'glm' ? 'GLM' : key == 'qwen' ? 'Qwen' : 'Claude', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
                          const SizedBox(height: 4),
                          Text('${_n(tokens)} ${ar ? 'توكن' : 'tokens'}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 22)),
                          if (m == null) Text(ar ? 'مفيش استهلاك في اليوم ده' : 'No usage on this day', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))
                          else ...[
                            Text('${_n(m['input'])} in · ${_n(m['output'])} out · ${_n(reqs)} ${ar ? 'طلب' : 'requests'} · ${_n(m['instant'])} ${ar ? 'فوري' : 'instant'}', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
                            Text('${ar ? 'توفير الكاش' : 'Cache hit'} ${_pct((m['cached'] as num?) ?? 0, (m['input'] as num?) ?? 0)} · ${_n(m['fallbacks'])} ${ar ? 'رجع للأساسي' : 'fell back'}${reqs > 0 ? ' · ${_n(tokens / reqs)} ${ar ? 'توكن/طلب' : 'tokens/request'}' : ''}', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
                          ],
                          const SizedBox(height: 4),
                          Text(_usd(m?['costUsd']), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18, color: Color(0xFFD14343))),
                        ]),
                      ),
                    );
                  }(),
                const SizedBox(height: 8),
                Text(ar ? 'المتاجر' : 'Shops', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
                const SizedBox(height: 6),
                for (final s in shops)
                  PressScale(
                    onTap: s['tenantId'] == null ? null : () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text('${s['name']}')), body: _ShopUsage(api: widget.api, id: '${s['tenantId']}', day: _day.toString().substring(0, 10), model: _model)))),
                    child: _card(Row(children: [
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(s['tenantId'] == null ? (ar ? 'على مستوى المنصة' : 'Platform level') : '${s['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                        Text('${_n(s['conversations'])} ${ar ? 'محادثة' : 'conversations'} · ${_n(s['requests'])} ${ar ? 'طلب' : 'requests'}', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
                      ])),
                      Column(crossAxisAlignment: CrossAxisAlignment.end, children: [Text(_n(((s['input'] as num?) ?? 0) + ((s['output'] as num?) ?? 0)), style: const TextStyle(fontWeight: FontWeight.w900)), Text(_usd(s['costUsd']), style: const TextStyle(color: Color(0xFFD14343), fontSize: 12.5))]),
                    ])),
                  ),
              ]),
            );
          },
        ),
      ),
    ]);
  }
}

class _ShopUsage extends StatelessWidget {
  const _ShopUsage({required this.api, required this.id, required this.day, this.model = 'all'});
  final ApiClient api;
  final String id, day, model;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return _Loader(load: () => api.get('platform/ai-usage/shops/$id', query: {'day': day, if (model != 'all') 'model': model}), builder: (context, d, reload) {
      final t = d['totals'] as Map<String, dynamic>;
      final convs = ((d['conversations'] as List?) ?? []).cast<Map<String, dynamic>>();
      final models = ((d['byModel'] as List?) ?? []).cast<Map<String, dynamic>>();
      return ListView(padding: const EdgeInsets.all(12), children: [
        Row(children: [_stat(ar ? 'توكنز' : 'Tokens', _n(((t['input'] as num?) ?? 0) + ((t['output'] as num?) ?? 0))), _stat(ar ? 'طلبات' : 'Requests', _n(t['requests'])), _stat(ar ? 'التكلفة' : 'Cost', _usd(t['costUsd']), tone: const Color(0xFFD14343))]),
        for (final m in models) ListTile(dense: true, title: Text('${m['model']}'), trailing: Text('${_n(m['requests'])} · ${_usd(m['costUsd'])}')),
        const Divider(),
        for (final c in convs)
          ListTile(
            title: Text('${c['firstMessage'] ?? '—'}', maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text('${c['channel'] ?? ''} · ${c['model'] ?? ''} · ${_n(c['requests'])} ${ar ? 'طلب' : 'requests'}'),
            trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [Text(_n(((c['input'] as num?) ?? 0) + ((c['output'] as num?) ?? 0)), style: const TextStyle(fontWeight: FontWeight.w800)), Text(_usd(c['costUsd']), style: const TextStyle(fontSize: 12, color: Color(0xFFD14343)))]),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(ar ? 'المحادثة' : 'Conversation')), body: _ConversationUsage(api: api, id: '${c['conversationId']}')))),
          ),
      ]);
    });
  }
}

class _ConversationUsage extends StatelessWidget {
  const _ConversationUsage({required this.api, required this.id});
  final ApiClient api;
  final String id;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return _Loader(load: () => api.get('platform/ai-usage/conversations/$id'), builder: (context, d, reload) {
      final replies = ((d['replies'] as List?) ?? []).cast<Map<String, dynamic>>();
      return ListView(padding: const EdgeInsets.all(12), children: [
        for (final r in replies)
          _card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${r['preview'] ?? '—'}', style: const TextStyle(fontWeight: FontWeight.w800)),
            const SizedBox(height: 4),
            Text(r['instant'] == true ? (ar ? 'رد فوري بدون موديل' : 'Instant reply, no model') : '${r['model'] ?? ''}${r['fallback'] == true ? ' (${ar ? 'رجع للأساسي' : 'fell back'})' : ''} · ${_n(((r['input'] as num?) ?? 0) + ((r['output'] as num?) ?? 0))} ${ar ? 'توكن' : 'tokens'} · ${_usd(r['costUsd'])}', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
            for (final c in ((r['calls'] as List?) ?? []).cast<Map<String, dynamic>>())
              Padding(padding: const EdgeInsets.only(top: 3), child: Text('• ${((c['tools'] as List?) ?? []).isEmpty ? (ar ? 'الرد النهائي' : 'final answer') : (c['tools'] as List).join(', ')}  ${_n(c['in'])} in (${_n(c['cached'])} ${ar ? 'كاش' : 'cached'}) · ${_n(c['out'])} out', textDirection: TextDirection.ltr, style: const TextStyle(fontSize: 11.5, fontFamily: 'monospace'))),
          ])),
      ]);
    });
  }
}

// ---------------------------------------------------------------- WhatsApp wallet

class WalletScreen extends StatelessWidget {
  const WalletScreen({super.key, required this.api});
  final ApiClient api;

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.account_balance_wallet_rounded),
        label: Text(ar ? 'الأرصدة' : 'Balances'),
        onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(ar ? 'الأرصدة' : 'Balances')), body: _Balances(api: api)))),
      ),
      body: PagedList(
        api: api,
        path: 'platform/wallet/topups',
        filterParam: 'status',
        filters: [('', ar ? 'الكل' : 'All'), ('PENDING', ar ? 'بانتظار' : 'Pending'), ('APPROVED', ar ? 'مقبول' : 'Approved'), ('REJECTED', ar ? 'مرفوض' : 'Rejected')],
        emptyText: ar ? 'مفيش طلبات شحن' : 'No top-up requests',
        itemBuilder: (context, r, reload) => _card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [Expanded(child: Text('${r['tenantName']}', style: const TextStyle(fontWeight: FontWeight.w900))), StatusPill('${r['status']}')]),
          const SizedBox(height: 4),
          Text('${_egp(r['amountMinor'])} · ${ar ? 'الرصيد' : 'Balance'} ${_egp(r['balanceMinor'])}', style: const TextStyle(fontWeight: FontWeight.w700)),
          if (r['phone'] != null) TextButton.icon(onPressed: () => dial('${r['phone']}'), icon: const Icon(Icons.call_rounded, size: 18), label: Text('${r['phone']}', textDirection: TextDirection.ltr)),
          if ('${r['status']}' == 'PENDING')
            Row(children: [
              FilledButton(onPressed: () async {
                final note = await _ask(context, ar ? 'ملاحظة (اختياري)' : 'Note (optional)');
                if (note == null || !context.mounted) return;
                if (await run(context, () async { await api.post('platform/wallet/topups/${r['id']}', {'approve': true, 'adminNote': note.isEmpty ? null : note}); })) await reload();
              }, child: Text(ar ? 'قبول' : 'Approve')),
              const SizedBox(width: 8),
              OutlinedButton(onPressed: () async {
                final note = await _ask(context, ar ? 'سبب الرفض (اختياري)' : 'Reason (optional)');
                if (note == null || !context.mounted) return;
                if (await run(context, () async { await api.post('platform/wallet/topups/${r['id']}', {'approve': false, 'adminNote': note.isEmpty ? null : note}); })) await reload();
              }, child: Text(ar ? 'رفض' : 'Reject')),
            ]),
        ])),
      ),
    );
  }
}

class _Balances extends StatelessWidget {
  const _Balances({required this.api});
  final ApiClient api;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return PagedList(
      api: api,
      path: 'platform/wallet/balances',
      searchHint: ar ? 'ابحث باسم المتجر' : 'Search shop',
      itemBuilder: (context, b, reload) => _card(
        InkWell(
          onTap: () async {
            final amount = await _ask(context, '${b['tenantName']} — ${ar ? 'تعديل الرصيد (جنيه، سالب للخصم)' : 'Adjust balance (EGP, negative to deduct)'}', type: const TextInputType.numberWithOptions(decimal: true, signed: true));
            final egp = double.tryParse(amount ?? '');
            if (egp == null || egp == 0 || !context.mounted) return;
            final note = await _ask(context, ar ? 'ملاحظة (اختياري)' : 'Note (optional)');
            if (note == null || !context.mounted) return;
            if (await run(context, () async { await api.post('platform/wallet/adjust', {'tenantId': b['tenantId'], 'amountMinor': (egp * 100).round(), 'note': note.isEmpty ? null : note}); })) await reload();
          },
          child: Row(children: [Expanded(child: Text('${b['tenantName']}', style: const TextStyle(fontWeight: FontWeight.w800))), Text(_egp(b['balanceMinor']), style: const TextStyle(fontWeight: FontWeight.w900))]),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- platform WhatsApp number

class PlatformWhatsAppScreen extends StatefulWidget {
  const PlatformWhatsAppScreen({super.key, required this.api});
  final ApiClient api;
  @override
  State<PlatformWhatsAppScreen> createState() => _PlatformWhatsAppScreenState();
}

class _PlatformWhatsAppScreenState extends State<PlatformWhatsAppScreen> {
  Map<String, dynamic>? _info, _qr;
  bool _loading = true;
  Timer? _timer;
  final _number = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(const Duration(seconds: 6), (_) { if (_waiting) _poll(); });
  }

  @override
  void dispose() { _timer?.cancel(); _number.dispose(); super.dispose(); }

  bool get _waiting => _info?['enabled'] == true && _info?['status'] != 'CONNECTED';

  Future<void> _load() async {
    try { final r = await widget.api.get('platform/whatsapp') as Map<String, dynamic>; if (mounted) setState(() { _info = r; _loading = false; }); if (_waiting) _poll(); } catch (_) { if (mounted) setState(() => _loading = false); }
  }

  Future<void> _poll() async {
    try { final q = await widget.api.get('platform/whatsapp/qr') as Map<String, dynamic>; if (mounted) setState(() { _qr = q; _info = {...?_info, 'status': q['status'], 'phone': q['phone']}; }); } catch (_) { /* the next tick tries again */ }
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    if (_loading) return const Center(child: CircularProgressIndicator());
    final i = _info;
    if (i == null || i['available'] != true) return emptyState(ar ? 'الميزة دي مش متاحة على السيرفر لسه.' : 'Not available on this server yet.');
    final enabled = i['enabled'] == true, connected = enabled && i['status'] == 'CONNECTED';
    final qr = _qr?['qr'] as String?;
    return ListView(padding: const EdgeInsets.all(16), children: [
      _card(Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          Expanded(child: Text(ar ? 'رقم واتساب المنصة' : 'Platform WhatsApp number', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
          if (enabled) Chip(visualDensity: VisualDensity.compact, label: Text(connected ? (ar ? 'مربوط' : 'Linked') : (ar ? 'في الانتظار' : 'Waiting')), backgroundColor: connected ? const Color(0xFFDCFCE7) : const Color(0xFFFEF3C7)),
        ]),
        const SizedBox(height: 8),
        Text(ar ? 'الرقم ده بيبعت أكواد التأكيد اللي بتثبت إن رقم الموبايل بتاع الشخص اللي بيسجل. مجاني ومربوط بكود QR. استخدم رقم مخصص.' : 'This number sends the one-time codes that prove a phone belongs to the person registering. It is free and linked with a QR code. Use a dedicated number.', style: const TextStyle(color: Color(0xFF6B7C8D))),
        const SizedBox(height: 12),
        if (!enabled) FilledButton(onPressed: () async {
          Map<String, dynamic>? r;
          if (await run(context, () async { r = await widget.api.post('platform/whatsapp/enable', {}) as Map<String, dynamic>; }) && mounted) setState(() { _qr = r; _info = {...i, 'enabled': true, 'status': r!['status']}; });
        }, child: Text(ar ? 'شغّل واربط الرقم' : 'Turn on and link the number')),
        if (enabled && !connected) ...[
          Text(ar ? '١. على التليفون: واتساب > الإعدادات > الأجهزة المرتبطة.\n٢. اضغط «ربط جهاز» وامسح الكود.' : '1. On the phone: WhatsApp > Settings > Linked devices.\n2. Press “Link a device” and scan the code.'),
          const SizedBox(height: 10),
          if (qr != null && qr.contains(',')) Center(child: Container(color: Colors.white, padding: const EdgeInsets.all(8), child: Image.memory(base64Decode(qr.split(',').last), width: 220, height: 220, gaplessPlayback: true)))
          else Center(child: Text(ar ? 'بنجهز كود QR…' : 'Preparing the QR code…')),
        ],
        if (connected) ...[
          Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFDCFCE7), borderRadius: BorderRadius.circular(12)), child: Text('${ar ? 'مربوط' : 'Linked'}${i['phone'] != null ? '  +${i['phone']}' : ''}', style: const TextStyle(fontWeight: FontWeight.w800))),
          const SizedBox(height: 10),
          TextField(controller: _number, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ar ? 'ابعت رسالة تجريبية لرقم' : 'Send a test message to', hintText: '201012345678')),
          const SizedBox(height: 8),
          OutlinedButton(onPressed: () async { if (await run(context, () async { await widget.api.post('platform/whatsapp/test', {'number': _number.text.trim()}); }) && context.mounted) toast(context, ar ? 'اتبعتت الرسالة' : 'Test message sent'); }, child: Text(ar ? 'ابعت تجريبية' : 'Send test')),
        ],
        if (enabled) ...[
          const Divider(height: 24),
          OutlinedButton(
            style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFD14343)),
            onPressed: () async {
              if (!await _confirm(context, ar ? 'توقف الرقم وتفصل التليفون؟' : 'Turn it off and unlink the phone?') || !mounted) return;
              if (await run(this.context, () async { await widget.api.post('platform/whatsapp/disable', {}); }) && mounted) setState(() { _qr = null; _info = {...i, 'enabled': false, 'status': 'DISCONNECTED', 'phone': null}; });
            },
            child: Text(ar ? 'إيقاف وفصل' : 'Turn off and unlink'),
          ),
        ],
      ])),
    ]);
  }
}

// ---------------------------------------------------------------- audit log

class AuditScreen extends StatelessWidget {
  const AuditScreen({super.key, required this.api});
  final ApiClient api;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return PagedList(
      api: api,
      path: 'platform/audit',
      searchParam: 'action',
      searchHint: ar ? 'فلتر بالإجراء (مثلاً PLAN_CHANGED)' : 'Filter by action (e.g. PLAN_CHANGED)',
      itemBuilder: (context, r, reload) => _card(
        padding: const EdgeInsets.all(12),
        Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${r['action']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w800, fontFamily: 'monospace', fontSize: 13)),
          const SizedBox(height: 2),
          Text('${r['entityType'] ?? ''} · ${fmtDate('${r['occurredAt']}')}${r['tenantId'] != null ? ' · ${'${r['tenantId']}'.substring(0, 8)}' : ''}', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
        ]),
      ),
    );
  }
}

// ---------------------------------------------------------------- account security

class SecurityScreen extends StatefulWidget {
  const SecurityScreen({super.key, required this.api, required this.onSignOut});
  final ApiClient api;
  final VoidCallback onSignOut;
  @override
  State<SecurityScreen> createState() => _SecurityScreenState();
}

class _SecurityScreenState extends State<SecurityScreen> {
  int _gen = 0;
  Map<String, dynamic>? _setup;
  final _code = TextEditingController();

  @override
  void dispose() { _code.dispose(); super.dispose(); }

  Future<void> _recoveryDialog(List codes) => showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (d) {
          final ar = AppLocale.isAr;
          return AlertDialog(
            title: Text(ar ? 'أكواد الاسترداد' : 'Recovery codes'),
            content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(ar ? 'احتفظ بيها في مكان آمن. كل كود بيشتغل مرة واحدة لو ضاع تليفونك، ومش هتظهر تاني.' : 'Keep them somewhere safe. Each works once if you lose your phone. They will not be shown again.'),
              const SizedBox(height: 10),
              SelectableText(codes.join('\n'), textDirection: TextDirection.ltr, style: const TextStyle(fontFamily: 'monospace', fontSize: 15)),
            ]),
            actions: [TextButton(onPressed: () { Clipboard.setData(ClipboardData(text: codes.join('\n'))); }, child: Text(ar ? 'نسخ' : 'Copy')), FilledButton(onPressed: () => Navigator.pop(d), child: Text(ar ? 'حفظتها' : 'I saved them'))],
          );
        },
      );

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return _Loader(
      key: ValueKey('sec$_gen'),
      load: () => widget.api.get('auth/2fa'),
      builder: (context, d, reload) {
        final on = d['enabled'] == true;
        final setup = _setup;
        return ListView(padding: const EdgeInsets.all(16), children: [
          _card(Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(children: [Expanded(child: Text(ar ? 'الدخول بخطوتين' : 'Two-step sign-in', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))), Chip(visualDensity: VisualDensity.compact, label: Text(on ? (ar ? 'شغّال' : 'On') : (ar ? 'مقفول' : 'Off')), backgroundColor: on ? const Color(0xFFDCFCE7) : const Color(0xFFFEF3C7))]),
            const SizedBox(height: 8),
            Text(ar ? 'بعد كلمة المرور بيطلب كود من 6 أرقام من تطبيق مصادقة (Google Authenticator أو Microsoft Authenticator أو Authy)، فلو كلمة المرور اتسرقت لوحدها محدش يقدر يفتح حسابك.' : 'After your password it asks for a 6-digit code from an authenticator app (Google Authenticator, Microsoft Authenticator, Authy), so a stolen password alone cannot open your account.', style: const TextStyle(color: Color(0xFF6B7C8D))),
            const SizedBox(height: 12),
            if (!on && setup == null) FilledButton(onPressed: () async {
              Map<String, dynamic>? r;
              if (await run(context, () async { r = await widget.api.post('auth/2fa/setup', {}) as Map<String, dynamic>; }) && mounted) setState(() { _setup = r; _code.clear(); });
            }, child: Text(ar ? 'شغّل الدخول بخطوتين' : 'Turn on two-step sign-in')),
            if (!on && setup != null) ...[
              Text(ar ? 'امسح الكود بتطبيق المصادقة، أو اضغط «افتح في تطبيق المصادقة» لو التطبيق على نفس التليفون، وبعدين اكتب الكود اللي بيظهر.' : 'Scan the code with your authenticator app, or press “Open in authenticator” if the app is on this phone, then type the code it shows.'),
              const SizedBox(height: 10),
              Center(child: Container(color: Colors.white, padding: const EdgeInsets.all(8), child: QrImageView(data: '${setup['otpauthUri']}', size: 200))),
              const SizedBox(height: 6),
              OutlinedButton(onPressed: () => launch('${setup['otpauthUri']}'), child: Text(ar ? 'افتح في تطبيق المصادقة' : 'Open in authenticator')),
              Row(children: [Expanded(child: SelectableText('${setup['secret']}', textDirection: TextDirection.ltr, style: const TextStyle(fontFamily: 'monospace'))), IconButton(icon: const Icon(Icons.copy_rounded, size: 18), onPressed: () { Clipboard.setData(ClipboardData(text: '${setup['secret']}')); toast(context, ar ? 'اتنسخ' : 'Copied'); })]),
              TextField(controller: _code, keyboardType: TextInputType.number, maxLength: 8, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ar ? 'كود من 6 أرقام' : '6-digit code')),
              Row(children: [
                Expanded(child: FilledButton(onPressed: () async {
                  Map<String, dynamic>? r;
                  if (await run(context, () async { r = await widget.api.post('auth/2fa/enable', {'code': _code.text.trim()}) as Map<String, dynamic>; }) && mounted) {
                    setState(() { _setup = null; _gen++; });
                    await _recoveryDialog((r!['recoveryCodes'] as List));
                  }
                }, child: Text(ar ? 'تأكيد وتشغيل' : 'Confirm and turn on'))),
                const SizedBox(width: 8),
                OutlinedButton(onPressed: () => setState(() => _setup = null), child: Text(ar ? 'إلغاء' : 'Cancel')),
              ]),
            ],
            if (on) OutlinedButton(
              style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFD14343)),
              onPressed: () async {
                final code = await _ask(context, ar ? 'كود من التطبيق أو كود استرداد' : 'A code from the app, or a recovery code', secretLike: true);
                if (code == null || code.isEmpty || !mounted) return;
                if (await run(this.context, () async { await widget.api.post('auth/2fa/disable', {'code': code}); }) && mounted) setState(() => _gen++);
              },
              child: Text(ar ? 'إيقاف الدخول بخطوتين' : 'Turn off two-step sign-in'),
            ),
          ])),
          _card(Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(ar ? 'تسجيل الخروج من كل الأجهزة' : 'Sign out everywhere', style: const TextStyle(fontWeight: FontWeight.w900)),
            const SizedBox(height: 6),
            Text(ar ? 'ضاع تليفون أو دخلت من جهاز مشترك؟ ده بيخرّج حسابك من كل الأجهزة خلال دقايق، وبعدها هتحتاج تدخل تاني هنا.' : 'Lost a phone or used a shared device? This signs your account out on every device within minutes, and you will sign in again here.', style: const TextStyle(color: Color(0xFF6B7C8D))),
            const SizedBox(height: 10),
            OutlinedButton(onPressed: () async {
              if (!await _confirm(context, ar ? 'تخرّج حسابك من كل الأجهزة؟' : 'Sign your account out on every device?') || !mounted) return;
              if (await run(this.context, () async { await widget.api.post('auth/logout-all', {}); }) && mounted) widget.onSignOut();
            }, child: Text(ar ? 'اخرج من كل الأجهزة' : 'Sign out everywhere')),
          ])),
        ]);
      },
    );
  }
}
