import 'dart:async';

import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'billing_ui.dart';
import 'clinic_panel.dart' show NotificationsScreen;
import 'common.dart';
import 'forms.dart';
import 'main.dart' show BusinessHome, BusinessLogo;
import 'push.dart';

/// The platform owner's area: every shop and clinic, their subscriptions and contacts, and the "Proceed to pay" requests to call back.
class AdminHome extends StatefulWidget {
  const AdminHome({super.key, required this.api, required this.perms, required this.onSignOut, this.onBack});
  final ApiClient api;
  final List<String> perms;
  final VoidCallback onSignOut;
  final VoidCallback? onBack;
  @override
  State<AdminHome> createState() => _AdminHomeState();
}

class _AdminHomeState extends State<AdminHome> {
  int _tab = 0;
  final _gen = <int, int>{};
  final ValueNotifier<int> _unread = ValueNotifier(0);
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _pollUnread();
    _poll = Timer.periodic(const Duration(seconds: 30), (_) => _pollUnread());
    // the platform owner's phone gets a push for every new pay request
    PushService.ensure((token) async { await widget.api.post('me/devices', {'token': token, 'platform': defaultTargetPlatform == TargetPlatform.iOS ? 'ios' : 'android'}); }).then((_) => _onPush());   // also handles the push that launched the app
    pushOpened.addListener(_onPush);
  }

  @override
  void dispose() {
    _poll?.cancel();
    pushOpened.removeListener(_onPush);
    super.dispose();
  }

  /// A tapped pay-request push opens the pay requests tab.
  void _onPush() {
    final d = pushOpened.value;
    if (d == null || d['type'] != 'PAY_REQUEST' || !mounted) return;
    pushOpened.value = null;
    setState(() { _tab = 2; _gen[2] = (_gen[2] ?? 0) + 1; });
  }

  Future<void> _pollUnread() async {
    try {
      final r = await widget.api.get('notifications/unread-count') as Map;
      if (mounted) _unread.value = (r['count'] as num?)?.toInt() ?? 0;
    } catch (_) {}
  }

  Ctx get _ctx => Ctx(api: widget.api, currency: 'EGP', perms: widget.perms, host: '', type: 'PLATFORM');

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    final titles = [ar ? 'نظرة عامة' : 'Overview', ar ? 'الأنشطة' : 'Businesses', ar ? 'طلبات الدفع' : 'Pay requests'];
    final pages = <Widget>[
      _Overview(key: ValueKey('o${_gen[0] ?? 0}'), api: widget.api, goTo: (i) => setState(() => _tab = i)),
      _Businesses(key: ValueKey('b${_gen[1] ?? 0}'), api: widget.api, perms: widget.perms),
      _Requests(key: ValueKey('r${_gen[2] ?? 0}'), api: widget.api, perms: widget.perms),
    ];
    return Scaffold(
      appBar: AppBar(
        leading: widget.onBack == null ? null : IconButton(icon: const Icon(Icons.arrow_back_rounded), onPressed: widget.onBack),
        title: Text(ar ? 'لوحة المنصة · ${titles[_tab]}' : 'Platform · ${titles[_tab]}'),
        actions: [
          ValueListenableBuilder<int>(
            valueListenable: _unread,
            builder: (context, n, _) => IconButton(
              tooltip: ar ? 'الإشعارات' : 'Notifications',
              icon: Badge(isLabelVisible: n > 0, label: Text(n > 99 ? '99+' : '$n'), child: const Icon(Icons.notifications_none_rounded)),
              onPressed: () async {
                await Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(ar ? 'الإشعارات' : 'Notifications')), body: NotificationsScreen(ctx: _ctx))));
                unawaited(_pollUnread());
              },
            ),
          ),
          const Padding(padding: EdgeInsets.symmetric(horizontal: 4), child: LanguageChip()),
          if (widget.onBack == null) IconButton(tooltip: ar ? 'تسجيل الخروج' : 'Sign out', icon: const Icon(Icons.logout_rounded), onPressed: widget.onSignOut),
        ],
      ),
      body: IndexedStack(index: _tab, children: pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() { _tab = i; _gen[i] = (_gen[i] ?? 0) + 1; }),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.dashboard_rounded), label: titles[0]),
          NavigationDestination(icon: const Icon(Icons.storefront_rounded), label: titles[1]),
          NavigationDestination(icon: const Icon(Icons.payments_rounded), label: titles[2]),
        ],
      ),
    );
  }
}

String _typeLabel(Object? t) => t == 'STORE' ? (AppLocale.isAr ? 'متجر' : 'Shop') : (AppLocale.isAr ? 'عيادة' : 'Clinic');

// ---------------------------------------------------------------- overview

