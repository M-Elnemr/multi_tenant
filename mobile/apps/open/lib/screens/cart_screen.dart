import 'dart:async';

import 'package:flutter/material.dart';

import '../core/api.dart';
import '../core/models.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'checkout_screen.dart';
import 'product_screen.dart';
import 'shell.dart';

Map<String, dynamic> cartRequest({Map<String, dynamic>? address}) => {
      'items': [for (final l in AppState.I.cart) {'variantId': l.variantId, 'quantity': l.quantity}],
      'coupons': AppState.I.coupons,
      if (address != null) 'address': address,
    };

/// The cart, grouped by shop: each shop ships and is paid separately, so each has its own coupon, delivery and subtotal.
class CartScreen extends StatefulWidget {
  const CartScreen({super.key});
  @override
  State<CartScreen> createState() => _CartScreenState();
}

class _CartScreenState extends State<CartScreen> {
  Map<String, dynamic>? _quote;
  String _sig = '';
  Timer? _t;
  final Map<String, TextEditingController> _coupon = {};
  final Map<String, String> _couponErr = {};

  @override
  void initState() {
    super.initState();
    AppState.I.addListener(_maybeQuote);
    _maybeQuote();
  }

  @override
  void dispose() {
    AppState.I.removeListener(_maybeQuote);
    _t?.cancel();
    for (final c in _coupon.values) {
      c.dispose();
    }
    super.dispose();
  }

  void _maybeQuote() {
    final a = AppState.I;
    final sig = '${a.cart.map((l) => '${l.variantId}x${l.quantity}').join(',')}|${a.coupons}';
    if (sig == _sig) return;
    _sig = sig;
    _t?.cancel();
    if (a.cart.isEmpty) {
      setState(() => _quote = null);
      return;
    }
    _t = Timer(const Duration(milliseconds: 200), () async {
      try {
        final q = Map<String, dynamic>.from(await OpenApi.post('cart/quote', cartRequest()) as Map);
        if (!mounted || sig != _sig) return;
        // a coupon the shop rejects is dropped with a message, so the cart never sits on a coupon that is not applied
        final bad = list(q['shops']).where((p) => (p['error'] as Map?)?['code'] == 'COUPON_INVALID').toList();
        if (bad.isNotEmpty) {
          for (final p in bad) {
            _couponErr[s(p['shopId'])] = tr('error.COUPON_INVALID');
            AppState.I.coupons.remove(s(p['shopId']));
          }
          AppState.I.refresh();
          return;
        }
        setState(() => _quote = q);
      } catch (_) {
        if (mounted) setState(() => _quote = null);
      }
    });
  }

