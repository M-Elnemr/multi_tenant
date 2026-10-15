import 'package:flutter/material.dart';
import 'package:intl/intl.dart' as intl;
import 'package:platform_core/platform_core.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'billing_ui.dart';
import 'common.dart';
import 'forms.dart';

List<Map<String, dynamic>> _maps(dynamic v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
String _date(dynamic iso) { final d = DateTime.tryParse('$iso'); return d == null ? '' : intl.DateFormat.yMMMd(AppLocale.isAr ? 'ar' : 'en').format(d.toLocal()); }

// ---------------------------------------------------------------- Domains

class DomainsScreen extends StatelessWidget {
  const DomainsScreen({super.key, required this.ctx});
  final Ctx ctx;

  Future<void> _add(BuildContext c, Future<void> Function() reload) async {
    final isAr = S.of(c).ar;
    final host = TextEditingController();
    String? upgrade;
    final ok = await formSheet(c,
        title: isAr ? 'إضافة نطاق' : 'Add a domain',
        canSave: () => host.text.trim().contains('.'),
        body: (x, set) => [
              Text(isAr ? 'اكتب نطاقك مثل www.my-brand.com ثم أضف سجلات DNS التي ستظهر لك.' : 'Enter your domain such as www.my-brand.com, then add the DNS records shown afterwards.', style: const TextStyle(color: Color(0xFF6B7C8D))),
              const SizedBox(height: 10),
              tf(host, isAr ? 'النطاق' : 'Domain', ltr: true, hint: 'www.my-brand.com', onChanged: (_) => set(() {})),
            ],
        onSave: () async {
          try {
            await ctx.api.post('tenant/domains', {'host': host.text.trim().toLowerCase()});
          } on ApiException catch (e) {
            if (e.code == 'FEATURE_NOT_AVAILABLE') { upgrade = isAr ? 'النطاق الخاص غير متاح في باقتك الحالية. رقّ الباقة من "الفواتير والاشتراك".' : 'Custom domains are not in your current plan. Upgrade from Billing.'; }
            rethrow;
          }
        });
    if (ok) await reload();
    if (!ok && upgrade != null && c.mounted) toast(c, upgrade!);
  }

  @override
  Widget build(BuildContext context) => Async<List<dynamic>>(
        load: () async => await ctx.api.get('tenant/domains') as List<dynamic>,
        builder: (context, data, reload) {
          final isAr = S.of(context).ar;
          final list = _maps(data);
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 90), children: [
              Text(isAr ? 'اربط نطاقك الخاص بمتجرك أو عيادتك في ثلاث خطوات: أضفه، أنشئ سجلي DNS، ثم اضغط تحقق. الشهادات تُصدر تلقائيًا.' : 'Connect your own domain in three steps: add it, create the DNS records, tap verify. Certificates are issued automatically.', style: const TextStyle(color: Color(0xFF6B7C8D))),
              const SizedBox(height: 10),
              for (final d in list) _domainCard(context, d, reload, isAr),
              const SizedBox(height: 10),
              FilledButton.icon(onPressed: () => _add(context, reload), icon: const Icon(Icons.add_rounded), label: Text(isAr ? 'إضافة نطاق' : 'Add a domain')),
            ]),
          );
        },
      );

  Widget _domainCard(BuildContext context, Map<String, dynamic> d, Future<void> Function() reload, bool isAr) {
    final verified = d['verified'] == true, primary = d['primary'] == true, custom = d['kind'] == 'CUSTOM';
    final dns = _maps(d['dnsInstructions']);
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      SelectableText('${d['host']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w800, fontFamily: 'monospace')),
      const SizedBox(height: 6),
      Wrap(spacing: 6, children: [
        if (primary) _tag(isAr ? 'الرئيسي' : 'Primary', const Color(0xFF1F6A99)),
        _tag(verified ? (isAr ? 'تم التحقق' : 'Verified') : (isAr ? 'بانتظار التحقق' : 'Pending'), verified ? const Color(0xFF1E9E5A) : const Color(0xFFD98200)),
        if (!custom) _tag(isAr ? 'مجاني' : 'Free', const Color(0xFF6B7C8D)),
      ]),
      if (!verified && dns.isNotEmpty) ...[
        const SizedBox(height: 10),
        Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(color: const Color(0xFFF4F7FA), borderRadius: BorderRadius.circular(12)),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(isAr ? 'أضف هذه السجلات عند مزوّد النطاق:' : 'Add these records at your domain provider:', style: const TextStyle(fontWeight: FontWeight.w800)),
            const SizedBox(height: 6),
            for (final r in dns) ...[
              Text('${r['type']}', style: const TextStyle(fontWeight: FontWeight.w900)),
              CopyRow(isAr ? 'الاسم' : 'Name', '${r['name']}'),
              CopyRow(isAr ? 'القيمة' : 'Value', '${r['value']}'),
              const Divider(height: 12),
            ],
          ]),
        ),
      ],
      const SizedBox(height: 8),
      Wrap(spacing: 8, children: [
        if (!verified) FilledButton(onPressed: () async { if (await run(context, () => ctx.api.post('tenant/domains/${d['id']}/verify', {}))) await reload(); }, child: Text(isAr ? 'تحقق' : 'Verify')),
        if (verified && !primary) OutlinedButton(onPressed: () async { if (await run(context, () => ctx.api.post('tenant/domains/${d['id']}/primary', {}))) await reload(); }, child: Text(isAr ? 'اجعله الرئيسي' : 'Make primary')),
        if (custom && !primary) TextButton(onPressed: () async { if (await confirm(context, isAr ? 'حذف النطاق؟' : 'Remove this domain?') && context.mounted && await run(context, () => ctx.api.delete('tenant/domains/${d['id']}'))) await reload(); }, child: Text(isAr ? 'حذف' : 'Remove')),
      ]),
    ])));
  }

  Widget _tag(String t, Color c) => Container(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3), decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(20)), child: Text(t, style: TextStyle(color: c, fontWeight: FontWeight.w800, fontSize: 12)));
}