class _Overview extends StatelessWidget {
  const _Overview({super.key, required this.api, required this.goTo});
  final ApiClient api;
  final void Function(int) goTo;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return Async<Map<String, dynamic>>(
      load: () async => Map<String, dynamic>.from(await api.get('platform/overview') as Map),
      builder: (context, d, reload) {
        final mrr = ((d['monthlyRecurringRevenueMinor'] as num?) ?? 0) / 100;
        final byType = (d['tenantsByTypeAndStatus'] as Map?) ?? const {};
        int sum(Object? m) => ((m as Map?) ?? const {}).values.fold<int>(0, (a, b) => a + ((b as num?)?.toInt() ?? 0));
        Widget hero(String value, String label) => Expanded(child: Column(children: [Text(value, style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.w900)), const SizedBox(height: 2), Text(label, textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withValues(alpha: 0.85), fontSize: 12))]));
        Widget tile(String label, Object? value, IconData icon, Color color, {int? tab}) => Expanded(
              child: Padding(
                padding: const EdgeInsets.all(5),
                child: PressScale(
                  onTap: tab == null ? null : () => goTo(tab),
                  child: Container(
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20), boxShadow: const [BoxShadow(color: Color(0x14102B46), blurRadius: 16, offset: Offset(0, 6))]),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Container(width: 38, height: 38, decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(12)), child: Icon(icon, color: color, size: 21)),
                      const SizedBox(height: 10),
                      Text('$value', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 26, height: 1)),
                      const SizedBox(height: 3),
                      Text(label, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
                    ]),
                  ),
                ),
              ),
            );
        final attention = <Widget>[
          if (((d['newPayRequests'] as num?) ?? 0) > 0) _attn(Icons.payments_rounded, ar ? '${d['newPayRequests']} طلب دفع جديد يحتاج اتصالًا' : '${d['newPayRequests']} new pay requests to call back', const Color(0xFFD98200), () => goTo(2)),
          if (((d['expiringIn7Days'] as num?) ?? 0) > 0) _attn(Icons.hourglass_bottom_rounded, ar ? '${d['expiringIn7Days']} اشتراك ينتهي خلال 7 أيام' : '${d['expiringIn7Days']} subscriptions end within 7 days', const Color(0xFF1F6A99), () => goTo(1)),
          if (((d['waitingForRenew'] as num?) ?? 0) > 0) _attn(Icons.update_rounded, ar ? '${d['waitingForRenew']} نشاط بانتظار التجديد' : '${d['waitingForRenew']} waiting for renew', const Color(0xFFD14343), () => goTo(1)),
          if (((d['disabled'] as num?) ?? 0) > 0) _attn(Icons.block_rounded, ar ? '${d['disabled']} نشاط متوقف' : '${d['disabled']} disabled', const Color(0xFF6B7C8D), () => goTo(1)),
        ];
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(12), children: [
            FadeSlideIn(child: Container(
              padding: const EdgeInsets.fromLTRB(16, 18, 16, 16),
              decoration: BoxDecoration(borderRadius: BorderRadius.circular(26), gradient: LinearGradient(begin: AlignmentDirectional.topStart, end: AlignmentDirectional.bottomEnd, colors: [kBrandBlue, Color.lerp(kBrandBlue, kBrandGreen, 0.65)!, kBrandGreen]), boxShadow: [BoxShadow(color: kBrandBlue.withValues(alpha: 0.28), blurRadius: 22, offset: const Offset(0, 10))]),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(ar ? 'أهلًا بعودتك' : 'Welcome back', style: TextStyle(color: Colors.white.withValues(alpha: 0.88), fontWeight: FontWeight.w600)),
                const SizedBox(height: 2),
                Text(ar ? 'لوحة المنصة' : 'Platform console', style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.w900)),
                const SizedBox(height: 16),
                Row(children: [hero('${d['totalTenants']}', ar ? 'الأنشطة' : 'Businesses'), hero('${sum(byType['STORE'])}', ar ? 'المتاجر' : 'Shops'), hero('${sum(byType['CLINIC'])}', ar ? 'العيادات' : 'Clinics'), hero(mrr.toStringAsFixed(0), ar ? 'جنيه / شهر' : 'EGP / month')]),
              ]),
            )),
            const SizedBox(height: 10),
            Row(children: [tile(ar ? 'طلبات دفع جديدة' : 'New pay requests', d['newPayRequests'], Icons.payments_rounded, const Color(0xFFD98200), tab: 2), tile(ar ? 'ينتهي خلال 7 أيام' : 'Ending in 7 days', d['expiringIn7Days'], Icons.hourglass_bottom_rounded, const Color(0xFF1F6A99), tab: 1)]),
            Row(children: [tile(ar ? 'بانتظار التجديد' : 'Waiting for renew', d['waitingForRenew'], Icons.update_rounded, const Color(0xFFD14343), tab: 1), tile(ar ? 'متوقف' : 'Disabled', d['disabled'], Icons.block_rounded, const Color(0xFF6B7C8D), tab: 1)]),
            Row(children: [tile(ar ? 'جديد هذا الأسبوع' : 'New this week', d['newTenantsLast7Days'], Icons.fiber_new_rounded, const Color(0xFF1E9E5A)), tile(ar ? 'المستخدمون' : 'Users', d['totalUsers'], Icons.people_rounded, const Color(0xFF1F6A99))]),
            sectionTitle(ar ? 'يحتاج انتباهك' : 'Needs your attention'),
            if (attention.isEmpty) Container(padding: const EdgeInsets.all(16), decoration: BoxDecoration(color: const Color(0xFFE6F6EC), borderRadius: BorderRadius.circular(16)), child: Row(children: [const Icon(Icons.check_circle_rounded, color: Color(0xFF1E9E5A)), const SizedBox(width: 10), Expanded(child: Text(ar ? 'لا شيء يحتاج انتباهك الآن.' : 'Nothing needs your attention right now.', style: const TextStyle(fontWeight: FontWeight.w700, color: Color(0xFF14663A))))])) else ...attention,
            sectionTitle(ar ? 'الاشتراكات حسب الباقة' : 'Subscriptions by plan'),
            for (final p in ((d['activeSubscriptionsByPlan'] as List?) ?? const []).cast<Map>())
              Padding(padding: const EdgeInsets.only(bottom: 8), child: Row(children: [
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(planLabel('${p['plan']}'), style: const TextStyle(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 4),
                  ClipRRect(borderRadius: BorderRadius.circular(8), child: LinearProgressIndicator(minHeight: 8, value: ((p['c'] as num?) ?? 0) / (((d['totalTenants'] as num?) ?? 1) == 0 ? 1 : (d['totalTenants'] as num)), backgroundColor: const Color(0xFFE9EEF4), color: '${p['status']}' == 'ACTIVE' ? const Color(0xFF1E9E5A) : const Color(0xFF1F6A99))),
                ])),
                const SizedBox(width: 10),
                StatusPill('${p['status']}'),
                const SizedBox(width: 8),
                Text('${p['c']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
              ])),
          ]),
        );
      },
    );
  }

  Widget _attn(IconData icon, String text, Color color, VoidCallback onTap) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: PressScale(
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(color: color.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(16)),
            child: Row(children: [Icon(icon, color: color), const SizedBox(width: 12), Expanded(child: Text(text, style: TextStyle(fontWeight: FontWeight.w800, color: color))), Icon(Icons.chevron_right_rounded, color: color)]),
          ),
        ),
      );
}

