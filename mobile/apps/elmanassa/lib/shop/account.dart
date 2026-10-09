import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

String statusLabel(S s, String code) => switch (code) {
      'REQUESTED' || 'PENDING' => s.ar ? 'تم الطلب' : 'Requested',
      'PREPARING' => s.ar ? 'جاري التجهيز' : 'Preparing',
      'SHIPPED' => s.ar ? 'تم الشحن' : 'Shipped',
      'ARRIVED' => s.ar ? 'وصل' : 'Arrived',
      'RETURNED' => s.ar ? 'مرتجع' : 'Returned',
      'REFUNDED' => s.ar ? 'تم الاسترداد' : 'Refunded',
      'CANCELLED' => s.ar ? 'ملغي' : 'Cancelled',
      _ => code,
    };

/// Orders tab: follow any order with its number and phone (guests included), or sign in with Google to see all your orders.
class OrdersPage extends StatefulWidget {
  const OrdersPage({super.key, required this.ctx});
  final StoreCtx ctx;
  @override
  State<OrdersPage> createState() => _OrdersPageState();
}

class _OrdersPageState extends State<OrdersPage> {
  final number = TextEditingController(), phone = TextEditingController();
  int refresh = 0;

  @override
  void dispose() { number.dispose(); phone.dispose(); super.dispose(); }

  Future<void> _deleteAccount(BuildContext context, S s) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(s.ar ? 'حذف الحساب؟' : 'Delete your account?'),
        content: Text(s.ar
            ? 'سيتم حذف حسابك وعناوينك وقائمة المفضلة نهائيًا. تبقى طلباتك السابقة لدى المتاجر لأغراض المحاسبة والمرتجعات. لا يمكن التراجع.'
            : 'Your account, addresses and wishlist will be deleted permanently. Past orders stay with the shops for their records and returns. This cannot be undone.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: Text(s.ar ? 'إلغاء' : 'Cancel')),
          FilledButton(style: FilledButton.styleFrom(backgroundColor: Theme.of(c).colorScheme.error), onPressed: () => Navigator.pop(c, true), child: Text(s.ar ? 'احذف حسابي' : 'Delete')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await widget.ctx.api.delete('shop/me');
      try { await GoogleSignIn().signOut(); } catch (_) {}
      await widget.ctx.api.session.clear();
      if (mounted) setState(() => refresh++);
    } catch (e) {
      if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s.ar ? 'تعذّر حذف الحساب، حاول مرة أخرى' : 'Could not delete the account, try again')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final api = widget.ctx.api;
    final cs = Theme.of(context).colorScheme;
    return ListView(padding: const EdgeInsets.all(16), children: [
      Text(s.ar ? 'أين طلبي؟' : 'Where is my order?', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900)),
      const SizedBox(height: 4),
      Text(s.ar ? 'أدخل رقم الطلب ورقم الهاتف الذي استخدمته.' : 'Enter your order number and the phone you used.', style: const TextStyle(color: Colors.grey)),
      const SizedBox(height: 12),
      TextField(controller: number, decoration: InputDecoration(labelText: s.ar ? 'رقم الطلب' : 'Order number', border: const OutlineInputBorder(), hintText: 'ST-20261009-000001'), textDirection: TextDirection.ltr),
      const SizedBox(height: 10),
      TextField(controller: phone, keyboardType: TextInputType.phone, decoration: InputDecoration(labelText: s.ar ? 'رقم الموبايل' : 'Mobile number', border: const OutlineInputBorder())),
      const SizedBox(height: 10),
      FilledButton(
        onPressed: () { if (number.text.trim().isNotEmpty && phone.text.trim().isNotEmpty) Navigator.of(context).push(MaterialPageRoute(builder: (_) => TrackPage(ctx: widget.ctx, orderNumber: number.text.trim(), phone: phone.text.trim()))); },
        child: Padding(padding: const EdgeInsets.all(6), child: Text(s.ar ? 'تتبع' : 'Track')),
      ),
      const Divider(height: 40),
      FutureBuilder<bool>(
        key: ValueKey(refresh),
        future: api.session.loggedIn,
        builder: (context, snap) {
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          if (!snap.data!) {
            return Column(children: [
              Text(s.ar ? 'سجّل الدخول بجوجل لمتابعة كل طلباتك. يمكنك الطلب دون حساب.' : 'Sign in with Google to see all your orders. You can order without an account.', textAlign: TextAlign.center, style: TextStyle(color: cs.onSurfaceVariant)),
              const SizedBox(height: 12),
              GoogleSignInButton(api: api, onSignedIn: () => setState(() => refresh++)),
            ]);
          }
          return Async<Map<String, dynamic>>(
            load: () async => await api.get('shop/orders') as Map<String, dynamic>,
            builder: (context, data, reload) {
              final orders = (data['data'] as List).cast<Map<String, dynamic>>();
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                if (orders.isEmpty) Center(child: Padding(padding: const EdgeInsets.all(12), child: Text(s.empty))) else Text(s.ar ? 'طلباتي' : 'My orders', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
                for (final o in orders) Card(child: ListTile(
                  title: Text(o['orderNumber'] as String, style: const TextStyle(fontWeight: FontWeight.w800), textDirection: TextDirection.ltr, textAlign: TextAlign.start),
                  subtitle: Text(statusLabel(s, o['status'] as String)),
                  trailing: Text(s.money(o['totalMinor'] as num, widget.ctx.tenant.currency), style: const TextStyle(fontWeight: FontWeight.w900)),
                  onTap: () async {
                    final detail = await api.get('shop/orders/${o['id']}') as Map<String, dynamic>;
                    final ph = (detail['customerPhoneSnapshot'] as String?) ?? '';
                    if (context.mounted) Navigator.of(context).push(MaterialPageRoute(builder: (_) => TrackPage(ctx: widget.ctx, orderNumber: o['orderNumber'] as String, phone: ph)));
                  },
                )),
                const SizedBox(height: 24),
                Center(child: TextButton.icon(
                  icon: const Icon(Icons.delete_outline),
                  style: TextButton.styleFrom(foregroundColor: cs.error),
                  label: Text(s.ar ? 'حذف حسابي' : 'Delete my account'),
                  onPressed: () => _deleteAccount(context, s),
                )),
              ]);
            },
          );
        },
      ),
    ]);
  }
}

