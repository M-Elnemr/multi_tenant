import 'package:flutter/material.dart';
import 'package:intl/intl.dart' hide TextDirection;
import 'package:platform_core/platform_core.dart' show AppLocale;
import 'package:url_launcher/url_launcher.dart';

import '../core/api.dart';
import '../core/models.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'auth_screen.dart';
import 'shell.dart';
import 'shop_screen.dart';

const _steps = ['REQUESTED', 'PREPARING', 'SHIPPED', 'ARRIVED'];

String _when(Object? iso) {
  final d = DateTime.tryParse(s(iso))?.toLocal();
  return d == null ? '' : DateFormat.yMMMd(AppLocale.isAr ? 'ar_EG' : 'en_GB').add_jm().format(d);
}

/// A signed-in shopper's own order.
Future<Map<String, dynamic>> fetchOrder(String id) async => Map<String, dynamic>.from(await OpenApi.get('orders/$id') as Map);

/// Anyone follows an order with the shop, the number and the phone it was placed with (the phone is looked up on this device).
Future<Map<String, dynamic>> trackOrder(String shop, String number, String phone) async => Map<String, dynamic>.from(await OpenApi.post('orders/track', {'shop': shop, 'orderNumber': number, 'phone': phone}) as Map);

/// One order: the shop, its number, a status timeline, the items, totals and the shop's contact.
class OrderView extends StatelessWidget {
  const OrderView({super.key, required this.order, this.compact = false, this.canReview = false});
  final Map<String, dynamic> order;
  final bool compact, canReview;
  @override
  Widget build(BuildContext context) => _OrderCard(order, compact: compact, canReview: canReview);
}

class _OrderCard extends StatelessWidget {
  const _OrderCard(this.o, {required this.compact, required this.canReview});
  final Map<String, dynamic> o;
  final bool compact, canReview;