// ---------------------------------------------------------------- businesses

class _Businesses extends StatelessWidget {
  const _Businesses({super.key, required this.api, required this.perms});
  final ApiClient api;
  final List<String> perms;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return PagedList(
      api: api,
      path: 'platform/tenants',
      searchHint: ar ? 'ابحث بالاسم أو الموبايل' : 'Search name or mobile',
      filterParam: 'filter',
      filters: [('', ar ? 'الكل' : 'All'), ('expiring', ar ? 'ينتهي قريبًا' : 'Ending soon'), ('waiting', ar ? 'بانتظار التجديد' : 'Waiting for renew'), ('pay', ar ? 'طلب الدفع' : 'Asked to pay'), ('disabled', ar ? 'متوقف' : 'Disabled')],
      itemBuilder: (context, t, reload) {
        final left = _daysLeft(t['currentPeriodEnd']);
        final start = DateTime.tryParse('${t['currentPeriodStart'] ?? ''}');
        final end = DateTime.tryParse('${t['currentPeriodEnd'] ?? ''}');
        final total = start != null && end != null ? (end.difference(start).inHours / 24).clamp(1, 4000).toDouble() : 30.0;
        final pct = left == null ? 0.0 : (left / total).clamp(0.0, 1.0);
        final tone = left == null ? const Color(0xFF9AA9BA) : left <= 0 ? const Color(0xFFD14343) : left <= 7 ? const Color(0xFFD98200) : const Color(0xFF1E9E5A);
        return Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: PressScale(
            onTap: () async { await Navigator.of(context).push(MaterialPageRoute(builder: (_) => TenantDetailPage(api: api, id: '${t['id']}', perms: perms))); await reload(); },
            child: Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20), boxShadow: const [BoxShadow(color: Color(0x14102B46), blurRadius: 16, offset: Offset(0, 6))]),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  BusinessLogo(url: null, name: '${t['name']}', size: 46),
                  const SizedBox(width: 12),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [Flexible(child: Text('${t['name']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))), if (t['hasPayRequest'] == true) const Padding(padding: EdgeInsetsDirectional.only(start: 6), child: Icon(Icons.payments_rounded, size: 17, color: Color(0xFFD98200)))]),
                    Text('${t['primaryHost'] ?? ''}', maxLines: 1, overflow: TextOverflow.ellipsis, textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)),
                  ])),
                ]),
                const SizedBox(height: 10),
                Wrap(spacing: 6, runSpacing: 4, children: [
                  Container(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4), decoration: BoxDecoration(color: const Color(0xFFEEF2F6), borderRadius: BorderRadius.circular(20)), child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(t['tenantType'] == 'STORE' ? Icons.storefront_rounded : Icons.local_hospital_rounded, size: 13, color: const Color(0xFF4A5B6C)), const SizedBox(width: 4), Text(_typeLabel(t['tenantType']), style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800, color: Color(0xFF4A5B6C)))])),
                  StatusPill('${t['status']}'),
                  if ('${t['subscriptionStatus']}' != '${t['status']}' && t['subscriptionStatus'] != null && !(t['status'] == 'TRIAL' && t['subscriptionStatus'] == 'TRIALING')) StatusPill('${t['subscriptionStatus']}'),
                ]),
                const SizedBox(height: 12),
                Row(children: [Expanded(child: Text(planLabel(t['planCode'] as String?), style: const TextStyle(fontWeight: FontWeight.w800))), Text(left == null ? '' : left <= 0 ? (ar ? 'منتهي' : 'Expired') : (ar ? 'متبقي $left يوم' : '$left days left'), style: TextStyle(fontWeight: FontWeight.w800, color: tone))]),
                const SizedBox(height: 6),
                ClipRRect(borderRadius: BorderRadius.circular(8), child: LinearProgressIndicator(minHeight: 7, value: pct, backgroundColor: const Color(0xFFE9EEF4), color: tone)),
                const SizedBox(height: 4),
                Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(fmtDate('${t['currentPeriodStart'] ?? t['createdAt']}', time: false), style: const TextStyle(color: Color(0xFF8A99AA), fontSize: 11)), Text(fmtDate(t['currentPeriodEnd'] as String?, time: false), style: const TextStyle(color: Color(0xFF8A99AA), fontSize: 11))]),
                if ((t['ownerName'] ?? '').toString().trim().isNotEmpty || t['ownerPhone'] != null) ...[
                  const Divider(height: 22),
                  Row(children: [
                    Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('${t['ownerName'] ?? ''}'.trim().isEmpty ? '-' : '${t['ownerName']}'.trim(), style: const TextStyle(fontWeight: FontWeight.w800)), Text(localPhone(t['ownerPhone']), textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))])),
                    if (t['ownerPhone'] != null) ...[
                      IconButton.filled(visualDensity: VisualDensity.compact, icon: const Icon(Icons.call_rounded, size: 19), onPressed: () => dial('${t['ownerPhone']}')),
                      const SizedBox(width: 6),
                      IconButton.filled(style: IconButton.styleFrom(backgroundColor: const Color(0xFF1E9E5A)), visualDensity: VisualDensity.compact, icon: const Icon(Icons.chat_rounded, size: 19), onPressed: () => whatsapp('${t['ownerPhone']}', '${t['name']}')),
                    ],
                  ]),
                ],
              ]),
            ),
          ),
        );
      },
    );
  }
}