/// One order's progress, shipment, items and (when allowed) a return request.
class TrackPage extends StatefulWidget {
  const TrackPage({super.key, required this.ctx, required this.orderNumber, required this.phone});
  final StoreCtx ctx;
  final String orderNumber, phone;
  @override
  State<TrackPage> createState() => _TrackPageState();
}

class _TrackPageState extends State<TrackPage> {
  late Future<Map<String, dynamic>> future = _load();
  String reason = 'SIZE';
  final details = TextEditingController();
  String? error;
  bool busy = false;

  Future<Map<String, dynamic>> _load() async => await widget.ctx.api.post('shop/track', {'orderNumber': widget.orderNumber, 'phone': widget.phone}) as Map<String, dynamic>;

  @override
  void dispose() { details.dispose(); super.dispose(); }

  Future<void> _return() async {
    setState(() { busy = true; error = null; });
    try {
      await widget.ctx.api.post('shop/track/return', {'orderNumber': widget.orderNumber, 'phone': widget.phone, 'reason': reason, 'details': details.text});
      setState(() => future = _load());
    } catch (e) {
      setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final cur = widget.ctx.tenant.currency;
    final cs = Theme.of(context).colorScheme;
    const steps = ['REQUESTED', 'PREPARING', 'SHIPPED', 'ARRIVED'];
    const reasons = {'DEFECTIVE': ['معيب أو تالف', 'Defective or damaged'], 'WRONG_ITEM': ['منتج خاطئ', 'Wrong item'], 'NOT_AS_DESCRIBED': ['غير مطابق للوصف', 'Not as described'], 'SIZE': ['المقاس غير مناسب', 'Size or fit'], 'CHANGED_MIND': ['غيرت رأيي', 'Changed my mind'], 'OTHER': ['سبب آخر', 'Other']};
    return Scaffold(
      appBar: AppBar(title: Text(widget.orderNumber, textDirection: TextDirection.ltr)),
      body: FutureBuilder<Map<String, dynamic>>(
        future: future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
          if (snap.hasError) return Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(errorText(snap.error!), textAlign: TextAlign.center)));
          final o = snap.data!;
          final status = o['status'] as String;
          final idx = steps.indexOf(status);
          final closed = ['CANCELLED', 'RETURNED', 'REFUNDED'].contains(status);
          final items = (o['items'] as List).cast<Map<String, dynamic>>();
          final ret = o['returnRequest'] as Map?;
          return ListView(padding: const EdgeInsets.all(16), children: [
            if (!closed) Row(children: [for (var i = 0; i < steps.length; i++) Expanded(child: Column(children: [
              CircleAvatar(radius: 18, backgroundColor: i <= idx ? cs.primary : cs.surfaceContainerHighest, child: i < idx ? const Icon(Icons.check, size: 18, color: Colors.white) : Text('${i + 1}', style: TextStyle(color: i <= idx ? Colors.white : Colors.grey, fontWeight: FontWeight.w800))),
              const SizedBox(height: 6),
              Text(statusLabel(s, steps[i]), textAlign: TextAlign.center, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: i <= idx ? null : Colors.grey)),
            ]))]) else Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: cs.surfaceContainerHighest, borderRadius: BorderRadius.circular(14)), child: Center(child: Text(statusLabel(s, status), style: const TextStyle(fontWeight: FontWeight.w900)))),
            if (status == 'REQUESTED' && o['confirmed'] == false) Padding(padding: const EdgeInsets.only(top: 14), child: Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: Colors.amber.shade50, borderRadius: BorderRadius.circular(14)), child: Text(s.ar ? '📞 سنتصل بك قريبًا لتأكيد طلبك.' : '📞 We will call you shortly to confirm your order.', style: TextStyle(color: Colors.amber.shade900, fontWeight: FontWeight.w700)))),
            if ((o['courierName'] as String?)?.isNotEmpty == true || (o['trackingNumber'] as String?)?.isNotEmpty == true) Card(margin: const EdgeInsets.only(top: 14), child: ListTile(
              leading: const Icon(Icons.local_shipping_outlined), title: Text(s.ar ? 'الشحنة' : 'Shipment', style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text('${o['courierName'] ?? ''} ${o['trackingNumber'] ?? ''}'.trim()),
              trailing: (o['trackingUrl'] as String?)?.isNotEmpty == true ? TextButton(onPressed: () => openUrl(o['trackingUrl'] as String), child: Text(s.ar ? 'تتبع' : 'Follow')) : null,
            )),
            const SizedBox(height: 14),
            for (final i in items) ListTile(contentPadding: EdgeInsets.zero, title: Text('${i['productNameSnapshot']} × ${i['quantity']}'), subtitle: (i['variantNameSnapshot'] as String?)?.isNotEmpty == true ? Text(i['variantNameSnapshot'] as String) : null, trailing: Text(s.money(i['lineTotalMinor'] as num, cur), style: const TextStyle(fontWeight: FontWeight.w800))),
            const Divider(),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(s.ar ? 'الشحن' : 'Delivery'), Text(((o['shippingMinor'] as num?) ?? 0) == 0 ? (s.ar ? 'مجاني' : 'Free') : s.money(o['shippingMinor'] as num, cur))]),
            if (((o['codFeeMinor'] as num?) ?? 0) > 0) Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(s.ar ? 'رسوم الدفع عند الاستلام' : 'COD fee'), Text(s.money(o['codFeeMinor'] as num, cur))]),
            const SizedBox(height: 6),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(s.total, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)), Text(s.money(o['totalMinor'] as num, cur), style: TextStyle(fontWeight: FontWeight.w900, fontSize: 17, color: cs.primary))]),
            if (ret != null) Padding(padding: const EdgeInsets.only(top: 14), child: Text('↩️ ${s.ar ? 'طلب الاسترجاع' : 'Return request'}: ${ret['status']}', style: const TextStyle(fontWeight: FontWeight.w800))),
            if (o['canReturn'] == true) ...[
              const Divider(height: 32),
              Text(s.ar ? 'طلب استرجاع' : 'Request a return', style: const TextStyle(fontWeight: FontWeight.w900)),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(initialValue: reason, decoration: const InputDecoration(border: OutlineInputBorder()), items: [for (final e in reasons.entries) DropdownMenuItem(value: e.key, child: Text(s.ar ? e.value[0] : e.value[1]))], onChanged: (v) => setState(() => reason = v ?? 'SIZE')),
              const SizedBox(height: 10),
              TextField(controller: details, maxLines: 3, decoration: InputDecoration(border: const OutlineInputBorder(), labelText: s.ar ? 'تفاصيل (اختياري)' : 'Details (optional)')),
              if (error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(error!, style: TextStyle(color: cs.error))),
              const SizedBox(height: 10),
              FilledButton(onPressed: busy ? null : _return, child: Text(s.ar ? 'إرسال الطلب' : 'Send request')),
            ],
          ]);
        },
      ),
    );
  }
}

