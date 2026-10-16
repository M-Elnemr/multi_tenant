import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';

/// What the "free plan ends / renew" bar shows. `state` is TRIAL_ENDING or WAIT_RENEW (the backend sends OK / DISABLED too).
class BillingBanner {
  const BillingBanner(this.state, this.days);
  final String state;
  final int days;
}

/// The bar is drawn above the whole navigator (see [BillingBarHost]), so it shows on every screen after login; the business that is open feeds it.
class BillingHub {
  static final banner = ValueNotifier<BillingBanner?>(null);
  static final hide = ValueNotifier<int>(0);   // >0 while the pay screen itself is open
  static ApiClient? api;

  static void clear() { banner.value = null; api = null; }

  /// Reads the subscription state and updates the bar. Returns the state string (OK / TRIAL_ENDING / WAIT_RENEW / DISABLED), or null when unreachable.
  static Future<String?> refresh(ApiClient client, {required bool canPay}) async {
    try {
      final r = await client.get('billing/status') as Map;
      final state = '${r['state']}';
      api = client;
      banner.value = canPay && (state == 'TRIAL_ENDING' || state == 'WAIT_RENEW') ? BillingBanner(state, (r['daysLeft'] as num?)?.toInt() ?? 0) : null;
      return state;
    } catch (_) {
      return null;
    }
  }
}

String billingBarText(BillingBanner b) {
  final ar = AppLocale.isAr;
  if (b.state == 'WAIT_RENEW') {
    return b.days > 0
        ? (ar ? 'انتهى اشتراكك. جدّد خلال ${b.days} يوم وإلا سيتم إيقاف نشاطك' : 'Your subscription ended. Renew within ${b.days} days or your business will be disabled')
        : (ar ? 'انتهى اشتراكك. جدّد اليوم وإلا سيتم إيقاف نشاطك' : 'Your subscription ended. Renew today or your business will be disabled');
  }
  return b.days > 0
      ? (ar ? 'تنتهي خطتك المجانية خلال ${b.days} يوم. رقّ اشتراكك الآن' : 'Your free plan ends in ${b.days} days. Upgrade now')
      : (ar ? 'تنتهي خطتك المجانية اليوم. رقّ اشتراكك الآن' : 'Your free plan ends today. Upgrade now');
}

/// Wraps the navigator (MaterialApp.builder): the bar sits on top of every page, also the ones pushed later; tapping it opens the pay screen.
class BillingBarHost extends StatelessWidget {
  const BillingBarHost({super.key, required this.child});
  final Widget? child;
  @override
  Widget build(BuildContext context) {
    final page = child ?? const SizedBox.shrink();
    return ValueListenableBuilder<int>(
      valueListenable: BillingHub.hide,
      builder: (context, hidden, _) => ValueListenableBuilder<BillingBanner?>(
        valueListenable: BillingHub.banner,
        builder: (context, b, _) {
          final api = BillingHub.api;
          if (b == null || hidden > 0 || api == null) return page;
          final renew = b.state == 'WAIT_RENEW';
          return Column(children: [
            Material(
              color: renew ? const Color(0xFFC62828) : const Color(0xFFB45F06),
              child: InkWell(
                onTap: () => navigatorKey.currentState?.push(MaterialPageRoute(builder: (_) => PayPage(api: api))),
                child: Padding(
                  padding: EdgeInsets.fromLTRB(14, MediaQuery.of(context).padding.top + 8, 14, 8),
                  child: Row(children: [
                    const Icon(Icons.notifications_active_rounded, color: Colors.white, size: 18),
                    const SizedBox(width: 8),
                    Expanded(child: Text(billingBarText(b), style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 12.5, decoration: TextDecoration.none))),
                    const SizedBox(width: 8),
                    Container(padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4), decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(12)), child: Text(AppLocale.isAr ? 'التفاصيل' : 'Details', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 12, decoration: TextDecoration.none))),
                  ]),
                ),
              ),
            ),
            Expanded(child: MediaQuery.removePadding(context: context, removeTop: true, child: page)),
          ]);
        },
      ),
    );
  }
}