int? _daysLeft(Object? iso) {
  final d = DateTime.tryParse('${iso ?? ''}');
  return d == null ? null : (d.difference(DateTime.now()).inHours / 24).ceil();
}

// ---------------------------------------------------------------- pay requests

class _Requests extends StatelessWidget {
  const _Requests({super.key, required this.api, required this.perms});
  final ApiClient api;
  final List<String> perms;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return PagedList(
      api: api,
      path: 'platform/pay-requests',
      filterParam: 'status',
      filters: [('', ar ? 'الكل' : 'All'), ('NEW', ar ? 'جديد' : 'New'), ('CONTACTED', ar ? 'تم التواصل' : 'Contacted'), ('PAID', ar ? 'مدفوع' : 'Paid'), ('REJECTED', ar ? 'مرفوض' : 'Rejected')],
      emptyText: ar ? 'لا توجد طلبات بعد' : 'No requests yet',
      itemBuilder: (context, r, reload) => _RequestCard(api: api, r: r, perms: perms, reload: reload),
    );
  }
}

class _RequestCard extends StatelessWidget {
  const _RequestCard({required this.api, required this.r, required this.perms, required this.reload});
  final ApiClient api;
  final Map<String, dynamic> r;
  final List<String> perms;
  final Future<void> Function() reload;