/// Google sign-in for shop clients. The OAuth Web client id is public; the Android client is matched by package + signing key.
class GoogleSignInButton extends StatefulWidget {
  const GoogleSignInButton({super.key, required this.api, required this.onSignedIn});
  final ApiClient api;
  final VoidCallback onSignedIn;
  @override
  State<GoogleSignInButton> createState() => _GoogleSignInButtonState();
}

class _GoogleSignInButtonState extends State<GoogleSignInButton> {
  static const clientId = String.fromEnvironment('GOOGLE_CLIENT_ID', defaultValue: '424377343706-6tbunl2hl4ev6ekqu9h2h2ppanarhi89.apps.googleusercontent.com');
  String? error;
  bool busy = false;

  Future<void> _go() async {
    setState(() { busy = true; error = null; });
    try {
      final account = await GoogleSignIn(serverClientId: clientId, scopes: const ['email']).signIn();
      if (account == null) return;
      final idToken = (await account.authentication).idToken;
      if (idToken == null) throw StateError('No Google token');
      final r = await widget.api.post('auth/client/google', {'credential': idToken}) as Map<String, dynamic>;
      await widget.api.session.save(r);
      widget.onSignedIn();
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (clientId.isEmpty) return Text(s.ar ? 'تسجيل الدخول بجوجل سيتوفر قريبًا.' : 'Google sign-in is coming soon.', style: Theme.of(context).textTheme.bodySmall);
    return Column(mainAxisSize: MainAxisSize.min, children: [
      OutlinedButton.icon(onPressed: busy ? null : _go, icon: const Icon(Icons.login), label: Text(s.ar ? 'المتابعة بجوجل' : 'Continue with Google')),
      if (error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
    ]);
  }
}