// ---------------------------------------------------------------- Billing

class BillingScreen extends StatefulWidget {
  const BillingScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<BillingScreen> createState() => _BillingScreenState();
}

class _BillingScreenState extends State<BillingScreen> {
  int _n = 0;
  Ctx get ctx => widget.ctx;

  Future<dynamic> _load() async {
    final r = await Future.wait([
      ctx.api.get('billing/subscription').catchError((_) => null),
      ctx.api.get('billing/plans'),
      ctx.api.get('billing/invoices').catchError((_) => <dynamic>[]),
    ]);
    return r;
  }

  /// Online payment is not live yet: choosing a plan opens the manual pay screen (customer service calls the owner).
  Future<void> _change(Map<String, dynamic> plan) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => PayPage(api: ctx.api, plan: '${plan['code']}')));
    if (mounted) setState(() => _n++);
  }

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    final canManage = ctx.can('billing.manage');
    return Async<dynamic>(
      key: ValueKey(_n),
      load: _load,
      builder: (context, data, reload) {
        final sub = data[0] == null ? null : Map<String, dynamic>.from(data[0] as Map);
        final plans = _maps(data[1]).where((p) => ((p['priceMinor'] as num?) ?? 0) > 0).toList();
        final invoices = _maps(data[2]);
        final current = sub?['planCode'];
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
            if (sub != null) Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(isAr ? 'باقتك الحالية' : 'Current plan', style: const TextStyle(color: Color(0xFF6B7C8D))),
              Row(children: [Expanded(child: Text(planLabel('${sub['planCode']}'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 20))), StatusPill('${sub['status']}')]),
              const SizedBox(height: 4),
              Text(sub['status'] == 'TRIALING' ? (isAr ? 'تنتهي التجربة في ${_date(sub['currentPeriodEnd'])}' : 'Trial ends ${_date(sub['currentPeriodEnd'])}') : (isAr ? 'يتجدد في ${_date(sub['currentPeriodEnd'])}' : 'Renews ${_date(sub['currentPeriodEnd'])}')),
              if (sub['status'] == 'PAST_DUE') Padding(padding: const EdgeInsets.only(top: 8), child: Text(isAr ? 'هناك دفعة متأخرة. اختر باقة للسداد.' : 'A payment is overdue. Choose a plan to pay.', style: const TextStyle(color: Color(0xFFD98200), fontWeight: FontWeight.w700))),
              if (canManage) Align(
                alignment: AlignmentDirectional.centerEnd,
                child: sub['cancelAtPeriodEnd'] == true
                    ? Text(isAr ? 'سيُلغى عند نهاية الفترة' : 'Cancels at period end', style: const TextStyle(color: Color(0xFFD98200), fontWeight: FontWeight.w700))
                    : TextButton(onPressed: () async { if (await confirm(context, isAr ? 'إلغاء الاشتراك عند نهاية الفترة؟' : 'Cancel at the end of the period?') && context.mounted && await run(context, () => ctx.api.post('billing/subscription/cancel', {}))) await reload(); }, child: Text(isAr ? 'إلغاء الاشتراك' : 'Cancel subscription')),
              ),
            ]))),
            sectionTitle(isAr ? 'الباقات' : 'Plans'),
            for (final p in plans) Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text(planLabel('${p['code']}'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
              Text('${fmtMoney(p['priceMinor'] as num?)} ${p['currency']} / ${isAr ? 'شهر' : 'month'}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 22)),
              const SizedBox(height: 6),
              for (final e in ((p['features'] as Map?) ?? const {}).entries) Text('${e.value == false ? '✕' : '✓'} ${_feature(e.key, isAr)}${e.value is num ? ': ${e.value}' : ''}', style: TextStyle(color: e.value == false ? const Color(0xFFA0AAB6) : null, decoration: e.value == false ? TextDecoration.lineThrough : null)),
              const SizedBox(height: 10),
              FilledButton(
                onPressed: !canManage || (current == p['code'] && sub?['status'] == 'ACTIVE') ? null : () => _change(p),
                child: Text(current == p['code'] && sub?['status'] == 'ACTIVE' ? (isAr ? 'باقتك الحالية' : 'Current plan') : (isAr ? 'اختيار' : 'Choose')),
              ),
            ]))),
            sectionTitle(isAr ? 'الفواتير' : 'Invoices'),
            if (invoices.isEmpty) emptyState(isAr ? 'لا توجد فواتير' : 'No invoices'),
            for (final i in invoices) Card(child: ListTile(
              title: Text('${i['invoiceNumber']}', style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w700)),
              subtitle: Text(_date(i['issuedAt'])),
              trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [Text('${fmtMoney(i['totalMinor'] as num?)} ${i['currency']}', style: const TextStyle(fontWeight: FontWeight.w800)), StatusPill('${i['status']}')]),
            )),
          ]),
        );
      },
    );
  }

  String _feature(String k, bool isAr) {
    const ar = {'custom_domain': 'نطاق خاص', 'staff_limit': 'عدد الموظفين', 'product_limit': 'عدد المنتجات', 'branch_limit': 'عدد الفروع', 'coupons': 'الكوبونات', 'reports': 'التقارير', 'online_payments': 'الدفع الإلكتروني'};
    return isAr ? (ar[k] ?? k.replaceAll('_', ' ')) : k.replaceAll('_', ' ');
  }
}

/// A hosted payment page shown inside the app; closing it returns to the billing screen, which reloads.
class PaymentPage extends StatefulWidget {
  const PaymentPage({super.key, required this.url, required this.title});
  final String url, title;
  @override
  State<PaymentPage> createState() => _PaymentPageState();
}

class _PaymentPageState extends State<PaymentPage> {
  late final WebViewController _c = WebViewController()
    ..setJavaScriptMode(JavaScriptMode.unrestricted)
    ..setNavigationDelegate(NavigationDelegate(onPageFinished: (_) { if (mounted) setState(() => _loading = false); }))
    ..loadRequest(Uri.parse(widget.url));
  bool _loading = true;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(widget.title), actions: [IconButton(icon: const Icon(Icons.check_rounded), tooltip: S.of(context).ar ? 'تم' : 'Done', onPressed: () => Navigator.of(context).pop())]),
        body: Stack(children: [WebViewWidget(controller: _c), if (_loading) const Center(child: CircularProgressIndicator())]),
      );
}