  Future<void> _act(BuildContext context, String status) async {
    final ar = AppLocale.isAr;
    final note = TextEditingController(text: '${r['adminNote'] ?? ''}');
    final label = status == 'PAID' ? (ar ? 'تم الدفع: تفعيل الباقة' : 'Paid: activate the plan') : status == 'REJECTED' ? (ar ? 'رفض الطلب' : 'Reject') : (ar ? 'تم التواصل' : 'Mark contacted');
    final ok = await formSheet(context, title: '$label · ${r['tenantName']}', saveLabel: label, body: (c, set) => [tf(note, ar ? 'ملاحظة' : 'Note', lines: 2)], onSave: () async {
      await api.post('platform/pay-requests/${r['id']}', {'status': status, 'adminNote': note.text.trim().isEmpty ? null : note.text.trim(), 'activate': true});
    });
    if (ok) await reload();
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    final open = r['status'] == 'NEW' || r['status'] == 'CONTACTED';
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: InkWell(onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => TenantDetailPage(api: api, id: '${r['tenantId']}', perms: perms))), child: Text('${r['tenantName']}', style: TextStyle(fontWeight: FontWeight.w900, fontSize: 16, color: Theme.of(context).colorScheme.primary)))),
        StatusPill('${r['status']}'),
      ]),
      Text('${_typeLabel(r['tenantType'])} · ${r['primaryHost'] ?? ''}', textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)),
      const SizedBox(height: 6),
      Text('${ar ? 'الباقة المطلوبة' : 'Requested'}: ${planLabel('${r['planCode']}')} · ${fmtMoney(r['amountMinor'] as num?)} ${r['currency']}', style: const TextStyle(fontWeight: FontWeight.w700)),
      Text(fmtDate('${r['createdAt']}'), style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)),
      Row(children: [
        Expanded(child: Text(localPhone(r['phone']), textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
        IconButton.filledTonal(icon: const Icon(Icons.call_rounded), onPressed: () => dial('${r['phone']}')),
        const SizedBox(width: 6),
        IconButton.filledTonal(icon: const Icon(Icons.chat_rounded, color: Color(0xFF1E9E5A)), onPressed: () => whatsapp('${r['phone']}', '${r['tenantName']}')),
      ]),
      if ((r['note'] ?? '').toString().isNotEmpty) Text('“${r['note']}”'),
      if ((r['adminNote'] ?? '').toString().isNotEmpty) Text('${ar ? 'ملاحظة' : 'Note'}: ${r['adminNote']}', style: const TextStyle(color: Color(0xFF6B7C8D))),
      if (open) Padding(padding: const EdgeInsets.only(top: 8), child: Wrap(spacing: 8, runSpacing: 6, children: [
        if (r['status'] == 'NEW') OutlinedButton(onPressed: () => _act(context, 'CONTACTED'), child: Text(ar ? 'تم التواصل' : 'Contacted')),
        FilledButton(onPressed: () => _act(context, 'PAID'), child: Text(ar ? 'تم الدفع' : 'Paid: activate')),
        TextButton(onPressed: () => _act(context, 'REJECTED'), child: Text(ar ? 'رفض' : 'Reject')),
      ])),
    ])));
  }
}

// ---------------------------------------------------------------- one business

class TenantDetailPage extends StatefulWidget {
  const TenantDetailPage({super.key, required this.api, required this.id, required this.perms});
  final ApiClient api;
  final String id;
  final List<String> perms;
  @override
  State<TenantDetailPage> createState() => _TenantDetailPageState();
}

class _TenantDetailPageState extends State<TenantDetailPage> {
  int _n = 0;
  bool get _canPurge => widget.perms.contains('platform.tenant.purge');

  Future<void> _setStatus(String status) async {
    final ar = AppLocale.isAr;
    if (!await confirm(context, status == 'ACTIVE' ? (ar ? 'تفعيل هذا النشاط؟' : 'Enable this business?') : (ar ? 'إيقاف هذا النشاط؟ سيُغلق موقعه أمام الجميع.' : 'Disable this business? Its site closes for everyone.'))) return;
    if (!mounted) return;
    if (await run(context, () => widget.api.post('platform/tenants/${widget.id}/status', {'status': status}))) setState(() => _n++);
  }