  @override
  Widget build(BuildContext context) {
    final status = s(o['status']);
    final dead = status == 'CANCELLED' || status == 'RETURNED';
    final idx = _steps.indexOf(status);
    final cur = s(o['currency']);
    final phone = s(o['supportPhone']);
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22), border: Border.all(color: kLine), boxShadow: softShadow(.06)),
      clipBehavior: Clip.antiAlias,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Container(
          color: kSoft,
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          child: Row(children: [
            Expanded(child: GestureDetector(
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ShopScreen(slug: s(o['shopSlug']), name: s(o['shopName'])))),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [const Icon(Icons.storefront_rounded, size: 17), const SizedBox(width: 6), Flexible(child: Text(s(o['shopName']), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900)))]),
                Text('${s(o['orderNumber'])} · ${_when(o['createdAt'])}', textDirection: TextDirection.ltr, style: const TextStyle(color: kMute, fontSize: 11.5)),
              ]),
            )),
            Container(padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 5), decoration: BoxDecoration(color: dead ? const Color(0xFFFEF3F2) : status == 'ARRIVED' ? const Color(0xFFE8F8F0) : const Color(0xFFFFF1EA), borderRadius: BorderRadius.circular(99)), child: Text(tr('open.status.$status'), style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w900, color: dead ? const Color(0xFFB42318) : status == 'ARRIVED' ? const Color(0xFF067647) : const Color(0xFFC4320A)))),
          ]),
        ),
        if (!dead)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 4),
            child: Row(children: [
              for (var i = 0; i < _steps.length; i++)
                Expanded(child: Padding(padding: const EdgeInsetsDirectional.only(end: 4), child: Column(children: [
                  ClipRRect(borderRadius: BorderRadius.circular(9), child: LinearProgressIndicator(value: i <= idx ? 1 : 0, minHeight: 6, backgroundColor: kLine, color: kGreen)),
                  const SizedBox(height: 5),
                  Text(tr('open.status.${_steps[i]}'), maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 10.5, fontWeight: FontWeight.w800, color: i <= idx ? kInk : kMute)),
                ]))),
            ]),
          ),
        for (final i in list(o['items']))
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              SizedBox(width: 60, height: 60, child: Img(i['image'] as String?, radius: 12)),
              const SizedBox(width: 12),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(s(i['name']), maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13.5)),
                if (s(i['variantName']).isNotEmpty) Text(s(i['variantName']), style: const TextStyle(color: kMute, fontSize: 12)),
                Text('${money(i['unitPriceMinor'], cur)} × ${i['quantity']}', style: const TextStyle(color: kMute, fontSize: 12)),
                if (canReview && status == 'ARRIVED') _ReviewLink(productId: s(i['productId'])),
              ])),
              Text(money(i['lineTotalMinor'], cur), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 13.5)),
            ]),
          ),
        const SizedBox(height: 10),
        Container(
          color: kSoft,
          padding: const EdgeInsets.all(16),
          child: Column(children: [
            if (!compact) ...[
              _kv(tr('open.cart.items', {'n': list(o['items']).fold<int>(0, (a, i) => a + n(i['quantity']).toInt())}), money(o['subtotalMinor'], cur)),
              if (n(o['discountMinor']) > 0) _kv('${tr('open.cart.discount')}${s(o['couponCode']).isEmpty ? '' : ' (${o['couponCode']})'}', '−${money(o['discountMinor'], cur)}', green: true),
              _kv(tr('open.cart.shipping'), n(o['shippingMinor']) == 0 ? tr('open.freeShipping') : money(o['shippingMinor'], cur)),
              if (n(o['codFeeMinor']) > 0) _kv(tr('open.checkout.codFee'), money(o['codFeeMinor'], cur)),
            ],
            Row(children: [Expanded(child: Text(tr('open.checkout.total'), style: const TextStyle(fontWeight: FontWeight.w900))), Text(money(o['totalMinor'], cur), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18))]),
            const SizedBox(height: 6),
            Row(children: [const Icon(Icons.payments_outlined, size: 16, color: kMute), const SizedBox(width: 6), Expanded(child: Text('${s(o['paymentStatus']) == 'PAID' ? tr('open.paid') : tr('open.payOnDelivery')}${o['etaMinDays'] != null && !dead && status != 'ARRIVED' ? ' · ${tr('open.eta', {'min': o['etaMinDays'], 'max': o['etaMaxDays'] ?? o['etaMinDays']})}' : ''}', style: const TextStyle(color: kMute, fontSize: 12)))]),
            if (s(o['trackingNumber']).isNotEmpty || s(o['trackingUrl']).isNotEmpty)
              Padding(padding: const EdgeInsets.only(top: 8), child: Row(children: [const Icon(Icons.local_shipping_outlined, size: 16, color: kMute), const SizedBox(width: 6), Expanded(child: Text('${s(o['courierName']).isEmpty ? tr('open.courier') : s(o['courierName'])}: ${s(o['trackingNumber'])}', textDirection: TextDirection.ltr, style: const TextStyle(fontSize: 12.5))), if (s(o['trackingUrl']).isNotEmpty) GestureDetector(onTap: () => launchUrl(Uri.parse(s(o['trackingUrl'])), mode: LaunchMode.externalApplication), child: Text(tr('open.trackShipment'), style: const TextStyle(color: kAccent, fontWeight: FontWeight.w900, fontSize: 12.5)))])),
            if (phone.isNotEmpty)
              Padding(padding: const EdgeInsets.only(top: 10), child: Row(children: [
                OChip(label: phone.replaceFirst('+20', '0'), leading: const Icon(Icons.call_outlined, size: 16), onTap: () => launchUrl(Uri.parse('tel:$phone'))),
                const SizedBox(width: 8),
                OChip(label: 'WhatsApp', leading: const Icon(Icons.chat_outlined, size: 16), onTap: () => launchUrl(Uri.parse('https://wa.me/${phone.replaceAll(RegExp(r'\D'), '')}'), mode: LaunchMode.externalApplication)),
              ])),
          ]),
        ),
      ]),
    );
  }

  Widget _kv(String k, String v, {bool green = false}) => Padding(padding: const EdgeInsets.only(bottom: 5), child: Row(children: [Expanded(child: Text(k, style: TextStyle(color: green ? const Color(0xFF067647) : kMute, fontSize: 13))), Text(v, style: TextStyle(fontWeight: FontWeight.w800, fontSize: 13, color: green ? const Color(0xFF067647) : kInk))]));
}

