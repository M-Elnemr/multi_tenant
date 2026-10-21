import 'dart:async';

import 'package:flutter/material.dart';

import '../core/api.dart';
import '../core/models.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'listing_screen.dart';
import 'product_screen.dart';
import 'shop_screen.dart';

/// Full-screen search with suggestions as you type (products, categories and shops across every shop).
class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key, this.initial = ''});
  final String initial;
  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  late final TextEditingController _c = TextEditingController(text: widget.initial);
  Timer? _t;
  Map<String, dynamic>? _res;

  @override
  void dispose() {
    _t?.cancel();
    _c.dispose();
    super.dispose();
  }

  void _changed(String q) {
    _t?.cancel();
    if (q.trim().length < 2) {
      setState(() => _res = null);
      return;
    }
    _t = Timer(const Duration(milliseconds: 220), () async {
      try {
        final r = Map<String, dynamic>.from(await OpenApi.get('suggest', query: {'q': q.trim()}) as Map);
        if (mounted && _c.text.trim() == q.trim()) setState(() => _res = r);
      } catch (_) {/* suggestions are optional */}
    });
  }

  void _submit([String? q]) {
    final text = (q ?? _c.text).trim();
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => ListingScreen(title: text.isEmpty ? tr('open.allProducts') : tr('open.resultsFor', {'q': text}), fixed: {if (text.isNotEmpty) 'q': text})));
  }

  @override
  Widget build(BuildContext context) {
    final prods = list(_res?['products']).map(Product.new).toList();
    final cats = list(_res?['categories']);
    final shops = list(_res?['shops']);
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: Container(
          height: 46,
          margin: const EdgeInsetsDirectional.only(end: 14),
          child: TextField(
            controller: _c,
            autofocus: true,
            textInputAction: TextInputAction.search,
            onChanged: _changed,
            onSubmitted: _submit,
            decoration: InputDecoration(hintText: tr('open.searchPlaceholder'), prefixIcon: const Icon(Icons.search_rounded), suffixIcon: _c.text.isEmpty ? null : IconButton(icon: const Icon(Icons.close_rounded), onPressed: () { _c.clear(); _changed(''); }), contentPadding: EdgeInsets.zero),
          ),
        ),
      ),
      body: _res == null
          ? EmptyState(emoji: '🔎', title: tr('open.searchPlaceholder'))
          : ListView(padding: const EdgeInsets.only(bottom: 24), children: [
              if (cats.isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(16, 4, 16, 8), child: Wrap(spacing: 8, runSpacing: 8, children: [for (final c in cats) OChip(label: '${s(c['icon'])} ${localName(c)}', onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ListingScreen(title: localName(c), fixed: {'category': s(c['slug'])}))))])),
              if (shops.isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 8), child: Wrap(spacing: 8, runSpacing: 8, children: [for (final sh in shops) OChip(label: s(sh['name']), leading: const Icon(Icons.storefront_rounded, size: 16, color: kMute), onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ShopScreen(slug: s(sh['slug']), name: s(sh['name'])))))])),
              for (final p in prods)
                ListTile(
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProductScreen(shopSlug: p.shopSlug, slug: p.slug, preview: p))),
                  leading: SizedBox(width: 52, height: 52, child: Img(p.thumb, radius: 12)),
                  title: Text(p.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 14)),
                  subtitle: Text(p.shopName, style: const TextStyle(fontSize: 12)),
                  trailing: Text(money(p.price, p.currency), style: const TextStyle(fontWeight: FontWeight.w900, color: kAccent)),
                ),
              if (prods.isNotEmpty) Padding(padding: const EdgeInsets.all(16), child: GradientButton(label: tr('open.seeAllResults', {'q': _c.text.trim()}), onPressed: _submit, dark: true, compact: true)),
              if (prods.isEmpty && cats.isEmpty && shops.isEmpty) Padding(padding: const EdgeInsets.only(top: 60), child: EmptyState(emoji: '🔎', title: tr('open.noResults'), body: tr('open.noResultsHint'))),
            ]),
    );
  }
}