String planLabel(String? code) {
  final ar = AppLocale.isAr;
  final yearly = (code ?? '').endsWith('_YEARLY');
  final kind = (code ?? '').replaceAll('_YEARLY', '').split('_').last;
  final base = switch (kind) {
    'FREE' => ar ? 'مجانية (14 يومًا)' : 'Free (14 days)',
    'STANDARD' => ar ? 'قياسية' : 'Standard',
    'PRO' => ar ? 'احترافية' : 'Professional',
    _ => code ?? '-',
  };
  return yearly ? '$base (${ar ? 'سنوي' : 'yearly'})' : base;
}

/// The real benefits of a plan in plain words; the last line says whether a custom domain is included.
List<(String, bool)> planBenefits({required bool store, required bool domain}) {
  final ar = AppLocale.isAr;
  final list = store
      ? (ar
          ? ['متجرك الإلكتروني الخاص جاهز في دقائق على رابط باسمك', 'تطبيق موبايل لإدارة متجرك من أي مكان', 'إشعار فوري على موبايلك مع كل طلب جديد', 'كل عملائك بتفاصيلهم: اتصال أو واتساب بضغطة واحدة', 'منتجات بمقاسات وألوان وأسعار وصور، وإدارة سهلة', 'تابع كل طلب: تجهيز، شحن، تسليم، وتعامل مع المرتجعات', 'الدفع عند الاستلام ومناطق شحن بأسعارك أنت', 'إدارة المخزون مع تنبيه قبل أن ينفد المنتج', 'كوبونات وخصومات لزيادة مبيعاتك، وتقييمات العملاء لمنتجاتك لبناء الثقة', 'تقارير المبيعات وفريق عمل بصلاحيات مختلفة']
          : ['Your own online shop, live in minutes on your own address', 'Mobile app to run your shop from anywhere', 'Instant phone notification on every new order', 'All your customers with their details: call or WhatsApp in one tap', 'Products with sizes, colors, prices and photos', 'Follow every order: preparing, shipped, delivered, and handle returns', 'Cash on delivery and shipping zones with your own prices', 'Stock control with a low-stock alert', 'Coupons and discounts to grow sales, and customer reviews to build trust', 'Sales reports and a team with different permissions'])
      : (ar
          ? ['موقع لعيادتك مع الحجز أونلاين جاهز في دقائق', 'تطبيق موبايل للدكتور والسكرتارية', 'قاعة الانتظار والدور: المريض يصله إشعار عند دوره', 'ملف كامل لكل مريض: الزيارات والروشتات وطلبات التحاليل', 'المريض يرفع نتيجة التحليل ويصلك إشعار فورًا', 'رقم مريض موحد مع QR، والأطفال مرتبطون بولي الأمر', 'إشعار فوري مع كل حجز جديد', 'يعمل مع ضعف الإنترنت ويزامن بياناتك بعد ذلك', 'فريق عملك بصلاحيات مختلفة']
          : ['Your clinic website with online booking, live in minutes', 'Mobile app for the doctor and the receptionist', 'Waiting room and queue: patients get a notification on their turn', 'Complete patient file: visits, prescriptions and lab requests', 'Patients upload test results and you are notified at once', 'One patient ID with a QR code, children linked to a parent', 'Instant notification on every new booking', 'Keeps working with a weak connection and syncs later', 'Your team with different permissions']);
  return [for (final b in list) (b, true), (domain ? (ar ? 'نطاق خاص باسم علامتك التجارية (مثل www.yourbrand.com)' : 'Your own domain name (like www.yourbrand.com)') : (ar ? 'بدون نطاق خاص' : 'No custom domain'), domain)];
}

/// A pushed page around [PayBody] (opened from the bar and from the billing screen).
class PayPage extends StatelessWidget {
  const PayPage({super.key, required this.api, this.plan});
  final ApiClient api;
  final String? plan;
  @override
  Widget build(BuildContext context) => Scaffold(appBar: AppBar(title: Text(AppLocale.isAr ? 'الاشتراك' : 'Subscription')), body: PayBody(api: api, plan: plan));
}

/// Manual payment for now: the owner presses "Proceed to pay" and customer service calls them (online payment is not live yet).
class PayBody extends StatefulWidget {
  const PayBody({super.key, required this.api, this.plan, this.disabled = false});
  final ApiClient api;
  final String? plan;
  final bool disabled;
  @override
  State<PayBody> createState() => _PayBodyState();
}

class _PayBodyState extends State<PayBody> {
  final _phone = TextEditingController();
  String? _picked;
  String? _cycle;   // 'month' | 'year'
  Map<String, dynamic>? _saved;
  bool _editing = false, _busy = false, _phoneSet = false;
  int _n = 0;