class _ReviewLink extends StatelessWidget {
  const _ReviewLink({required this.productId});
  final String productId;
  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: () async {
          int rating = 5;
          final text = TextEditingController();
          final ok = await showModalBottomSheet<bool>(
            context: context,
            isScrollControlled: true,
            builder: (c) => StatefulBuilder(builder: (c, set) => Padding(
              padding: EdgeInsets.fromLTRB(20, 20, 20, 20 + MediaQuery.of(c).viewInsets.bottom),
              child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(tr('open.review.rate'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
                const SizedBox(height: 10),
                Row(children: [for (var i = 1; i <= 5; i++) GestureDetector(onTap: () => set(() => rating = i), child: Padding(padding: const EdgeInsets.only(right: 4), child: Icon(i <= rating ? Icons.star_rounded : Icons.star_outline_rounded, size: 38, color: const Color(0xFFF5A524))))]),
                const SizedBox(height: 10),
                TextField(controller: text, minLines: 2, maxLines: 4, maxLength: 600, decoration: InputDecoration(hintText: tr('open.review.placeholder'))),
                const SizedBox(height: 10),
                GradientButton(label: tr('open.review.send'), onPressed: () async {
                  try {
                    await OpenApi.post('reviews', {'productId': productId, 'rating': rating, if (text.text.trim().isNotEmpty) 'text': text.text.trim()});
                    if (c.mounted) Navigator.pop(c, true);
                  } on ApiException catch (e) {
                    if (c.mounted) toast(c, e.text);
                  }
                }),
              ]),
            )),
          );
          text.dispose();
          if (ok == true && context.mounted) toast(context, tr('open.review.thanks'));
        },
        child: Padding(padding: const EdgeInsets.only(top: 4), child: Text('★ ${tr('open.review.rate')}', style: const TextStyle(color: kAccent, fontWeight: FontWeight.w900, fontSize: 12))),
      );
}

/// Loads one order by shop + number using the phone saved on this device; asks for the phone when it is not known.
class GuestOrder extends StatefulWidget {
  const GuestOrder({super.key, required this.shop, required this.number, this.compact = false});
  final String shop, number;
  final bool compact;
  @override
  State<GuestOrder> createState() => _GuestOrderState();
}

class _GuestOrderState extends State<GuestOrder> {
  Map<String, dynamic>? _order;
  final _phone = TextEditingController();
  String _error = '';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final known = AppState.I.orders.where((o) => o['shop'] == widget.shop && o['number'] == widget.number);
    if (known.isNotEmpty) _load(s(known.first['phone']));
  }

  @override
  void dispose() {
    _phone.dispose();
    super.dispose();
  }

  Future<void> _load(String phone) async {
    setState(() { _busy = true; _error = ''; });
    try {
      final o = await trackOrder(widget.shop, widget.number, phone);
      AppState.I.rememberOrder(widget.shop, s(o['shopName']), widget.number, phone);
      if (mounted) setState(() => _order = o);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.status == 404 ? tr('open.order.wrongPhone') : e.text);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_order != null) return OrderView(order: _order!, compact: widget.compact, canReview: AppState.I.signedIn);
    final known = AppState.I.orders.any((o) => o['shop'] == widget.shop && o['number'] == widget.number);
    if (_busy || (known && _error.isEmpty)) return const Shimmer(height: 220, radius: 22);
    return Card2(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(tr('open.order.enterPhone'), style: const TextStyle(fontWeight: FontWeight.w900)),
      const SizedBox(height: 10),
      TextField(controller: _phone, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, decoration: const InputDecoration(hintText: '01xxxxxxxxx')),
      if (_error.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_error, style: const TextStyle(color: kRed, fontWeight: FontWeight.w800))),
      const SizedBox(height: 12),
      GradientButton(label: tr('open.done.track'), compact: true, onPressed: () { if (_phone.text.trim().isNotEmpty) _load(_phone.text.trim()); }),
    ]));
  }
}

