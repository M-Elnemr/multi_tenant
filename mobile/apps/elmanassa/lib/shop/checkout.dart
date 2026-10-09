import 'dart:math';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'account.dart';
import 'common.dart';

class CartPage extends StatelessWidget {
  const CartPage({super.key, required this.ctx, required this.onCheckout});
  final StoreCtx ctx;
  final VoidCallback onCheckout;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final cs = Theme.of(context).colorScheme;
    return ValueListenableBuilder<List<CartLine>>(
      valueListenable: ctx.cart,
      builder: (context, lines, _) {
        if (lines.isEmpty) return Center(child: Column(mainAxisSize: MainAxisSize.min, children: [const Icon(Icons.shopping_cart_outlined, size: 64, color: Colors.grey), const SizedBox(height: 8), Text(s.ar ? 'السلة فارغة' : 'Your cart is empty', style: Theme.of(context).textTheme.titleMedium)]));
        return Column(children: [
          Expanded(child: ListView.separated(
            padding: const EdgeInsets.all(12),
            itemCount: lines.length,
            separatorBuilder: (_, __) => const SizedBox(height: 10),
            itemBuilder: (_, i) {
              final l = lines[i];
              return Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(border: Border.all(color: cs.outlineVariant), borderRadius: BorderRadius.circular(16)),
                child: Row(children: [
                  ClipRRect(borderRadius: BorderRadius.circular(12), child: l.image == null ? Container(width: 72, height: 72, color: cs.surfaceContainerHighest) : Image.network(l.image!, width: 72, height: 72, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(width: 72, height: 72, color: cs.surfaceContainerHighest))),
                  const SizedBox(width: 12),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(l.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800)),
                    if (l.label.isNotEmpty) Text(l.label, style: const TextStyle(color: Colors.grey, fontSize: 12)),
                    const SizedBox(height: 6),
                    Row(children: [
                      InkWell(onTap: () => ctx.cart.setQty(l, l.qty - 1), child: const Icon(Icons.remove_circle_outline)),
                      Padding(padding: const EdgeInsets.symmetric(horizontal: 10), child: Text('${l.qty}', style: const TextStyle(fontWeight: FontWeight.w900))),
                      InkWell(onTap: () => ctx.cart.setQty(l, l.qty + 1), child: const Icon(Icons.add_circle_outline)),
                      const Spacer(),
                      Text(s.money(l.priceMinor * l.qty, ctx.tenant.currency), style: TextStyle(fontWeight: FontWeight.w900, color: cs.primary)),
                    ]),
                  ])),
                ]),
              );
            },
          )),
          SafeArea(top: false, child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(s.total, style: Theme.of(context).textTheme.titleMedium), Text(s.money(ctx.cart.total, ctx.tenant.currency), style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900))]),
            const SizedBox(height: 10),
            FilledButton(onPressed: ctx.isOpen ? onCheckout : null, child: Padding(padding: const EdgeInsets.all(6), child: Text(ctx.isOpen ? s.checkout : (s.ar ? 'المتجر مغلق مؤقتًا' : 'Shop closed')))),
          ]))),
        ]);
      },
    );
  }
}

String _newKey() {
  final r = Random.secure();
  return List.generate(24, (_) => r.nextInt(36).toRadixString(36)).join();
}

/// Guest-friendly checkout: contact, delivery (governorate, area, landmark, second phone), live fee and delivery time, cash on delivery.
class CheckoutPage extends StatefulWidget {
  const CheckoutPage({super.key, required this.ctx});
  final StoreCtx ctx;
  @override
  State<CheckoutPage> createState() => _CheckoutPageState();
}

class _CheckoutPageState extends State<CheckoutPage> {
  final key = _newKey(); // one Idempotency-Key per checkout attempt: a retry can never create a second order
  final name = TextEditingController(), phone = TextEditingController(), phone2 = TextEditingController(), line1 = TextEditingController(), city = TextEditingController(), area = TextEditingController(), landmark = TextEditingController(), coupon = TextEditingController();
  final debounce = Debouncer(450);
  String? shippingId, gov;
  Map<String, dynamic>? quote;
  String? error, quoteError;
  bool busy = false;

  StoreCtx get c => widget.ctx;
  Map<String, dynamic>? get method => c.shippingMethods.cast<Map<String, dynamic>?>().firstWhere((m) => m!['id'] == shippingId, orElse: () => null);
  bool get pickup => method?['type'] == 'PICKUP';
  bool get zones => method?['type'] == 'ZONES';