  Future<void> _changePlan(Map<String, dynamic> d) async {
    final ar = AppLocale.isAr;
    final store = d['tenantType'] == 'STORE';
    final plans = store ? ['STORE_STANDARD', 'STORE_PRO', 'STORE_STANDARD_YEARLY', 'STORE_PRO_YEARLY'] : ['CLINIC_PRO', 'CLINIC_PRO_YEARLY'];
    var plan = plans.contains((d['subscription'] as Map?)?['planCode']) ? '${(d['subscription'] as Map)['planCode']}' : plans.last;
    final days = TextEditingController(text: plan.endsWith('_YEARLY') ? '365' : '30');
    final ok = await formSheet(context, title: ar ? 'تغيير الباقة / إضافة أيام' : 'Change plan / add days', saveLabel: ar ? 'تطبيق' : 'Apply', body: (c, set) => [
      DropdownButtonFormField<String>(initialValue: plan, items: [for (final p in plans) DropdownMenuItem(value: p, child: Text(planLabel(p)))], onChanged: (v) { set(() => plan = v ?? plan); days.text = plan.endsWith('_YEARLY') ? '365' : '30'; }, decoration: InputDecoration(labelText: ar ? 'الباقة' : 'Plan')),
      const SizedBox(height: 10),
      tf(days, ar ? 'عدد الأيام' : 'Days to add', number: true, ltr: true),
    ], onSave: () async {
      await widget.api.post('platform/tenants/${widget.id}/plan', {'planCode': plan, 'days': int.tryParse(days.text.trim()) ?? 30});
    });
    if (ok) setState(() => _n++);
  }