class OrderDoneScreen extends StatelessWidget {
  const OrderDoneScreen({super.key, required this.shop, required this.number});
  final String shop, number;
  @override
  Widget build(BuildContext context) => Scaffold(
        body: ListView(padding: EdgeInsets.zero, children: [
          Container(
            padding: EdgeInsets.fromLTRB(24, MediaQuery.of(context).padding.top + 40, 24, 36),
            decoration: const BoxDecoration(gradient: LinearGradient(colors: [Color(0xFF12B76A), Color(0xFF0E9F6E)], begin: Alignment.topLeft, end: Alignment.bottomRight), borderRadius: BorderRadius.vertical(bottom: Radius.circular(34))),
            child: Column(children: [
              TweenAnimationBuilder<double>(tween: Tween(begin: 0, end: 1), duration: const Duration(milliseconds: 600), curve: Curves.elasticOut, builder: (_, v, c) => Transform.scale(scale: v, child: c), child: Container(width: 86, height: 86, decoration: const BoxDecoration(color: Colors.white24, shape: BoxShape.circle), child: const Icon(Icons.check_rounded, color: Colors.white, size: 54))),
              const SizedBox(height: 16),
              Text(tr('open.done.title'), style: const TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.w900)),
              const SizedBox(height: 6),
              Text(tr('open.done.sub'), textAlign: TextAlign.center, style: const TextStyle(color: Colors.white70, height: 1.5)),
            ]),
          ),
          Padding(padding: const EdgeInsets.all(16), child: GuestOrder(shop: shop, number: number, compact: true)),
          Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: Row(children: [
            Expanded(child: GradientButton(label: tr('open.done.track'), dark: true, onPressed: () => Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => OrderDetailScreen(shop: shop, number: number))))),
            const SizedBox(width: 10),
            Expanded(child: GradientButton(label: tr('open.cart.startShopping'), onPressed: () { Navigator.of(context).popUntil((r) => r.isFirst); Shell.goTo(context, 0); })),
          ])),
          const SizedBox(height: 32),
        ]),
      );
}

/// An order opened from the list: by id for a signed-in shopper, by shop + number for one placed on this phone.
class OrderDetailScreen extends StatelessWidget {
  const OrderDetailScreen({super.key, this.id, this.shop, this.number});
  final String? id, shop, number;
  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(tr('open.orderDetails'))),
        body: id != null
            ? Loader<Map<String, dynamic>>(
                load: () => fetchOrder(id!),
                skeleton: const Padding(padding: EdgeInsets.all(16), child: Shimmer(height: 260, radius: 22)),
                builder: (context, o, reload) => RefreshIndicator(color: kAccent, onRefresh: reload, child: ListView(padding: const EdgeInsets.fromLTRB(16, 0, 16, 28), children: [OrderView(order: o, canReview: true)])),
              )
            : ListView(padding: const EdgeInsets.fromLTRB(16, 0, 16, 28), children: [GuestOrder(shop: shop!, number: number!)]),
      );
}

