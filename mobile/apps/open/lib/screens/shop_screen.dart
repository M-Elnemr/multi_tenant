import 'package:flutter/material.dart';

import '../core/api.dart';
import '../core/models.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'listing_screen.dart';

Color _shopColor(Map<String, dynamic> s0) {
  final c = s(s0['primaryColor']);
  return RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(c) ? Color(int.parse('FF${c.substring(1)}', radix: 16)) : kNavy2;
}

class ShopLogo extends StatelessWidget {
  const ShopLogo(this.shop, {super.key, this.size = 56});
  final Map<String, dynamic> shop;
  final double size;
  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(size * .3), border: Border.all(color: Colors.white, width: 3), boxShadow: softShadow(.3)),
        child: shop['logoUrl'] != null ? Img(s(shop['logoUrl'])) : Center(child: Text(s(shop['name']).isEmpty ? '?' : s(shop['name']).substring(0, 1), style: TextStyle(fontSize: size * .45, fontWeight: FontWeight.w900, color: _shopColor(shop)))),
      );
}

/// A shop tile for the horizontal "shops" row on the home page.
class ShopCardMini extends StatelessWidget {
  const ShopCardMini(this.shop, {super.key});
  final Map<String, dynamic> shop;
  @override
  Widget build(BuildContext context) {
    final color = _shopColor(shop);
    final rating = n(shop['rating']).toDouble();
    return GestureDetector(
      onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ShopScreen(slug: s(shop['slug']), name: s(shop['name'])))),
      child: Container(
        width: 190,
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20), border: Border.all(color: kLine), boxShadow: softShadow(.06)),
        clipBehavior: Clip.antiAlias,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(height: 50, decoration: BoxDecoration(gradient: LinearGradient(colors: [color, Color.lerp(color, kNavy, .5)!]))),
          Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Transform.translate(offset: const Offset(0, -24), child: ShopLogo(shop, size: 50))),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 0),
            child: Transform.translate(offset: const Offset(0, -14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(s(shop['name']), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900)),
              const SizedBox(height: 2),
              Row(children: [
                if (rating > 0) ...[const Icon(Icons.star_rounded, size: 14, color: Color(0xFFF5A524)), Text(' ${rating.toStringAsFixed(1)}  ', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 12))],
                Text('${shop['productCount']} ${tr('open.productsCount')}', style: const TextStyle(color: kMute, fontSize: 12)),
              ]),
            ])),
          ),
        ]),
      ),
    );
  }
}

/// A shop inside the marketplace: banner, rating and every product it sells, with the same filters as everywhere else.
class ShopScreen extends StatelessWidget {
  const ShopScreen({super.key, required this.slug, required this.name});
  final String slug, name;

  @override
  Widget build(BuildContext context) => Loader<Map<String, dynamic>>(
        load: () async => Map<String, dynamic>.from(await OpenApi.get('shops/${Uri.encodeComponent(slug)}') as Map),
        skeleton: Scaffold(appBar: AppBar(title: Text(name)), body: const ProductGridSkeleton()),
        builder: (context, shop, _) {
          final color = _shopColor(shop);
          final rating = n(shop['rating']).toDouble();
          return ListingScreen(
            title: s(shop['name']),
            fixed: {'shop': slug},
            header: Container(
              margin: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22), border: Border.all(color: kLine)),
              clipBehavior: Clip.antiAlias,
              child: Column(children: [
                Container(height: 70, decoration: BoxDecoration(gradient: LinearGradient(colors: [color, Color.lerp(color, kNavy, .5)!]))),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
                  child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                    Transform.translate(offset: const Offset(0, -22), child: ShopLogo(shop, size: 68)),
                    const SizedBox(width: 12),
                    Expanded(child: Padding(padding: const EdgeInsets.only(top: 8), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(s(shop['name']), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
                      const SizedBox(height: 2),
                      Row(children: [
                        if (rating > 0) ...[const Icon(Icons.star_rounded, size: 15, color: Color(0xFFF5A524)), Text(' ${rating.toStringAsFixed(1)} (${shop['ratingCount']})  ', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 12.5))],
                        Text('${shop['productCount']} ${tr('open.productsCount')}', style: const TextStyle(color: kMute, fontSize: 12.5)),
                      ]),
                      if (shop['isOpen'] == false) Text(s(shop['closedMessage']).isEmpty ? tr('open.shopClosed') : s(shop['closedMessage']), style: const TextStyle(color: kRed, fontWeight: FontWeight.w800, fontSize: 12)),
                    ]))),
                  ]),
                ),
                if (s(shop['shortDescription']).isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 14), child: Align(alignment: AlignmentDirectional.centerStart, child: Text(s(shop['shortDescription']), style: const TextStyle(height: 1.5)))),
              ]),
            ),
          );
        },
      );
}

class ShopsScreen extends StatefulWidget {
  const ShopsScreen({super.key});
  @override
  State<ShopsScreen> createState() => _ShopsScreenState();
}

class _ShopsScreenState extends State<ShopsScreen> {
  String _sort = 'popular';
  String _q = '';
  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(tr('open.allShops'))),
        body: Column(children: [
          Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 8), child: TextField(onSubmitted: (v) => setState(() => _q = v.trim()), textInputAction: TextInputAction.search, decoration: InputDecoration(hintText: tr('open.searchShops'), prefixIcon: const Icon(Icons.search_rounded), contentPadding: EdgeInsets.zero))),
          SizedBox(height: 40, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 16), children: [for (final k in ['popular', 'rating', 'name']) Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: OChip(label: tr('open.shopSort.$k'), active: _sort == k, onTap: () => setState(() => _sort = k)))])),
          Expanded(
            child: Loader<Map<String, dynamic>>(
              key: ValueKey('$_sort|$_q'),
              load: () async => Map<String, dynamic>.from(await OpenApi.get('shops', query: {'sort': _sort, 'q': _q, 'pageSize': '60'}) as Map),
              builder: (context, res, _) {
                final shops = list(res['data']);
                if (shops.isEmpty) return EmptyState(emoji: '🏪', title: tr('open.noResults'));
                return GridView.builder(
                  padding: const EdgeInsets.all(16),
                  gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 2, childAspectRatio: 1.08, mainAxisSpacing: 12, crossAxisSpacing: 12),
                  itemCount: shops.length,
                  itemBuilder: (_, i) => ShopCardMini(shops[i]),
                );
              },
            ),
          ),
        ]),
      );
}