  Future<void> _resetPassword() async {
    final ar = AppLocale.isAr;
    if (!await confirm(context, ar ? 'إصدار كود مؤقت لإعادة تعيين كلمة مرور المالك؟' : 'Issue a one-time code to reset the owner\'s password?')) return;
    if (!mounted) return;
    try {
      final r = await widget.api.post('platform/tenants/${widget.id}/owner/reset-password', {}) as Map;
      if (!mounted) return;
      await showDialog<void>(context: context, builder: (c) => AlertDialog(
        title: Text(ar ? 'الكود المؤقت' : 'One-time code'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          SelectableText('${r['pin']}', textDirection: TextDirection.ltr, style: const TextStyle(fontSize: 34, fontWeight: FontWeight.w900, letterSpacing: 4)),
          const SizedBox(height: 8),
          Text(ar ? 'أعطه للمالك: في شاشة الدخول يختار «نسيت كلمة المرور» ويكتب هذا الكود. يعمل مرة واحدة.' : 'Give it to the owner: on the sign-in screen they choose forgot password and enter this code. It works once.'),
        ]),
        actions: [TextButton(onPressed: () => Navigator.pop(c), child: Text(ar ? 'تم' : 'Done'))],
      ));
    } catch (e) {
      if (mounted) toast(context, errorText(e), isError: true);
    }
  }

  Future<void> _editOwner(Map<String, dynamic> owner) async {
    final ar = AppLocale.isAr;
    final phone = TextEditingController(text: localPhone(owner['phone']));
    final email = TextEditingController(text: '${owner['email'] ?? ''}');
    final ok = await formSheet(context, title: ar ? 'تعديل بيانات تواصل المالك' : 'Edit owner contact', body: (c, set) => [tf(phone, ar ? 'موبايل المالك' : 'Owner phone', ltr: true), tf(email, ar ? 'بريد المالك' : 'Owner email', ltr: true)],
        onSave: () async { await widget.api.patch('platform/tenants/${widget.id}/owner', {'phone': phone.text.trim().isEmpty ? null : phone.text.trim(), 'email': email.text.trim().isEmpty ? null : email.text.trim()}); });
    if (ok) setState(() => _n++);
  }

  Future<void> _rename(Map<String, dynamic> d) async {
    final ar = AppLocale.isAr;
    final name = TextEditingController(text: '${d['name']}');
    final slug = TextEditingController(text: '${d['slug']}');
    final ok = await formSheet(context, title: ar ? 'تعديل اسم النشاط / العنوان' : 'Edit business name / address', body: (c, set) => [
      tf(name, ar ? 'اسم النشاط' : 'Business name'),
      tf(slug, ar ? 'اسم العنوان (قبل النقطة)' : 'Address name (before the dot)', ltr: true),
      Text(ar ? 'العنوان القديم يتوقف فورًا.' : 'The old address stops working immediately.', style: const TextStyle(color: Color(0xFFD98200), fontWeight: FontWeight.w700)),
    ], onSave: () async { await widget.api.patch('platform/tenants/${widget.id}', {'name': name.text.trim(), 'slug': slug.text.trim().toLowerCase()}); });
    if (ok) setState(() => _n++);
  }

  /// Support access: audited on the server, then the business opens with the admin's own session (acting as its owner).
  Future<void> _openAsSupport() async {
    try {
      final r = Map<String, dynamic>.from(await widget.api.post('platform/tenants/${widget.id}/support-access', {}) as Map);
      final host = r['host'] as String?;
      if (host == null || !mounted) return;
      final business = {'id': widget.id, 'name': r['name'], 'type': r['tenantType'], 'status': r['status'], 'currency': r['defaultCurrency'] ?? 'EGP', 'host': host, 'baseUrl': baseForHost(host), 'roles': <String>[]};
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => SupportBusiness(business: business, api: widget.api)));
    } catch (e) {
      if (mounted) toast(context, errorText(e), isError: true);
    }
  }

  Future<void> _delete(Map<String, dynamic> d) async {
    final ar = AppLocale.isAr;
    final slug = '${d['slug']}';
    final typed = TextEditingController();
    final ok = await formSheet(
      context,
      title: ar ? 'حذف كل شيء' : 'Delete everything',
      saveLabel: ar ? 'حذف نهائي' : 'Delete permanently',
      canSave: () => typed.text.trim().toLowerCase() == slug,
      body: (c, set) => [
        Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFDECEC), borderRadius: BorderRadius.circular(12)), child: Text(ar ? 'يحذف هذا النشاط نهائيًا مع بياناته وصوره والمرضى والعملاء الذين يتبعونه وحده. لا يمكن التراجع.' : 'Permanently removes this business, its data, its images and the patients and customers who belong only to it. This cannot be undone.', style: const TextStyle(color: Color(0xFFB71C1C), fontWeight: FontWeight.w700))),
        const SizedBox(height: 10),
        tf(typed, ar ? 'اكتب اسم العنوان ($slug) للتأكيد' : 'Type the address name ($slug) to confirm', ltr: true, onChanged: (_) => set(() {})),
      ],
      onSave: () async { await widget.api.delete('platform/tenants/${widget.id}', {'confirmSlug': typed.text.trim()}); },
    );
    if (ok && mounted) { toast(context, ar ? 'تم حذف النشاط' : 'The business was deleted'); Navigator.of(context).pop(); }
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'تفاصيل النشاط' : 'Business')),
      body: Async<Map<String, dynamic>>(
        key: ValueKey(_n),
        load: () async => Map<String, dynamic>.from(await widget.api.get('platform/tenants/${widget.id}') as Map),
        builder: (context, d, reload) {
          final sub = Map<String, dynamic>.from((d['subscription'] as Map?) ?? const {});
          final owner = Map<String, dynamic>.from((d['owner'] as Map?) ?? const {});
          final state = Map<String, dynamic>.from((d['billingState'] as Map?) ?? const {});
          final disabled = d['status'] == 'SUSPENDED';
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(padding: const EdgeInsets.all(16), children: [
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(borderRadius: BorderRadius.circular(24), gradient: LinearGradient(begin: AlignmentDirectional.topStart, end: AlignmentDirectional.bottomEnd, colors: [kBrandBlue, Color.lerp(kBrandBlue, kBrandGreen, 0.65)!, kBrandGreen]), boxShadow: [BoxShadow(color: kBrandBlue.withValues(alpha: 0.25), blurRadius: 18, offset: const Offset(0, 8))]),
                child: Row(children: [
                  BusinessLogo(url: null, name: '${d['name']}', size: 60),
                  const SizedBox(width: 14),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('${d['name']}', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 20)),
                    Text('${(((d['domains'] as List?) ?? const []).cast<Map>().where((x) => x['primary'] == true).map((x) => x['host']).firstOrNull) ?? d['slug']}', textDirection: TextDirection.ltr, style: TextStyle(color: Colors.white.withValues(alpha: 0.85), fontSize: 12.5)),
                    const SizedBox(height: 6),
                    Wrap(spacing: 6, children: [Container(decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20)), child: StatusPill('${d['status']}')), Container(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4), decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.22), borderRadius: BorderRadius.circular(20)), child: Text(_typeLabel(d['tenantType']), style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 12)))]),
                  ])),
                ]),
              ),
              sectionTitle(ar ? 'صاحب النشاط' : 'Owner'),
              Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${owner['firstName'] ?? ''} ${owner['lastName'] ?? ''}'.trim().isEmpty ? '-' : '${owner['firstName'] ?? ''} ${owner['lastName'] ?? ''}'.trim(), style: const TextStyle(fontWeight: FontWeight.w800)),
                if (owner['phone'] != null) Row(children: [
                  Expanded(child: Text(localPhone(owner['phone']), textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
                  IconButton.filledTonal(icon: const Icon(Icons.call_rounded), onPressed: () => dial('${owner['phone']}')),
                  const SizedBox(width: 6),
                  IconButton.filledTonal(icon: const Icon(Icons.chat_rounded, color: Color(0xFF1E9E5A)), onPressed: () => whatsapp('${owner['phone']}', '${d['name']}')),
                ]),
                if (owner['email'] != null) Text('${owner['email']}', textDirection: TextDirection.ltr),
                Wrap(spacing: 8, children: [
                  OutlinedButton.icon(onPressed: _resetPassword, icon: const Icon(Icons.lock_reset_rounded), label: Text(ar ? 'إعادة تعيين كلمة المرور' : 'Reset password')),
                  OutlinedButton.icon(onPressed: () => _editOwner(owner), icon: const Icon(Icons.edit_rounded), label: Text(ar ? 'تعديل التواصل' : 'Edit contact')),
                ]),
                const SizedBox(height: 4),
                Text('${ar ? 'تاريخ التسجيل' : 'Signed up'}: ${fmtDate('${d['createdAt']}', time: false)}  ·  ${ar ? 'الموظفون' : 'Staff'}: ${d['staffCount']}', style: const TextStyle(color: Color(0xFF6B7C8D))),
              ]))),
              sectionTitle(ar ? 'الاشتراك' : 'Subscription'),
              Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [Expanded(child: Text(planLabel(sub['planCode'] as String?), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))), if (sub['status'] != null) StatusPill('${sub['status']}')]),
                kv(ar ? 'بداية الاشتراك' : 'Started', fmtDate('${sub['currentPeriodStart'] ?? sub['createdAt'] ?? ''}', time: false)),
                kv(ar ? 'ينتهي في' : 'Ends on', fmtDate(sub['currentPeriodEnd'] as String?, time: false)),
                if (state['daysLeft'] != null && state['state'] != 'DISABLED') kv(ar ? 'المتبقي' : 'Days left', '${state['daysLeft']}'),
                const SizedBox(height: 6),
                Wrap(spacing: 8, runSpacing: 4, children: [
                  FilledButton.icon(onPressed: () => _changePlan(d), icon: const Icon(Icons.swap_vert_rounded), label: Text(ar ? 'تغيير الباقة / أيام' : 'Change plan / days')),
                  if (!disabled) OutlinedButton.icon(onPressed: () => _setStatus('SUSPENDED'), icon: const Icon(Icons.block_rounded, color: Color(0xFFD14343)), label: Text(ar ? 'إيقاف' : 'Disable', style: const TextStyle(color: Color(0xFFD14343)))),
                  if (disabled) OutlinedButton.icon(onPressed: () => _setStatus('ACTIVE'), icon: const Icon(Icons.check_circle_outline_rounded), label: Text(ar ? 'تفعيل' : 'Enable')),
                ]),
              ]))),
              if (((d['payRequests'] as List?) ?? const []).isNotEmpty) ...[
                sectionTitle(ar ? 'طلبات الدفع' : 'Pay requests'),
                for (final r in (d['payRequests'] as List).cast<Map>())
                  Card(child: ListTile(title: Text('${planLabel('${r['planCode']}')} · ${fmtMoney(r['amountMinor'] as num?)} ${r['currency']}'), subtitle: Text(fmtDate('${r['createdAt']}')), trailing: StatusPill('${r['status']}'))),
              ],
              sectionTitle(ar ? 'تحكم المنصة' : 'Platform controls'),
              Wrap(spacing: 8, runSpacing: 4, children: [
                OutlinedButton.icon(onPressed: () => _rename(d), icon: const Icon(Icons.drive_file_rename_outline_rounded), label: Text(ar ? 'تعديل الاسم / العنوان' : 'Edit name / address')),
                if (widget.perms.contains('platform.support.access')) FilledButton.tonalIcon(onPressed: _openAsSupport, icon: const Icon(Icons.support_agent_rounded), label: Text(ar ? 'فتح كدعم فني' : 'Open as support')),
              ]),
              sectionTitle(ar ? 'النطاقات' : 'Domains'),
              for (final x in ((d['domains'] as List?) ?? const []).cast<Map>()) Text('${x['host']} ${x['primary'] == true ? '★' : ''} ${x['verified'] == true ? '✓' : '…'}', textDirection: TextDirection.ltr),
              sectionTitle(ar ? 'آخر الأحداث' : 'Recent events'),
              for (final e in ((d['events'] as List?) ?? const []).cast<Map>()) Text('${e['eventType']} · ${fmtDate('${e['createdAt']}')}', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
              if (_canPurge) ...[
                const SizedBox(height: 22),
                OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFD14343), side: const BorderSide(color: Color(0xFFD14343))),
                  onPressed: () => _delete(d),
                  icon: const Icon(Icons.delete_forever_rounded),
                  label: Text(ar ? 'حذف كل بيانات هذا النشاط' : 'Delete everything about this business'),
                ),
              ],
            ]),
          );
        },
      ),
    );
  }
}

/// The business's own panel, opened by the platform owner for support (the platform session acts as the business owner on that address).
class SupportBusiness extends StatelessWidget {
  const SupportBusiness({super.key, required this.business, required this.api});
  final Map<String, dynamic> business;
  final ApiClient api;
  @override
  Widget build(BuildContext context) => BusinessHome(business: business, session: api.session, canSwitch: false, onSwitch: () => Navigator.of(context).pop(), onSignOut: () => Navigator.of(context).pop());
}