  @override
  void initState() {
    super.initState();
    BillingHub.hide.value++;
  }

  @override
  void dispose() {
    BillingHub.hide.value--;
    _phone.dispose();
    super.dispose();
  }

  Future<List<dynamic>> _load() => Future.wait([
        widget.api.get('billing/status'),
        widget.api.get('billing/plans'),
        widget.api.get('billing/pay-requests').catchError((_) => <dynamic>[]),
        AuthRepository(widget.api).me().catchError((_) => <String, dynamic>{}),
      ]);

  Future<void> _send(String planCode) async {
    setState(() => _busy = true);
    try {
      final r = await widget.api.post('billing/pay-requests', {'planCode': planCode, 'phone': _phone.text.trim()}) as Map;
      if (!mounted) return;
      setState(() { _saved = Map<String, dynamic>.from(r); _editing = false; _n++; });
    } catch (e) {
      if (mounted) toast(context, errorText(e), isError: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return Async<List<dynamic>>(
      key: ValueKey(_n),
      load: _load,
      builder: (context, data, reload) {
        final status = Map<String, dynamic>.from(data[0] as Map);
        final paid = (data[1] as List).cast<Map>().map((e) => Map<String, dynamic>.from(e)).where((p) => ((p['priceMinor'] as num?) ?? 0) > 0).toList();
        final cycle = _cycle ?? ('${_picked ?? widget.plan ?? status['planCode'] ?? ''}'.endsWith('_YEARLY') ? 'year' : 'month');
        final plans = paid.where((p) => (p['interval'] == 'YEAR') == (cycle == 'year')).toList();
        String? sameLevel(String? c) { final base = (c ?? '').replaceAll('_YEARLY', ''); for (final p in plans) { if ('${p['code']}'.replaceAll('_YEARLY', '') == base) return '${p['code']}'; } return null; }
        final reqs = (data[2] as List).cast<Map>().map((e) => Map<String, dynamic>.from(e)).toList();
        final me = Map<String, dynamic>.from(data[3] as Map);
        if (!_phoneSet) { _phone.text = localPhone(me['phone']); _phoneSet = true; }
        final open = _saved ?? reqs.cast<Map<String, dynamic>?>().firstWhere((r) => r!['status'] == 'NEW' || r['status'] == 'CONTACTED', orElse: () => null);
        final choice = sameLevel(_picked ?? widget.plan) ?? sameLevel('${status['planCode']}') ?? (plans.isEmpty ? '' : '${plans.first['code']}');
        final disabled = widget.disabled || status['state'] == 'DISABLED';
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            if (disabled) Container(
              margin: const EdgeInsets.only(bottom: 12),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(color: const Color(0xFFFDECEC), borderRadius: BorderRadius.circular(16), border: Border.all(color: const Color(0xFFE9A3A3))),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(ar ? 'نشاطك متوقف' : 'Your business is disabled', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16, color: Color(0xFFB71C1C))),
                const SizedBox(height: 4),
                Text(ar ? 'لا يستطيع موقعك ولا مرضاك ولا عملاؤك الوصول إلى نشاطك حتى تجدّد الاشتراك. اختر الباقة واطلب اتصالًا من خدمة العملاء.' : 'Your website, patients and customers cannot reach your business until you renew. Choose a plan and request a call from customer service.'),
              ]),
            ),
            if (!disabled && status['planCode'] != null) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(ar ? 'باقتك الحالية' : 'Current plan', style: const TextStyle(color: Color(0xFF6B7C8D))),
              Text(planLabel('${status['planCode']}'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
              if (status['periodEnd'] != null) Text((ar ? 'ينتهي في ' : 'Ends on ') + fmtDate('${status['periodEnd']}', time: false)),
            ]))),
            Container(
              margin: const EdgeInsets.symmetric(vertical: 8),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(color: const Color(0xFFE8F3FA), borderRadius: BorderRadius.circular(14)),
              child: Text(ar ? 'للاشتراك أو التجديد اضغط «طلب اتصال» وسيتواصل معك فريق خدمة العملاء قريبًا جدًا.' : 'To subscribe or renew, press “Request a call” and our customer service will contact you very soon.', style: const TextStyle(color: Color(0xFF14516F), fontWeight: FontWeight.w600)),
            ),
            if (open != null && !_editing)
              Card(child: Padding(padding: const EdgeInsets.all(18), child: Column(children: [
                const Icon(Icons.check_circle_rounded, color: Color(0xFF1E9E5A), size: 46),
                const SizedBox(height: 6),
                Text(ar ? 'تم استلام طلبك' : 'We received your request', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
                const SizedBox(height: 4),
                Text(ar ? 'سيتواصل معك فريق خدمة العملاء على ${localPhone(open['phone'])} قريبًا جدًا لإتمام اشتراكك.' : 'Customer service will contact you on ${localPhone(open['phone'])} very soon to complete your subscription.', textAlign: TextAlign.center),
                const SizedBox(height: 6),
                Text('${planLabel('${open['planCode']}')} · ${fmtMoney(open['amountMinor'] as num?)} ${open['currency'] ?? 'EGP'}', style: const TextStyle(fontWeight: FontWeight.w700)),
                TextButton(onPressed: () => setState(() { _picked = '${open['planCode']}'; _phone.text = localPhone(open['phone']); _editing = true; }), child: Text(ar ? 'تعديل طلبي' : 'Change my request')),
              ])))
            else ...[
              sectionTitle(ar ? 'اختر باقتك' : 'Choose your plan'),
              Wrap(spacing: 8, children: [
                ChoiceChip(label: Text(ar ? 'شهري' : 'Monthly'), selected: cycle == 'month', showCheckmark: false, onSelected: (_) => setState(() { _cycle = 'month'; _picked = null; })),
                ChoiceChip(label: Text(ar ? 'سنوي · شهران مجانًا' : 'Yearly · 2 months free'), selected: cycle == 'year', showCheckmark: false, onSelected: (_) => setState(() { _cycle = 'year'; _picked = null; })),
              ]),
              const SizedBox(height: 8),
              for (final p in plans) _PlanTile(plan: p, selected: p['code'] == choice, onTap: () => setState(() => _picked = '${p['code']}')),
              const SizedBox(height: 8),
              TextField(
                controller: _phone,
                keyboardType: TextInputType.phone,
                textDirection: TextDirection.ltr,
                inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9+]'))],
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(labelText: ar ? 'رقم الموبايل للتواصل' : 'Mobile number we should call', helperText: ar ? 'سيتصل بك فريق خدمة العملاء على هذا الرقم.' : 'Customer service will contact you on this number.', prefixIcon: const Icon(Icons.phone_rounded)),
              ),
              const SizedBox(height: 14),
              FilledButton(
                onPressed: _busy || choice.isEmpty || _phone.text.trim().length < 8 ? null : () => _send(choice),
                child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(ar ? 'طلب اتصال من خدمة العملاء' : 'Request a call from customer service'),
              ),
            ],
          ]),
        );
      },
    );
  }
}