  @override
  void initState() {
    super.initState();
    shippingId = c.shippingMethods.isEmpty ? null : c.shippingMethods.first['id'] as String;
    _quote();
  }

  @override
  void dispose() { for (final x in [name, phone, phone2, line1, city, area, landmark, coupon]) { x.dispose(); } debounce.dispose(); super.dispose(); }

  Map<String, dynamic> _body() => {
        'items': [for (final l in c.cart.value) {'variantId': l.variantId, 'quantity': l.qty}],
        'shippingMethodId': shippingId,
        'paymentMethod': 'CASH_ON_DELIVERY',
        if (coupon.text.trim().isNotEmpty) 'couponCode': coupon.text.trim(),
        'address': {
          'recipientName': name.text.trim(), 'phone': phone.text.trim(), 'phone2': phone2.text.trim(),
          if (!pickup) ...{'addressLine1': line1.text.trim(), 'city': city.text.trim(), 'governorateCode': gov ?? '', 'area': area.text.trim(), 'landmark': landmark.text.trim()},
        },
      };

  void _quoteSoon() => debounce.run(_quote);

  Future<void> _quote() async {
    if (shippingId == null || c.cart.value.isEmpty) return;
    if (zones && gov == null) { setState(() { quote = null; quoteError = null; }); return; }
    try {
      final q = await c.api.post('shop/cart/quote', _body()) as Map<String, dynamic>;
      if (mounted) setState(() { quote = q; quoteError = null; });
    } catch (e) {
      if (mounted) setState(() { quote = null; quoteError = errorText(e); });
    }
  }

