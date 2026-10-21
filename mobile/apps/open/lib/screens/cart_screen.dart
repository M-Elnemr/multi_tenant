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
      if (AppState.I.coupon.isNotEmpty) 'couponCode': AppState.I.coupon,
      if (address != null) 'address': address,
    };

/// The cart: products of one shop (delivery and cash collection are per shop), with that shop's coupon.
class CartScreen extends StatefulWidget {
  const CartScreen({super.key});
  @override
  State<CartScreen> createState() => _CartScreenState();
}

class _CartScreenState extends State<CartScreen> {
  Map<String, dynamic>? _quote;
  String _sig = '', _problem = '';
  Timer? _t;
  final _coupon = TextEditingController();

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
    _coupon.dispose();
    super.dispose();
  }

  void _maybeQuote() {
    final a = AppState.I;
    final sig = '${a.cart.map((l) => '${l.variantId}x${l.quantity}').join(',')}|${a.coupon}';
    if (sig == _sig) return;
    _sig = sig;
    _t?.cancel();
    if (a.cart.isEmpty) {
      setState(() { _quote = null; _problem = ''; });
      return;
    }
    _t = Timer(const Duration(milliseconds: 200), () async {
      try {
        final q = Map<String, dynamic>.from(await OpenApi.post('cart/quote', cartRequest()) as Map);
        if (mounted && sig == _sig) setState(() { _quote = q; _problem = ''; });
      } on ApiException catch (e) {
        if (!mounted || sig != _sig) return;
        // a coupon the shop rejects is dropped with a message, so the cart never sits on a coupon that is not applied
        if (e.code == 'COUPON_INVALID' && AppState.I.coupon.isNotEmpty) {
          _problem = e.text;
          AppState.I.setCoupon('');
          return;
        }
        setState(() { _quote = null; _problem = e.text; });
      }
    });
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: AppState.I,
        builder: (context, _) {
          final cart = AppState.I.cart;
          if (cart.isEmpty) {
            return Scaffold(appBar: AppBar(title: Text(tr('nav.cart'))), body: EmptyState(emoji: '🛒', title: tr('open.cart.empty'), body: tr('open.cart.emptyHint'), action: GradientButton(label: tr('open.cart.startShopping'), onPressed: () => Shell.goTo(context, 0), compact: true)));
          }
          final shop = cart.first;
          final currency = shop.currency;
          final subtotal = _quote == null ? cart.fold<int>(0, (a, l) => a + l.unitPriceMinor * l.quantity) : n(_quote!['subtotalMinor']).toInt();
          final discount = _quote == null ? 0 : n(_quote!['discountMinor']).toInt();
          final total = subtotal - discount;
          final blocked = _quote == null && _problem.isNotEmpty;
          final live = {for (final l in list(_quote?['lines'])) s(l['variantId']): n(l['unitPriceMinor']).toInt()};
          final code = AppState.I.coupon;
          return Scaffold(
            appBar: AppBar(title: Text('${tr('nav.cart')} (${AppState.I.cartCount})')),
            body: ListView(padding: const EdgeInsets.fromLTRB(16, 4, 16, 24), children: [
              Container(
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22), border: Border.all(color: kLine), boxShadow: softShadow(.06)),
                clipBehavior: Clip.antiAlias,
                child: Column(children: [
                  Container(
                    color: kSoft,
                    padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
                    child: Row(children: [
                      const Icon(Icons.storefront_rounded, size: 18),
                      const SizedBox(width: 8),
                      Expanded(child: Text(shop.shopName, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900))),
                      TextButton(onPressed: AppState.I.clearCart, child: Text(tr('open.cart.clear'), style: const TextStyle(color: kMute, fontSize: 12, fontWeight: FontWeight.w700))),
                    ]),
                  ),
                  if (_problem.isNotEmpty) Container(width: double.infinity, margin: const EdgeInsets.fromLTRB(14, 12, 14, 0), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFEF3F2), borderRadius: BorderRadius.circular(12)), child: Text(_problem, style: const TextStyle(color: Color(0xFFB42318), fontWeight: FontWeight.w800, fontSize: 13))),
                  for (final l in cart) _line(l, live[l.variantId]),
                  const Divider(),
                  Padding(
                    padding: const EdgeInsets.all(14),
                    child: code.isNotEmpty
                        ? Container(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8), decoration: BoxDecoration(color: const Color(0xFFE8F8F0), borderRadius: BorderRadius.circular(12)), child: Row(children: [Expanded(child: Text('🏷️ $code${discount > 0 ? ' · −${money(discount, currency)}' : ''}', style: const TextStyle(color: Color(0xFF067647), fontWeight: FontWeight.w800))), GestureDetector(onTap: () => AppState.I.setCoupon(''), child: Text(tr('open.cart.removeCoupon'), style: const TextStyle(color: Color(0xFF067647), decoration: TextDecoration.underline, fontSize: 12)))]))
                        : Row(children: [
                            Expanded(child: SizedBox(height: 44, child: TextField(controller: _coupon, textCapitalization: TextCapitalization.characters, decoration: InputDecoration(hintText: tr('open.cart.couponPlaceholder'), contentPadding: const EdgeInsets.symmetric(horizontal: 14))))),
                            const SizedBox(width: 8),
                            GradientButton(label: tr('open.cart.applyCoupon'), dark: true, compact: true, onPressed: () { final c = _coupon.text.trim(); if (c.isEmpty) return; _problem = ''; AppState.I.setCoupon(c); _coupon.clear(); }),
                          ]),
                  ),
                ]),
              ),
              const SizedBox(height: 14),
              Card2(child: Column(children: [
                Align(alignment: AlignmentDirectional.centerStart, child: Text(tr('open.cart.summary'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
                const SizedBox(height: 12),
                _row(tr('open.cart.items', {'n': AppState.I.cartCount}), money(subtotal, currency)),
                if (discount > 0) _row(tr('open.cart.discount'), '−${money(discount, currency)}', green: true),
                _row(tr('open.cart.shipping'), tr('open.cart.atCheckout')),
                const Divider(height: 24),
                Row(children: [Expanded(child: Text(tr('open.cart.estimatedTotal'), style: const TextStyle(fontWeight: FontWeight.w900))), Text(money(total, currency), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 24))]),
              ])),
              const SizedBox(height: 14),
              Row(children: [const Icon(Icons.payments_outlined, size: 18, color: kMute), const SizedBox(width: 8), Expanded(child: Text(tr('open.trust.codSub'), style: const TextStyle(color: kMute, fontSize: 12.5)))]),
              const SizedBox(height: 6),
              Text(tr('open.cart.oneShopNote', {'shop': shop.shopName}), style: const TextStyle(color: kMute, fontSize: 12.5)),
            ]),
            bottomNavigationBar: Container(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
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

  Widget _line(CartLine l, int? livePrice) => Padding(
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
              Text(money((livePrice ?? l.unitPriceMinor) * l.quantity, l.currency), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
            ]),
          ])),
        ]),
      );
}
