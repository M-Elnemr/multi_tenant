import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart' show AppLocale;

import '../core/api.dart';
import '../core/models.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'shell.dart';
import 'shop_screen.dart';

class ProductScreen extends StatefulWidget {
  const ProductScreen({super.key, required this.shopSlug, required this.slug, this.preview});
  final String shopSlug, slug;
  final Product? preview;
  @override
  State<ProductScreen> createState() => _ProductScreenState();
}

class _ProductScreenState extends State<ProductScreen> {
  Map<String, dynamic>? _d;
  Object? _err;
  Map<String, String> _choice = {};
  int _qty = 1;
  int _img = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _err = null);
    try {
      final d = Map<String, dynamic>.from(await OpenApi.get('products/${Uri.encodeComponent(widget.shopSlug)}/${Uri.encodeComponent(widget.slug)}') as Map);
      final variants = list(d['variants']);
      final options = list(d['options']);
      // start on the first variant that is in stock, so the shopper never lands on "sold out" when something is available
      final first = variants.firstWhere((v) => n(v['available']) > 0, orElse: () => variants.isEmpty ? {} : variants.first);
      final parts = {for (final x in s(first['comboKey']).split('|').where((e) => e.contains('='))) x.split('=')[0]: x.split('=')[1]};
      if (!mounted) return;
      setState(() {
        _d = d;
        _choice = {for (final o in options) s(o['name']): parts[s(o['name'])] ?? s((o['values'] as List).first)};
      });
    } catch (e) {
      if (mounted) setState(() => _err = e);
    }
  }

  List<Map<String, dynamic>> get _variants => list(_d?['variants']);
  List<Map<String, dynamic>> get _options => list(_d?['options']);
  String get _combo => _options.map((o) => '${o['name']}=${_choice[s(o['name'])]}').join('|');
  Map<String, dynamic>? get _variant {
    for (final v in _variants) {
      if (s(v['comboKey']) == _combo) return v;
    }
    return null;
  }

  bool _soldOut(Map<String, dynamic> o, String v) {
    final key = _options.map((x) => '${x['name']}=${x['name'] == o['name'] ? v : _choice[s(x['name'])]}').join('|');
    final m = _variants.where((x) => s(x['comboKey']) == key);
    return m.isEmpty || n(m.first['available']) <= 0;
  }

  String _valLabel(Map<String, dynamic> o, String v) {
    for (final m in list(o['valueMeta'])) {
      if (m['value'] == v) return AppLocale.isAr ? s(m['nameAr']) : s(m['nameEn']);
    }
    return v;
  }

  String get _label => _options.map((o) => _valLabel(o, _choice[s(o['name'])] ?? '')).join(' / ');

  CartLine? _line() {
    final d = _d, v = _variant;
    if (d == null || v == null) return null;
    final images = list(d['images']);
    return CartLine(variantId: s(v['id']), productId: s(d['id']), shopId: s(d['shopId']), shopSlug: s(Map.from(d['shop'] as Map)['slug']), shopName: s(Map.from(d['shop'] as Map)['name']), slug: s(d['slug']), name: s(d['name']), variantLabel: _label, unitPriceMinor: n(v['priceMinor']).toInt(), currency: s(d['currency']), quantity: _qty, image: images.isEmpty ? null : s(images.first['thumb']));
  }

  void _add({bool buy = false}) {
    final l = _line();
    if (l == null) return;
    AppState.I.addToCart(l);
    if (buy) {
      Navigator.of(context).popUntil((r) => r.isFirst);
      Shell.goTo(context, 3);
    } else {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(tr('open.addedToCart')), action: SnackBarAction(label: tr('open.viewCart'), textColor: kGold, onPressed: () { Navigator.of(context).popUntil((r) => r.isFirst); Shell.goTo(context, 3); })));
    }
  }

  @override
  Widget build(BuildContext context) {
    final d = _d;
    final pv = widget.preview;
    if (d == null) {
      return Scaffold(
        appBar: AppBar(),
        body: _err != null
            ? EmptyState(emoji: '📡', title: _err is ApiException ? (_err as ApiException).text : tr('error.generic'), action: GradientButton(label: tr('common.retry'), onPressed: _load, compact: true))
            : ListView(children: [
                AspectRatio(aspectRatio: 1, child: pv == null ? const Shimmer() : Hero(tag: 'p-${pv.id}', child: Img(pv.medium))),
                Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  if (pv != null) Text(pv.name, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
                  const SizedBox(height: 14),
                  const Shimmer(height: 26, width: 140, radius: 10),
                  const SizedBox(height: 14),
                  const Shimmer(height: 120, radius: 16),
                ])),
              ]),
      );
    }
    final shop = Map<String, dynamic>.from(d['shop'] as Map);
    final images = list(d['images']);
    final v = _variant;
    final avail = v == null ? 0 : n(v['available']).toInt();
    final open = shop['isOpen'] != false;
    final canBuy = v != null && avail > 0 && open;
    final compare = v != null && v['compareAtPriceMinor'] != null && n(v['compareAtPriceMinor']) > n(v['priceMinor']) ? n(v['compareAtPriceMinor']).toInt() : null;
    final off = compare != null && v != null ? ((compare - n(v['priceMinor'])) * 100 / compare).round() : 0;
    final cur = s(d['currency']);
    final reviews = Map<String, dynamic>.from(d['reviews'] as Map? ?? const {});
    final rating = n(reviews['average']).toDouble();
    final delivery = Map<String, dynamic>.from(d['delivery'] as Map? ?? const {});
    final zones = list(delivery['zones']);
    final cod = list(delivery['paymentMethods']).any((m) => m['method'] == 'CASH_ON_DELIVERY');
    final fees = [...zones.map((z) => n(z['feeMinor']).toInt()), ...list(delivery['shippingMethods']).where((m) => m['type'] != 'PICKUP' && m['type'] != 'ZONES').map((m) => n(m['feeMinor']).toInt())];
    final cheapest = fees.isEmpty ? null : fees.reduce((a, b) => a < b ? a : b);
    final pAsProduct = Product({'id': d['id'], 'shopId': d['shopId'], 'name': d['name'], 'slug': d['slug'], 'currency': cur, 'minPriceMinor': v == null ? null : n(v['priceMinor']), 'image': images.isEmpty ? null : {'thumb': images.first['thumb'], 'medium': images.first['medium']}, 'shop': shop});

    return Scaffold(
      body: CustomScrollView(slivers: [
        SliverAppBar(
          pinned: true,
          backgroundColor: Colors.white,
          expandedHeight: MediaQuery.of(context).size.width,
          leading: Padding(padding: const EdgeInsets.all(6), child: CircleAvatar(backgroundColor: Colors.white.withValues(alpha: .92), child: IconButton(onPressed: () => Navigator.maybePop(context), icon: const Icon(Icons.arrow_back_ios_new_rounded, size: 18)))),
          actions: [Padding(padding: const EdgeInsets.all(6), child: WishHeart(pAsProduct, size: 40))],
          flexibleSpace: FlexibleSpaceBar(
            background: Stack(fit: StackFit.expand, children: [
              images.isEmpty
                  ? Container(color: kSoft, alignment: Alignment.center, child: const Text('🛍️', style: TextStyle(fontSize: 80)))
                  : PageView.builder(itemCount: images.length, onPageChanged: (i) => setState(() => _img = i), itemBuilder: (_, i) => i == 0 && widget.preview != null ? Hero(tag: 'p-${widget.preview!.id}', child: Img(s(images[i]['medium']), fit: BoxFit.contain)) : Img(s(images[i]['medium']), fit: BoxFit.contain)),
              if (images.length > 1) Positioned(bottom: 12, left: 0, right: 0, child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [for (var i = 0; i < images.length; i++) AnimatedContainer(duration: const Duration(milliseconds: 200), margin: const EdgeInsets.symmetric(horizontal: 3), width: i == _img ? 20 : 6, height: 6, decoration: BoxDecoration(color: i == _img ? kAccent : Colors.black26, borderRadius: BorderRadius.circular(9)))])),
            ]),
          ),
        ),
        SliverToBoxAdapter(
          child: Container(
            decoration: const BoxDecoration(color: kBg, borderRadius: BorderRadius.vertical(top: Radius.circular(0))),
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (s(d['brand']).isNotEmpty) Text(s(d['brand']).toUpperCase(), style: const TextStyle(color: kAccent, fontWeight: FontWeight.w900, fontSize: 12, letterSpacing: .6)),
              const SizedBox(height: 2),
              Text(s(d['name']), style: const TextStyle(fontSize: 23, fontWeight: FontWeight.w900, height: 1.25)),
              const SizedBox(height: 8),
              Wrap(crossAxisAlignment: WrapCrossAlignment.center, spacing: 14, runSpacing: 4, children: [
                if (n(reviews['count']) > 0) Row(mainAxisSize: MainAxisSize.min, children: [Stars(rating, size: 16), const SizedBox(width: 4), Text(rating.toStringAsFixed(1), style: const TextStyle(fontWeight: FontWeight.w900)), Text(' (${reviews['count']})', style: const TextStyle(color: kMute))]) else Text(tr('open.noReviewsYet'), style: const TextStyle(color: kMute, fontSize: 13)),
                GestureDetector(onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ShopScreen(slug: s(shop['slug']), name: s(shop['name'])))), child: Text.rich(TextSpan(text: '${tr('open.soldBy')} ', style: const TextStyle(color: kMute, fontSize: 13), children: [TextSpan(text: s(shop['name']), style: const TextStyle(color: kInk, fontWeight: FontWeight.w900))]))),
              ]),
              const SizedBox(height: 14),
              Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                Text(v == null ? '—' : money(n(v['priceMinor']) * _qty, cur), style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w900)),
                const SizedBox(width: 10),
                if (compare != null) Padding(padding: const EdgeInsets.only(bottom: 4), child: Text(money(compare * _qty, cur), style: const TextStyle(color: kMute, fontSize: 16, decoration: TextDecoration.lineThrough))),
                if (off > 0) Container(margin: const EdgeInsetsDirectional.only(start: 10, bottom: 4), padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3), decoration: BoxDecoration(color: kRed, borderRadius: BorderRadius.circular(8)), child: Text('-$off%', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 12))),
              ]),
              for (final o in _options) ...[
                const SizedBox(height: 18),
                Text.rich(TextSpan(text: '${AppLocale.isAr ? s(o['labelAr']) : s(o['labelEn'])}: ', style: const TextStyle(fontWeight: FontWeight.w900), children: [TextSpan(text: _valLabel(o, _choice[s(o['name'])] ?? ''), style: const TextStyle(fontWeight: FontWeight.w600, color: kMute))])),
                const SizedBox(height: 10),
                Wrap(spacing: 10, runSpacing: 10, children: [
                  for (final val in (o['values'] as List).map((e) => '$e'))
                    Builder(builder: (_) {
                      final meta = list(o['valueMeta']).where((m) => m['value'] == val);
                      final hex = meta.isEmpty ? '' : s(meta.first['hex']);
                      final on = _choice[s(o['name'])] == val;
                      final out = _soldOut(o, val);
                      void pick() => setState(() => _choice[s(o['name'])] = val);
                      return hex.isNotEmpty
                          ? GestureDetector(onTap: pick, child: Opacity(opacity: out ? .4 : 1, child: Container(width: 42, height: 42, decoration: BoxDecoration(shape: BoxShape.circle, color: Color(int.parse('FF${hex.replaceAll('#', '')}', radix: 16)), border: Border.all(color: on ? kAccent : kLine, width: on ? 3 : 1.5)), child: on ? const Icon(Icons.check_rounded, color: Colors.white, size: 20) : null)))
                          : GestureDetector(onTap: pick, child: Opacity(opacity: out ? .45 : 1, child: Container(padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 11), decoration: BoxDecoration(color: on ? kNavy : Colors.white, borderRadius: BorderRadius.circular(99), border: Border.all(color: on ? kNavy : kLine)), child: Text(_valLabel(o, val), style: TextStyle(fontWeight: FontWeight.w800, color: on ? Colors.white : kInk, decoration: out ? TextDecoration.lineThrough : null)))));
                    }),
                ]),
              ],
              const SizedBox(height: 16),
              Row(children: [
                Text(!open ? (s(shop['closedMessage']).isEmpty ? tr('open.shopClosed') : s(shop['closedMessage'])) : avail <= 0 ? tr('open.outOfStock') : avail <= 5 ? tr('open.onlyLeft', {'n': avail}) : '✓ ${tr('open.inStock')}', style: TextStyle(fontWeight: FontWeight.w800, color: !open || avail <= 0 ? kRed : (avail <= 5 ? const Color(0xFFB54708) : const Color(0xFF067647)))),
                const Spacer(),
                Container(
                  decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(99), border: Border.all(color: kLine)),
                  child: Row(children: [
                    IconButton(onPressed: _qty > 1 ? () => setState(() => _qty--) : null, icon: const Icon(Icons.remove_rounded)),
                    SizedBox(width: 26, child: Text('$_qty', textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
                    IconButton(onPressed: _qty < (avail == 0 ? 1 : avail.clamp(1, 20)) ? () => setState(() => _qty++) : null, icon: const Icon(Icons.add_rounded)),
                  ]),
                ),
              ]),
              const SizedBox(height: 16),
              Card2(padding: const EdgeInsets.all(14), child: Column(children: [
                _Perk(Icons.local_shipping_outlined, tr('open.deliveredBy', {'shop': s(shop['name'])}), [if (zones.isNotEmpty) tr('open.eta', {'min': zones.map((z) => n(z['etaMinDays']).toInt()).reduce((a, b) => a < b ? a : b), 'max': zones.map((z) => n(z['etaMaxDays']).toInt()).reduce((a, b) => a > b ? a : b)}), if (cheapest != null) (cheapest == 0 ? tr('open.freeShipping') : tr('open.shippingFrom', {'price': money(cheapest, cur)}))].join(' · ')),
                if (cod) _Perk(Icons.payments_outlined, tr('open.trust.cod'), tr('open.trust.codSub')),
                _Perk(Icons.verified_user_outlined, tr('open.trust.track'), tr('open.trust.trackSub')),
              ])),
              const SizedBox(height: 12),
              Card2(
                onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ShopScreen(slug: s(shop['slug']), name: s(shop['name'])))),
                child: Row(children: [
                  ShopLogo(shop, size: 48),
                  const SizedBox(width: 12),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(s(shop['name']), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900)), Text('${shop['productCount']} ${tr('open.productsCount')}${n(shop['rating']) > 0 ? '  ·  ★ ${n(shop['rating']).toStringAsFixed(1)}' : ''}', style: const TextStyle(color: kMute, fontSize: 12.5))])),
                  Text(tr('open.visitShop'), style: const TextStyle(color: kAccent, fontWeight: FontWeight.w900, fontSize: 13)),
                ]),
              ),
              const SizedBox(height: 20),
              Text(tr('open.details'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
              const SizedBox(height: 8),
              if (s(d['description']).isNotEmpty) Text(s(d['description']), style: const TextStyle(height: 1.8, fontSize: 14.5)),
              if (list(d['specs']).isNotEmpty) ...[
                const SizedBox(height: 12),
                Container(
                  clipBehavior: Clip.antiAlias,
                  decoration: BoxDecoration(borderRadius: BorderRadius.circular(16), border: Border.all(color: kLine)),
                  child: Column(children: [for (var i = 0; i < list(d['specs']).length; i++) Container(color: i.isEven ? kSoft : Colors.white, padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Expanded(flex: 2, child: Text(s(list(d['specs'])[i]['k']), style: const TextStyle(fontWeight: FontWeight.w800))), Expanded(flex: 3, child: Text(s(list(d['specs'])[i]['v'])))]))]),
                ),
              ],
              const SizedBox(height: 22),
              Row(children: [Text(tr('open.reviews'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)), const Spacer(), if (n(reviews['count']) > 0) Text.rich(TextSpan(text: rating.toStringAsFixed(1), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900), children: [TextSpan(text: '  ${tr('open.basedOn', {'n': reviews['count']})}', style: const TextStyle(fontSize: 12, color: kMute, fontWeight: FontWeight.w600))]))]),
              const SizedBox(height: 8),
              if (list(reviews['reviews']).isEmpty) Text(tr('open.noReviewsHint'), style: const TextStyle(color: kMute, height: 1.5)),
              for (final r in list(reviews['reviews']))
                Container(
                  margin: const EdgeInsets.only(bottom: 10),
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(16), border: Border.all(color: kLine)),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Row(children: [Expanded(child: Text(s(r['firstName']), style: const TextStyle(fontWeight: FontWeight.w900))), Stars(n(r['rating']).toDouble())]), if (s(r['reviewText']).isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text(s(r['reviewText']), style: const TextStyle(height: 1.6)))]),
                ),
            ]),
          ),
        ),
        if (list(d['moreFromShop']).isNotEmpty) SliverToBoxAdapter(child: SectionHeader(title: tr('open.moreFromShop', {'shop': s(shop['name'])}))),
        if (list(d['moreFromShop']).isNotEmpty) SliverToBoxAdapter(child: ProductRail(products: list(d['moreFromShop']).map(Product.new).toList())),
        if (list(d['similar']).isNotEmpty) SliverToBoxAdapter(child: SectionHeader(title: tr('open.similar'))),
        if (list(d['similar']).isNotEmpty) SliverToBoxAdapter(child: ProductRail(products: list(d['similar']).map(Product.new).toList())),
        const SliverToBoxAdapter(child: SizedBox(height: 110)),
      ]),
      bottomSheet: Container(
        padding: EdgeInsets.fromLTRB(16, 10, 16, 10 + MediaQuery.of(context).padding.bottom),
        decoration: BoxDecoration(color: Colors.white, border: const Border(top: BorderSide(color: kLine)), boxShadow: softShadow(.15)),
        child: Row(children: [
          Expanded(child: GradientButton(label: tr('open.addToCart'), icon: Icons.add_shopping_cart_rounded, dark: true, onPressed: canBuy ? () => _add() : null)),
          const SizedBox(width: 10),
          Expanded(child: GradientButton(label: tr('open.buyNow'), onPressed: canBuy ? () => _add(buy: true) : null)),
        ]),
      ),
    );
  }
}

class _Perk extends StatelessWidget {
  const _Perk(this.icon, this.title, this.sub);
  final IconData icon;
  final String title, sub;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(icon, color: kAccent, size: 22),
          const SizedBox(width: 12),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(title, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 13.5)), if (sub.isNotEmpty) Text(sub, style: const TextStyle(color: kMute, fontSize: 12.5, height: 1.4))])),
        ]),
      );
}