  Future<void> _place() async {
    setState(() { busy = true; error = null; });
    try {
      final r = await c.api.post('shop/checkout', _body(), key) as Map<String, dynamic>;
      c.cart.clear();
      if (!mounted) return;
      Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => OrderDonePage(ctx: c, orderNumber: r['orderNumber'] as String, phone: phone.text.trim())));
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Widget _tf(TextEditingController ctrl, String label, {TextInputType? type, bool quote = false, String? hint}) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: TextField(controller: ctrl, keyboardType: type, onChanged: quote ? (_) => _quoteSoon() : null, decoration: InputDecoration(labelText: label, hintText: hint, border: const OutlineInputBorder())),
      );

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final cs = Theme.of(context).colorScheme;
    final cur = c.tenant.currency;
    final methods = c.shippingMethods;
    final eta = (quote?['etaMinDays'] as num?);
    return Scaffold(
      appBar: AppBar(title: Text(s.checkout)),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text(s.ar ? '١. بياناتك' : '1. Your details', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        const SizedBox(height: 10),
        _tf(name, s.ar ? 'الاسم' : 'Name'),
        _tf(phone, s.ar ? 'رقم الموبايل' : 'Mobile number', type: TextInputType.phone, quote: true),
        _tf(phone2, s.ar ? 'رقم آخر (اختياري)' : 'Second phone (optional)', type: TextInputType.phone),
        const SizedBox(height: 4),
        Text(s.ar ? '٢. التوصيل' : '2. Delivery', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        const SizedBox(height: 10),
        if (methods.length > 1) Padding(padding: const EdgeInsets.only(bottom: 12), child: DropdownButtonFormField<String>(
          initialValue: shippingId, decoration: const InputDecoration(border: OutlineInputBorder()),
          items: [for (final m in methods) DropdownMenuItem(value: m['id'] as String, child: Text(m['name'] as String))],
          onChanged: (v) { setState(() => shippingId = v); _quoteSoon(); },
        )),
        if (!pickup) ...[
          DropdownButtonFormField<String>(
            initialValue: gov, decoration: InputDecoration(labelText: s.ar ? 'المحافظة' : 'Governorate', border: const OutlineInputBorder()),
            items: [for (final g in governorates) DropdownMenuItem(value: g.$1, child: Text(s.ar ? g.$2 : g.$3))],
            onChanged: (v) { setState(() => gov = v); _quoteSoon(); },
          ),
          const SizedBox(height: 12),
          _tf(city, s.city, quote: true),
          _tf(area, s.ar ? 'المنطقة / الحي' : 'Area / district'),
          _tf(line1, s.address1, quote: true, hint: s.ar ? 'الشارع، رقم العمارة، الدور، الشقة' : 'Street, building, floor, apartment'),
          _tf(landmark, s.ar ? 'علامة مميزة' : 'Landmark'),
        ],
        if (eta != null && !pickup) Container(margin: const EdgeInsets.only(bottom: 12), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: Colors.green.shade50, borderRadius: BorderRadius.circular(14)), child: Text(s.ar ? '🚚 التوصيل خلال ${quote!['etaMinDays']}–${quote!['etaMaxDays']} أيام' : '🚚 Delivery in ${quote!['etaMinDays']}–${quote!['etaMaxDays']} days', style: TextStyle(color: Colors.green.shade900, fontWeight: FontWeight.w800))),
        Text(s.ar ? '٣. الدفع' : '3. Payment', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        const SizedBox(height: 10),
        Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(border: Border.all(color: cs.primary, width: 2), borderRadius: BorderRadius.circular(16), color: cs.primary.withValues(alpha: 0.06)), child: Row(children: [const Text('💵', style: TextStyle(fontSize: 26)), const SizedBox(width: 12), Expanded(child: Text(s.cod, style: const TextStyle(fontWeight: FontWeight.w800)))])),
        const SizedBox(height: 12),
        _tf(coupon, s.ar ? 'كود الخصم (اختياري)' : 'Coupon (optional)', quote: true),
        const Divider(),
        if (quote != null) ...[
          _row(s.ar ? 'المجموع الفرعي' : 'Subtotal', s.money(quote!['subtotalMinor'] as num, cur)),
          if (((quote!['discountMinor'] as num?) ?? 0) > 0) _row(s.ar ? 'الخصم' : 'Discount', '−${s.money(quote!['discountMinor'] as num, cur)}', color: Colors.green),
          _row(s.ar ? 'الشحن' : 'Delivery', ((quote!['shippingMinor'] as num?) ?? 0) == 0 ? (s.ar ? 'مجاني' : 'Free') : s.money(quote!['shippingMinor'] as num, cur)),
          if (((quote!['codFeeMinor'] as num?) ?? 0) > 0) _row(s.ar ? 'رسوم الدفع عند الاستلام' : 'COD fee', s.money(quote!['codFeeMinor'] as num, cur)),
          _row(s.total, s.money(quote!['totalMinor'] as num, cur), bold: true),
        ] else _row(s.total, s.money(c.cart.total, cur), bold: true),
        if (quoteError != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(quoteError!, style: TextStyle(color: cs.error, fontWeight: FontWeight.w700))),
        if (error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(error!, style: TextStyle(color: cs.error, fontWeight: FontWeight.w700))),
        const SizedBox(height: 16),
        FilledButton(onPressed: busy || quoteError != null || (zones && gov == null) ? null : _place, child: Padding(padding: const EdgeInsets.all(8), child: Text(busy ? '…' : s.placeOrder))),
        const SizedBox(height: 24),
      ]),
    );
  }

  Widget _row(String l, String v, {bool bold = false, Color? color}) => Padding(padding: const EdgeInsets.symmetric(vertical: 4), child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(l, style: TextStyle(fontWeight: bold ? FontWeight.w900 : null, fontSize: bold ? 17 : null)), Text(v, style: TextStyle(fontWeight: bold ? FontWeight.w900 : FontWeight.w700, fontSize: bold ? 17 : null, color: color))]));
}

class OrderDonePage extends StatelessWidget {
  const OrderDonePage({super.key, required this.ctx, required this.orderNumber, required this.phone});
  final StoreCtx ctx;
  final String orderNumber, phone;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      body: SafeArea(child: Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
        const CircleAvatar(radius: 40, backgroundColor: Color(0xFFD1FAE5), child: Icon(Icons.check, size: 44, color: Color(0xFF059669))),
        const SizedBox(height: 16),
        Text(s.ar ? 'تم استلام طلبك' : 'Order received', style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
        const SizedBox(height: 8),
        SelectableText(orderNumber, style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: Theme.of(context).colorScheme.primary)),
        const SizedBox(height: 8),
        Text(s.ar ? 'قد نتصل بك قريبًا لتأكيد الطلب. الدفع عند الاستلام.' : 'We may call you shortly to confirm. Pay when it arrives.', textAlign: TextAlign.center),
        const SizedBox(height: 20),
        FilledButton(onPressed: () => Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => TrackPage(ctx: ctx, orderNumber: orderNumber, phone: phone))), child: Text(s.ar ? 'تتبع طلبي' : 'Track my order')),
        TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(s.ar ? 'متابعة التسوق' : 'Continue shopping')),
      ])))),
    );
  }
}