class _PlanTile extends StatelessWidget {
  const _PlanTile({required this.plan, required this.selected, required this.onTap});
  final Map<String, dynamic> plan;
  final bool selected;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    final features = (plan['features'] as Map?) ?? const {};
    final domain = features['custom_domain'] != false && features.containsKey('custom_domain');
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18), border: Border.all(color: selected ? scheme.primary : const Color(0xFFD5DEE8), width: selected ? 2.2 : 1)),
          child: Row(children: [
            Icon(selected ? Icons.radio_button_checked_rounded : Icons.radio_button_off_rounded, color: selected ? scheme.primary : const Color(0xFF9AA9BA)),
            const SizedBox(width: 12),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(planLabel('${plan['code']}'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
              Text('${domain ? '✓' : '✕'} ${domain ? (ar ? 'يشمل نطاقًا خاصًا' : 'Includes a custom domain') : (ar ? 'بدون نطاق خاص' : 'No custom domain')}', style: TextStyle(color: domain ? const Color(0xFF1E7A4A) : const Color(0xFF6B7C8D), fontSize: 13)),
            ])),
            Text('${fmtMoney(plan['priceMinor'] as num?)} ${plan['currency']}\n${plan['interval'] == 'YEAR' ? (ar ? '/ سنة' : '/ year') : (ar ? '/ شهر' : '/ month')}', textAlign: TextAlign.end, style: const TextStyle(fontWeight: FontWeight.w900)),
          ]),
        ),
      ),
    );
  }
}
