import 'dart:async';

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
    final verified = d['verified'] == true, primary = d['primary'] == true, stopped = d['stopped'] == true, custom = d['kind'] == 'CUSTOM';
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      SelectableText('${d['host']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w800, fontFamily: 'monospace')),
      if (d['alias'] != null) Padding(padding: const EdgeInsets.only(top: 2), child: Text(isAr ? 'يعمل أيضًا على ${d['alias']} تلقائيًا (أضف سجل DNS له)' : 'Also works on ${d['alias']} automatically (add its DNS record)', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))),
      const SizedBox(height: 6),
      Wrap(spacing: 6, children: [
        if (stopped) _tag(isAr ? 'متوقف - باقتك لا تشمل نطاقًا خاصًا' : 'Stopped - your plan has no custom domain', const Color(0xFFC62828)),
        if (primary) _tag(isAr ? 'الرئيسي' : 'Primary', const Color(0xFF1F6A99)),
        _tag(verified ? (isAr ? 'تم التحقق' : 'Verified') : (isAr ? 'بانتظار التحقق' : 'Pending'), verified ? const Color(0xFF1E9E5A) : const Color(0xFFD98200)),
        if (!custom) _tag(isAr ? 'مجاني' : 'Free', const Color(0xFF6B7C8D)),
      ]),
      if (!verified && custom) DnsSetupPanel(api: ctx.api, id: '${d['id']}', host: '${d['host']}', onVerified: reload),
      if (verified && custom && !stopped) Container(margin: const EdgeInsets.only(top: 10), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFE6F6EC), borderRadius: BorderRadius.circular(14)), child: Row(children: [const Icon(Icons.check_circle_rounded, color: Color(0xFF1E9E5A)), const SizedBox(width: 10), Expanded(child: Text(isAr ? 'تم ربط نطاقك. شهادة الأمان (HTTPS) تُصدر تلقائيًا خلال دقائق.' : 'Your domain is connected. The HTTPS certificate is issued automatically within minutes.', style: const TextStyle(fontWeight: FontWeight.w700, color: Color(0xFF14663A))))])),
      const SizedBox(height: 8),
      Wrap(spacing: 8, children: [
        if (!verified) FilledButton(onPressed: () async { if (await run(context, () => ctx.api.post('tenant/domains/${d['id']}/verify', {}))) await reload(); }, child: Text(isAr ? 'تحقق' : 'Verify')),
        if (verified && !primary && !stopped) OutlinedButton(onPressed: () async { if (await run(context, () => ctx.api.post('tenant/domains/${d['id']}/primary', {}))) await reload(); }, child: Text(isAr ? 'اجعله الرئيسي' : 'Make primary')),
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
        final plans = _maps(data[1]).where((p) => ((p['priceMinor'] as num?) ?? 0) > 0 && p['interval'] != 'YEAR').toList();
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
              if (sub['status'] == 'PAST_DUE') Padding(padding: const EdgeInsets.only(top: 8), child: Text(isAr ? 'انتهى اشتراكك. اختر باقة وتواصل معنا.' : 'Your subscription ended. Choose a plan and contact us.', style: const TextStyle(color: Color(0xFFD98200), fontWeight: FontWeight.w700))),
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
              for (final b in planBenefits(store: '${p['code']}'.startsWith('STORE'), domain: ((p['features'] as Map?) ?? const {})['custom_domain'] != false && ((p['features'] as Map?) ?? const {}).containsKey('custom_domain')))
                Padding(padding: const EdgeInsets.symmetric(vertical: 2), child: Text('${b.$2 ? '✓' : '✕'} ${b.$1}', style: TextStyle(color: b.$2 ? null : const Color(0xFFA0AAB6), decoration: b.$2 ? null : TextDecoration.lineThrough))),
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

/// Guided DNS setup of one custom domain: what each record is for, exactly what to type, and whether it is already right (checked live every 15 seconds).
class DnsSetupPanel extends StatefulWidget {
  const DnsSetupPanel({super.key, required this.api, required this.id, required this.host, required this.onVerified});
  final ApiClient api;
  final String id, host;
  final Future<void> Function() onVerified;
  @override
  State<DnsSetupPanel> createState() => _DnsSetupPanelState();
}

class _DnsSetupPanelState extends State<DnsSetupPanel> {
  List<Map<String, dynamic>> _records = const [];
  bool _busy = false;
  Object? _error;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _check();
    _timer = Timer.periodic(const Duration(seconds: 15), (_) => _check());
  }

  @override
  void dispose() { _timer?.cancel(); super.dispose(); }

  Future<void> _check() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final r = Map<String, dynamic>.from(await widget.api.get('tenant/domains/${widget.id}/check') as Map);
      if (!mounted) return;
      setState(() { _records = _maps(r['records']); _error = null; });
      if (r['verified'] == true) await widget.onVerified();
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _purpose(Map<String, dynamic> r, bool ar) {
    final name = '${r['purpose']}' == 'VERIFY' ? widget.host : '${r['name']}';
    return switch ('${r['purpose']}') {
      'VERIFY' => ar ? 'تثبت أن $name ملكك' : 'Proves $name is yours',
      _ => ar ? 'توجّه $name إلى موقعك' : 'Points $name to your site',
    };
  }

  String _hint(Map<String, dynamic> r, bool ar) {
    final found = ((r['found'] as List?) ?? const []).join(', ');
    return switch ('${r['hint']}') {
      'CNAME_INSTEAD_OF_TXT' => ar ? 'هذا الاسم عليه سجل CNAME ($found). احذفه وأضف سجل TXT بدلًا منه.' : 'This name has a CNAME ($found). Delete it and add a TXT record instead.',
      'WRONG_VALUE' => ar ? 'يوجد سجل TXT لكن قيمته مختلفة. استبدلها بالقيمة أعلاه.' : 'A TXT record exists but its value is different. Replace it with the value above.',
      'OTHER_ADDRESS' => ar ? 'يشير إلى $found لكن يجب أن يشير إلى العنوان أعلاه. عدّل السجل القديم أو احذفه.' : 'It points to $found, but it must point to the address above. Edit or delete the old record.',
      _ => '',
    };
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final verifyOk = _records.any((r) => r['purpose'] == 'VERIFY' && r['status'] == 'OK');
    Widget step(int n, String text, bool on, bool done) => Expanded(child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 2),
          padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 6),
          decoration: BoxDecoration(color: on ? const Color(0xFFE5F0F8) : done ? const Color(0xFFE6F6EC) : Colors.white, borderRadius: BorderRadius.circular(12), border: Border.all(color: on ? const Color(0xFF1F6A99) : done ? const Color(0xFF9AD5B1) : const Color(0xFFD5DEE8))),
          child: Column(children: [CircleAvatar(radius: 11, backgroundColor: on ? const Color(0xFF1F6A99) : done ? const Color(0xFF1E9E5A) : const Color(0xFFD5DEE8), child: done ? const Icon(Icons.check, size: 14, color: Colors.white) : Text('$n', style: TextStyle(fontSize: 12, color: on ? Colors.white : const Color(0xFF6B7C8D), fontWeight: FontWeight.w800))), const SizedBox(height: 4), Text(text, textAlign: TextAlign.center, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: on ? const Color(0xFF1F6A99) : const Color(0xFF6B7C8D)))]),
        ));
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [step(1, ar ? 'أضف نطاقك' : 'Add domain', false, true), step(2, ar ? 'أضف سجلات DNS' : 'Add DNS records', !verifyOk, verifyOk), step(3, ar ? 'نتحقق ونفعّل HTTPS' : 'We verify + HTTPS', verifyOk, false)]),
        const SizedBox(height: 10),
        Text(ar ? 'ادخل على موقع مزوّد النطاق (GoDaddy أو Namecheap أو Cloudflare ...) وافتح إعدادات DNS وأضف السجلات التالية. اضغط على أيقونة النسخ والصق كل قيمة كما هي.' : 'Open your domain provider (GoDaddy, Namecheap, Cloudflare...), go to DNS settings and add the records below. Tap the copy icon and paste each value exactly.', style: const TextStyle(color: Color(0xFF4A5B6C), fontSize: 13)),
        const SizedBox(height: 8),
        for (final r in _records)
          Container(
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: r['status'] == 'OK' ? const Color(0xFFF0FAF4) : r['status'] == 'WRONG' ? const Color(0xFFFDF0F0) : const Color(0xFFF7F9FB), borderRadius: BorderRadius.circular(14), border: Border.all(color: r['status'] == 'OK' ? const Color(0xFFBFE5CC) : r['status'] == 'WRONG' ? const Color(0xFFF0B7B7) : const Color(0xFFE1E8EF))),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Container(padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2), decoration: BoxDecoration(color: const Color(0xFF14232B), borderRadius: BorderRadius.circular(8)), child: Text('${r['type']}', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 12))),
                const SizedBox(width: 8),
                Expanded(child: Text(_purpose(r, ar), style: const TextStyle(fontWeight: FontWeight.w800))),
              ]),
              const SizedBox(height: 6),
              Wrap(spacing: 6, children: [
                _chip(r['status'] == 'OK' ? (ar ? '✓ موجود وصحيح' : '✓ Found, correct') : r['status'] == 'WRONG' ? (ar ? '! يحتاج تصحيح' : '! Needs a fix') : (ar ? '… لم يظهر بعد' : '… Not found yet'), r['status'] == 'OK' ? const Color(0xFF1E9E5A) : r['status'] == 'WRONG' ? const Color(0xFFD14343) : const Color(0xFFD98200)),
                if (r['purpose'] == 'WWW') _chip(ar ? 'مُستحسن' : 'Recommended', const Color(0xFF1F6A99)),
              ]),
              const SizedBox(height: 4),
              CopyRow(ar ? 'الاسم / Host' : 'Name / Host', '${r['shortName']}'),
              CopyRow(ar ? 'القيمة' : 'Value', '${r['value']}'),
              if (r['status'] == 'WRONG' && '${r['hint'] ?? ''}'.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 4), child: Text(_hint(r, ar), style: const TextStyle(color: Color(0xFFB71C1C), fontWeight: FontWeight.w700, fontSize: 12.5))),
            ]),
          ),
        if (_error != null) Text(errorText(_error!), style: const TextStyle(color: Color(0xFFB71C1C))),
        Row(children: [
          FilledButton.tonalIcon(onPressed: _busy ? null : _check, icon: _busy ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.refresh_rounded), label: Text(ar ? 'افحص مجددًا' : 'Check again')),
          const SizedBox(width: 8),
          Expanded(child: Text(ar ? 'نعيد الفحص كل 15 ثانية' : 'We re-check every 15 seconds', style: const TextStyle(fontSize: 11.5, color: Color(0xFF8A99AA)))),
        ]),
        const SizedBox(height: 6),
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: Text(ar ? 'أخطاء شائعة' : 'Common mistakes', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 14)),
          children: [for (final m in (ar
              ? ['في خانة الاسم اكتب الاسم المختصر فقط (مثل _platform-verify أو @ أو www) وليس النطاق كاملًا.', 'اختر نوع السجل بدقة: TXT للإثبات، وA للنطاق الرئيسي، وCNAME لـ www.', 'النطاق الرئيسي لا يمكن أن يكون CNAME: استخدم سجل A.', 'احذف سجلات A أو CNAME القديمة التي بنفس الاسم وتشير إلى مكان آخر.', 'لا تكتب https:// في أي خانة. قد يستغرق التغيير من دقائق حتى ساعة.']
              : ['In the Name box type only the short name (like _platform-verify, @ or www), not the whole domain.', 'Pick the exact type: TXT for the proof, A for the root domain, CNAME for www.', 'The root domain cannot be a CNAME: use an A record.', 'Delete old A or CNAME records of the same name that point elsewhere.', 'Do not type https:// anywhere. Changes can take minutes up to an hour.'])) Padding(padding: const EdgeInsets.only(bottom: 6), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [const Text('• '), Expanded(child: Text(m, style: const TextStyle(fontSize: 13, color: Color(0xFF4A5B6C))))]))],
        ),
      ]),
    );
  }

  Widget _chip(String t, Color c) => Container(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3), decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(20)), child: Text(t, style: TextStyle(color: c, fontWeight: FontWeight.w800, fontSize: 12)));
}