  Map<String, dynamic>? _part(String shopId) {
    for (final p in list(_quote?['shops'])) {
      if (p['shopId'] == shopId) return p;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: AppState.I,
        builder: (context, _) {
          final cart = AppState.I.cart;
          if (cart.isEmpty) {
            return Scaffold(appBar: AppBar(title: Text(tr('nav.cart'))), body: EmptyState(emoji: '🛒', title: tr('open.cart.empty'), body: tr('open.cart.emptyHint'), action: GradientButton(label: tr('open.cart.startShopping'), onPressed: () => Shell.goTo(context, 0), compact: true)));
          }
          final shops = <String, List<CartLine>>{};
          for (final l in cart) {
            shops.putIfAbsent(l.shopId, () => []).add(l);
          }
          final currency = cart.first.currency;
          final blocked = _quote?['hasErrors'] == true;
          final subtotal = _quote == null ? cart.fold<int>(0, (a, l) => a + l.unitPriceMinor * l.quantity) : n(_quote!['subtotalMinor']).toInt();
          final discount = _quote == null ? 0 : n(_quote!['discountMinor']).toInt();
          final total = subtotal - discount;
          return Scaffold(
            appBar: AppBar(title: Text('${tr('nav.cart')} (${AppState.I.cartCount})')),
            body: ListView(padding: const EdgeInsets.fromLTRB(16, 4, 16, 24), children: [
              for (final e in shops.entries) _shopCard(e.key, e.value, currency),
              Card2(child: Column(children: [
                Align(alignment: AlignmentDirectional.centerStart, child: Text(tr('open.cart.summary'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
                const SizedBox(height: 12),
                _row(tr('open.cart.items', {'n': AppState.I.cartCount}), money(subtotal, currency)),
                if (discount > 0) _row(tr('open.cart.discount'), '−${money(discount, currency)}', green: true),
                _row(tr('open.cart.shipping'), tr('open.cart.atCheckout')),
                const Divider(height: 24),
                Row(children: [Expanded(child: Text(tr('open.cart.estimatedTotal'), style: const TextStyle(fontWeight: FontWeight.w900))), Text(money(total, currency), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 24))]),
              ])),
              if (blocked) Padding(padding: const EdgeInsets.only(top: 10), child: Text(tr('open.cart.fixIssues'), style: const TextStyle(color: kRed, fontWeight: FontWeight.w800))),
              const SizedBox(height: 14),
              Row(children: [const Icon(Icons.payments_outlined, size: 18, color: kMute), const SizedBox(width: 8), Expanded(child: Text(tr('open.trust.codSub'), style: const TextStyle(color: kMute, fontSize: 12.5)))]),
            ]),
            bottomNavigationBar: Container(
              padding: EdgeInsets.fromLTRB(16, 10, 16, 10 + MediaQuery.of(context).padding.bottom * 0),
              decoration: BoxDecoration(color: Colors.white, border: const Border(top: BorderSide(color: kLine)), boxShadow: softShadow(.12)),
              child: SafeArea(top: false, child: Row(children: [
                Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [Text(tr('open.cart.estimatedTotal'), style: const TextStyle(color: kMute, fontSize: 11.5)), Text(money(total, currency), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 19))]),
                const SizedBox(width: 16),
                Expanded(child: GradientButton(label: tr('open.cart.checkout'), onPressed: blocked ? null : () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const CheckoutScreen())))),
              ])),
            ),
          );
        },
      );

  Widget _row(String a, String b, {bool green = false}) => Padding(padding: const EdgeInsets.symmetric(vertical: 4), child: Row(children: [Expanded(child: Text(a, style: TextStyle(color: green ? const Color(0xFF067647) : kMute))), Text(b, style: TextStyle(fontWeight: FontWeight.w800, color: green ? const Color(0xFF067647) : kInk))]));

  Widget _shopCard(String shopId, List<CartLine> lines, String currency) {
    final part = _part(shopId);
    final code = AppState.I.coupons[shopId];
    final ctrl = _coupon.putIfAbsent(shopId, () => TextEditingController());
    final q = part?['quote'] as Map?;
    final err = part?['error'] as Map?;
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22), border: Border.all(color: kLine), boxShadow: softShadow(.06)),
      clipBehavior: Clip.antiAlias,
      child: Column(children: [
        Container(
          color: kSoft,
          padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
          child: Row(children: [
            const Icon(Icons.storefront_rounded, size: 18),
            const SizedBox(width: 8),
            Expanded(child: Text(lines.first.shopName, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900))),
            TextButton(onPressed: () => AppState.I.removeShop(shopId), child: Text(tr('open.cart.removeShop'), style: const TextStyle(color: kMute, fontSize: 12, fontWeight: FontWeight.w700))),
          ]),
        ),
        if (err != null && err['code'] != 'COUPON_INVALID') Container(width: double.infinity, margin: const EdgeInsets.fromLTRB(14, 12, 14, 0), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFEF3F2), borderRadius: BorderRadius.circular(12)), child: Text(ApiException(400, s(err['code']), s(err['message'])).text, style: const TextStyle(color: Color(0xFFB42318), fontWeight: FontWeight.w800, fontSize: 13))),
        for (final l in lines) _line(l, currency, (q?['lines'] as List?)?.cast<Map>().where((x) => x['variantId'] == l.variantId).map((x) => n(x['unitPriceMinor']).toInt()).firstOrNull),
        const Divider(),
        Padding(
          padding: const EdgeInsets.all(14),
          child: Column(children: [
            if (code != null)
              Container(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8), decoration: BoxDecoration(color: const Color(0xFFE8F8F0), borderRadius: BorderRadius.circular(12)), child: Row(children: [Expanded(child: Text('🏷️ $code${q != null && n(q['discountMinor']) > 0 ? ' · −${money(q['discountMinor'], currency)}' : ''}', style: const TextStyle(color: Color(0xFF067647), fontWeight: FontWeight.w800))), GestureDetector(onTap: () => AppState.I.setCoupon(shopId, null), child: Text(tr('open.cart.removeCoupon'), style: const TextStyle(color: Color(0xFF067647), decoration: TextDecoration.underline, fontSize: 12)))]))
            else
              Row(children: [
                Expanded(child: SizedBox(height: 44, child: TextField(controller: ctrl, textCapitalization: TextCapitalization.characters, decoration: InputDecoration(hintText: tr('open.cart.couponPlaceholder'), contentPadding: const EdgeInsets.symmetric(horizontal: 14))))),
                const SizedBox(width: 8),
                GradientButton(label: tr('open.cart.applyCoupon'), dark: true, compact: true, onPressed: () { final c = ctrl.text.trim(); if (c.isEmpty) return; _couponErr.remove(shopId); AppState.I.setCoupon(shopId, c); ctrl.clear(); }),
              ]),
            if (_couponErr[shopId] != null) Padding(padding: const EdgeInsets.only(top: 6), child: Align(alignment: AlignmentDirectional.centerStart, child: Text(_couponErr[shopId]!, style: const TextStyle(color: kRed, fontWeight: FontWeight.w800, fontSize: 12)))),
            if (q != null) Padding(padding: const EdgeInsets.only(top: 10), child: Row(children: [Expanded(child: Text(tr('open.cart.shopSubtotal'), style: const TextStyle(color: kMute))), Text(money(n(q['subtotalMinor']) - n(q['discountMinor']), currency), style: const TextStyle(fontWeight: FontWeight.w900))])),
          ]),
        ),
      ]),
    );
  }

  Widget _line(CartLine l, String currency, int? livePrice) => Padding(
        padding: const EdgeInsets.all(14),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          GestureDetector(onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProductScreen(shopSlug: l.shopSlug, slug: l.slug))), child: SizedBox(width: 78, height: 78, child: Img(l.image, radius: 14))),
          const SizedBox(width: 12),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(l.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, height: 1.3)),
            if (l.variantLabel.isNotEmpty) Text(l.variantLabel, style: const TextStyle(color: kMute, fontSize: 12)),
            const SizedBox(height: 8),
            Row(children: [
              Container(
                decoration: BoxDecoration(border: Border.all(color: kLine), borderRadius: BorderRadius.circular(99)),
                child: Row(children: [
                  InkWell(onTap: () => l.quantity > 1 ? AppState.I.setQty(l.variantId, l.quantity - 1) : AppState.I.removeLine(l.variantId), borderRadius: BorderRadius.circular(99), child: Padding(padding: const EdgeInsets.all(7), child: Icon(l.quantity > 1 ? Icons.remove_rounded : Icons.delete_outline_rounded, size: 18, color: l.quantity > 1 ? kInk : kRed))),
                  SizedBox(width: 24, child: Text('${l.quantity}', textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w900))),
                  InkWell(onTap: () => AppState.I.setQty(l.variantId, l.quantity + 1), borderRadius: BorderRadius.circular(99), child: const Padding(padding: EdgeInsets.all(7), child: Icon(Icons.add_rounded, size: 18))),
                ]),
              ),
              const Spacer(),
              Text(money((livePrice ?? l.unitPriceMinor) * l.quantity, currency), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
            ]),
          ])),
        ]),
      );
}
