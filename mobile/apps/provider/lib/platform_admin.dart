import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'billing_ui.dart';
import 'common.dart';
import 'forms.dart';

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
        Widget stat(String label, Object? value, IconData icon, Color color, {int? tab}) => Expanded(
              child: Card(child: InkWell(
                borderRadius: BorderRadius.circular(16),
                onTap: tab == null ? null : () => goTo(tab),
                child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Icon(icon, color: color),
                  const SizedBox(height: 8),
                  Text('$value', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 24)),
                  Text(label, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
                ])),
              )),
            );
        final mrr = ((d['monthlyRecurringRevenueMinor'] as num?) ?? 0) / 100;
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(12), children: [
            Row(children: [stat(ar ? 'طلبات دفع جديدة' : 'New pay requests', d['newPayRequests'], Icons.payments_rounded, const Color(0xFFD98200), tab: 2), stat(ar ? 'ينتهي خلال 7 أيام' : 'Ending in 7 days', d['expiringIn7Days'], Icons.hourglass_bottom_rounded, const Color(0xFFD98200), tab: 1)]),
            Row(children: [stat(ar ? 'بانتظار التجديد' : 'Waiting for renew', d['waitingForRenew'], Icons.update_rounded, const Color(0xFFD14343), tab: 1), stat(ar ? 'متوقف' : 'Disabled', d['disabled'], Icons.block_rounded, const Color(0xFFD14343), tab: 1)]),
            Row(children: [stat(ar ? 'إجمالي الأنشطة' : 'Businesses', d['totalTenants'], Icons.storefront_rounded, const Color(0xFF1F6A99), tab: 1), stat(ar ? 'جديد هذا الأسبوع' : 'New this week', d['newTenantsLast7Days'], Icons.fiber_new_rounded, const Color(0xFF1E9E5A), tab: 1)]),
            Row(children: [stat(ar ? 'الإيراد الشهري' : 'Monthly revenue', mrr.toStringAsFixed(0), Icons.account_balance_wallet_rounded, const Color(0xFF1E9E5A)), stat(ar ? 'المستخدمون' : 'Users', d['totalUsers'], Icons.people_rounded, const Color(0xFF6B7C8D))]),
            sectionTitle(ar ? 'الاشتراكات' : 'Subscriptions'),
            for (final p in ((d['activeSubscriptionsByPlan'] as List?) ?? const []).cast<Map>())
              Card(child: ListTile(title: Text(planLabel('${p['plan']}')), subtitle: StatusPill('${p['status']}'.replaceAll('TRIALING', 'TRIALING')), trailing: Text('${p['c']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)))),
          ]),
        );
      },
    );
  }
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
        return Card(child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: () async { await Navigator.of(context).push(MaterialPageRoute(builder: (_) => TenantDetailPage(api: api, id: '${t['id']}', perms: perms))); await reload(); },
          child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('${t['name']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              if (t['hasPayRequest'] == true) const Padding(padding: EdgeInsets.only(right: 6, left: 6), child: Icon(Icons.payments_rounded, size: 18, color: Color(0xFFD98200))),
              StatusPill('${t['status']}'),
            ]),
            Text('${_typeLabel(t['tenantType'])} · ${t['primaryHost'] ?? ''}', textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)),
            const SizedBox(height: 6),
            Text('${planLabel(t['planCode'] as String?)}  ·  ${ar ? 'بداية' : 'from'} ${fmtDate('${t['currentPeriodStart'] ?? t['createdAt']}', time: false)}  ·  ${ar ? 'حتى' : 'to'} ${fmtDate(t['currentPeriodEnd'] as String?, time: false)}'),
            if (left != null) Text(left <= 0 ? (ar ? 'منتهي' : 'Expired') : (ar ? 'متبقي $left يوم' : '$left days left'), style: TextStyle(fontWeight: FontWeight.w800, color: left <= 0 ? const Color(0xFFD14343) : left <= 7 ? const Color(0xFFD98200) : const Color(0xFF6B7C8D))),
            if ((t['ownerName'] ?? '').toString().trim().isNotEmpty || t['ownerPhone'] != null) Padding(padding: const EdgeInsets.only(top: 4), child: Row(children: [
              Expanded(child: Text('${t['ownerName'] ?? ''}  ${t['ownerPhone'] ?? ''}', textDirection: TextDirection.ltr)),
              if (t['ownerPhone'] != null) ...[
                IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.call_rounded, size: 20), onPressed: () => dial('${t['ownerPhone']}')),
                IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.chat_rounded, size: 20, color: Color(0xFF1E9E5A)), onPressed: () => whatsapp('${t['ownerPhone']}', '${t['name']}')),
              ],
            ])),
          ])),
        ));
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
        Expanded(child: Text('${r['phone']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
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
    final plans = store ? ['STORE_STANDARD', 'STORE_PRO'] : ['CLINIC_PRO'];
    var plan = plans.contains((d['subscription'] as Map?)?['planCode']) ? '${(d['subscription'] as Map)['planCode']}' : plans.last;
    final days = TextEditingController(text: '30');
    final ok = await formSheet(context, title: ar ? 'تغيير الباقة / إضافة أيام' : 'Change plan / add days', saveLabel: ar ? 'تطبيق' : 'Apply', body: (c, set) => [
      DropdownButtonFormField<String>(initialValue: plan, items: [for (final p in plans) DropdownMenuItem(value: p, child: Text(planLabel(p)))], onChanged: (v) => set(() => plan = v ?? plan), decoration: InputDecoration(labelText: ar ? 'الباقة' : 'Plan')),
      const SizedBox(height: 10),
      tf(days, ar ? 'عدد الأيام' : 'Days to add', number: true, ltr: true),
    ], onSave: () async {
      await widget.api.post('platform/tenants/${widget.id}/plan', {'planCode': plan, 'days': int.tryParse(days.text.trim()) ?? 30});
    });
    if (ok) setState(() => _n++);
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
              Row(children: [Expanded(child: Text('${d['name']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 22))), StatusPill('${d['status']}')]),
              Text('${_typeLabel(d['tenantType'])} · ${d['slug']}', textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF6B7C8D))),
              sectionTitle(ar ? 'صاحب النشاط' : 'Owner'),
              Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${owner['firstName'] ?? ''} ${owner['lastName'] ?? ''}'.trim().isEmpty ? '-' : '${owner['firstName'] ?? ''} ${owner['lastName'] ?? ''}'.trim(), style: const TextStyle(fontWeight: FontWeight.w800)),
                if (owner['phone'] != null) Row(children: [
                  Expanded(child: Text('${owner['phone']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
                  IconButton.filledTonal(icon: const Icon(Icons.call_rounded), onPressed: () => dial('${owner['phone']}')),
                  const SizedBox(width: 6),
                  IconButton.filledTonal(icon: const Icon(Icons.chat_rounded, color: Color(0xFF1E9E5A)), onPressed: () => whatsapp('${owner['phone']}', '${d['name']}')),
                ]),
                if (owner['email'] != null) Text('${owner['email']}', textDirection: TextDirection.ltr),
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
                Wrap(spacing: 8, children: [
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