/// Orders: the signed-in shopper's orders across shops, or (when signed out) the ones placed on this phone.
class OrdersScreen extends StatelessWidget {
  const OrdersScreen({super.key});
  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: AppState.I,
        builder: (context, _) => Scaffold(
          appBar: AppBar(title: Text(tr('open.myOrders'))),
          body: AppState.I.signedIn ? _signedIn(context) : _guest(context),
        ),
      );

  Widget _signedIn(BuildContext context) => Loader<Map<String, dynamic>>(
        load: () async => Map<String, dynamic>.from(await OpenApi.get('orders', query: {'pageSize': '40'}) as Map),
        skeleton: ListView(padding: const EdgeInsets.all(16), children: List.generate(3, (_) => const Padding(padding: EdgeInsets.only(bottom: 12), child: Shimmer(height: 110, radius: 20)))),
        builder: (context, res, reload) {
          final orders = list(res['data']);
          if (orders.isEmpty) return EmptyState(emoji: '📦', title: tr('open.orders.none'), action: GradientButton(label: tr('open.cart.startShopping'), compact: true, onPressed: () { Navigator.of(context).popUntil((r) => r.isFirst); Shell.goTo(context, 0); }));
          return RefreshIndicator(color: kAccent, onRefresh: reload, child: ListView.separated(padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), itemCount: orders.length, separatorBuilder: (_, __) => const SizedBox(height: 12), itemBuilder: (_, i) => _OrderTile(orders[i], () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => OrderDetailScreen(id: s(orders[i]['id'])))))));
        },
      );

  Widget _guest(BuildContext context) => ListView(padding: const EdgeInsets.fromLTRB(16, 0, 16, 24), children: [
        Card2(child: Row(children: [Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(tr('open.orders.signInTitle'), style: const TextStyle(fontWeight: FontWeight.w900)), const SizedBox(height: 2), Text(tr('open.orders.signInHint'), style: const TextStyle(color: kMute, fontSize: 12.5))])), const SizedBox(width: 10), GradientButton(label: tr('open.signIn'), compact: true, onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const AuthScreen())))])),
        if (AppState.I.orders.isNotEmpty) ...[
          Padding(padding: const EdgeInsets.fromLTRB(2, 20, 2, 8), child: Text(tr('open.orders.onThisDevice'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
          for (final o in AppState.I.orders)
            Card2(margin: const EdgeInsets.only(bottom: 10), onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => OrderDetailScreen(shop: s(o['shop']), number: s(o['number'])))), child: Row(children: [Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(s(o['shopName']), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900)), Text('${s(o['number'])} · ${_when(DateTime.fromMillisecondsSinceEpoch(n(o['at']).toInt()).toIso8601String())}', textDirection: TextDirection.ltr, style: const TextStyle(color: kMute, fontSize: 11.5))])), Text(tr('open.done.track'), style: const TextStyle(color: kAccent, fontWeight: FontWeight.w900))])),
        ] else
          Padding(padding: const EdgeInsets.only(top: 40), child: EmptyState(emoji: '📦', title: tr('open.orders.none'))),
      ]);
}

class _OrderTile extends StatelessWidget {
  const _OrderTile(this.o, this.onTap);
  final Map<String, dynamic> o;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final items = list(o['items']);
    return Card2(
      onTap: onTap,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(s(o['shopName']), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900))),
          Container(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3), decoration: BoxDecoration(color: kSoft, borderRadius: BorderRadius.circular(99)), child: Text(tr('open.status.${s(o['status'])}'), style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w900))),
        ]),
        Text('${s(o['orderNumber'])} · ${_when(o['createdAt'])}', textDirection: TextDirection.ltr, style: const TextStyle(color: kMute, fontSize: 11.5)),
        const SizedBox(height: 10),
        Row(children: [
          for (final i in items.take(4)) Padding(padding: const EdgeInsetsDirectional.only(end: 6), child: SizedBox(width: 38, height: 38, child: Img(i['image'] as String?, radius: 9))),
          const Spacer(),
          Text(money(o['totalMinor'], s(o['currency'])), style: const TextStyle(fontWeight: FontWeight.w900)),
        ]),
      ]),
    );
  }
}
