import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'product.dart';

/// Search, category chips (with sub-categories), sorting, an offers filter, and a paged product grid.
class CatalogPage extends StatefulWidget {
  const CatalogPage({super.key, required this.ctx});
  final StoreCtx ctx;
  @override
  State<CatalogPage> createState() => _CatalogPageState();
}

class _CatalogPageState extends State<CatalogPage> {
  final search = TextEditingController();
  final debounce = Debouncer();
  final scroll = ScrollController();
  final items = <Map<String, dynamic>>[];
  String? category; // slug
  String sort = 'newest';
  bool onSale = false, inStock = false;
  int page = 1, total = 0;
  bool loading = true, more = false;
  Object? error;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
    scroll.addListener(() {
      if (scroll.position.pixels > scroll.position.maxScrollExtent - 400 && !loading && !more && items.length < total) _load();
    });
  }

  @override
  void dispose() { search.dispose(); debounce.dispose(); scroll.dispose(); super.dispose(); }

  Future<void> _load({bool reset = false}) async {
    if (reset) { page = 1; items.clear(); }
    setState(() { loading = reset; more = !reset; error = null; });
    try {
      final r = await widget.ctx.api.get('shop/products', query: {
        'q': search.text.trim(), 'category': category, 'sort': sort == 'newest' ? null : sort, 'onSale': onSale ? 'true' : null, 'inStock': inStock ? 'true' : null,
        'page': '$page', 'pageSize': '12',
      }) as Map<String, dynamic>;
      final data = (r['data'] as List).cast<Map<String, dynamic>>();
      if (!mounted) return;
      setState(() { items.addAll(data); total = ((r['meta'] as Map)['total'] as num).toInt(); page++; loading = false; more = false; });
    } catch (e) {
      if (mounted) setState(() { error = e; loading = false; more = false; });
    }
  }

  List<Map<String, dynamic>> _children(String? parentId) => widget.ctx.categories.where((c) => c['parentId'] == parentId && (c['productCount'] as num) > 0).toList();

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = widget.ctx;
    final current = category == null ? null : c.categories.cast<Map<String, dynamic>?>().firstWhere((x) => x!['slug'] == category, orElse: () => null);
    final chips = _children(current?['id'] as String?);
    final parent = current == null ? null : c.categories.cast<Map<String, dynamic>?>().firstWhere((x) => x!['id'] == current['parentId'], orElse: () => null);
    return RefreshIndicator(
      onRefresh: () => _load(reset: true),
      child: CustomScrollView(controller: scroll, slivers: [
        SliverToBoxAdapter(child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
          child: TextField(
            controller: search,
            textInputAction: TextInputAction.search,
            onChanged: (_) => debounce.run(() => _load(reset: true)),
            onSubmitted: (_) => _load(reset: true),
            decoration: InputDecoration(prefixIcon: const Icon(Icons.search), hintText: s.ar ? 'ابحث عن منتج' : 'Search products', filled: true, border: OutlineInputBorder(borderRadius: BorderRadius.circular(30), borderSide: BorderSide.none), isDense: true),
          ),
        )),
        SliverToBoxAdapter(child: SizedBox(height: 46, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6), children: [
          if (current != null) Padding(padding: const EdgeInsets.symmetric(horizontal: 3), child: ActionChip(avatar: const Icon(Icons.arrow_back, size: 16), label: Text(parent?['name'] as String? ?? (s.ar ? 'الكل' : 'All')), onPressed: () { category = parent?['slug'] as String?; _load(reset: true); })),
          if (current != null) Padding(padding: const EdgeInsets.symmetric(horizontal: 3), child: FilterChip(selected: true, label: Text(current['name'] as String), onSelected: (_) {})),
          for (final x in chips) Padding(padding: const EdgeInsets.symmetric(horizontal: 3), child: FilterChip(selected: false, label: Text('${x['name']} (${x['productCount']})'), onSelected: (_) { category = x['slug'] as String; _load(reset: true); })),
        ]))),
        SliverToBoxAdapter(child: Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Row(children: [
          Expanded(child: Wrap(spacing: 6, children: [
            FilterChip(label: Text(s.ar ? '🔥 عروض' : '🔥 Offers'), selected: onSale, onSelected: (v) { onSale = v; _load(reset: true); }),
            FilterChip(label: Text(s.ar ? 'المتوفر' : 'In stock'), selected: inStock, onSelected: (v) { inStock = v; _load(reset: true); }),
          ])),
          DropdownButton<String>(value: sort, underline: const SizedBox.shrink(), items: [
            DropdownMenuItem(value: 'newest', child: Text(s.ar ? 'الأحدث' : 'Newest')),
            DropdownMenuItem(value: 'popular', child: Text(s.ar ? 'الأكثر طلبًا' : 'Popular')),
            DropdownMenuItem(value: 'price_asc', child: Text(s.ar ? 'الأقل سعرًا' : 'Price ↑')),
            DropdownMenuItem(value: 'price_desc', child: Text(s.ar ? 'الأعلى سعرًا' : 'Price ↓')),
            DropdownMenuItem(value: 'discount', child: Text(s.ar ? 'أكبر خصم' : 'Discount')),
          ], onChanged: (v) { sort = v ?? 'newest'; _load(reset: true); }),
        ]))),
        if (loading) const SliverToBoxAdapter(child: Padding(padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator())))
        else if (error != null) SliverToBoxAdapter(child: Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(children: [Text(errorText(error!)), TextButton(onPressed: () => _load(reset: true), child: Text(s.retry))]))))
        else if (items.isEmpty) SliverToBoxAdapter(child: Padding(padding: const EdgeInsets.all(48), child: Center(child: Text(s.ar ? 'لا توجد منتجات مطابقة' : 'No matching products', style: Theme.of(context).textTheme.titleMedium))))
        else SliverPadding(
          padding: const EdgeInsets.all(12),
          sliver: SliverGrid.builder(
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 2, mainAxisSpacing: 12, crossAxisSpacing: 12, childAspectRatio: 0.62),
            itemCount: items.length,
            itemBuilder: (_, i) => ProductTile(ctx: c, p: items[i]),
          ),
        ),
        if (more) const SliverToBoxAdapter(child: Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator()))),
        const SliverToBoxAdapter(child: SizedBox(height: 24)),
      ]),
    );
  }
}

class ProductTile extends StatelessWidget {
  const ProductTile({super.key, required this.ctx, required this.p});
  final StoreCtx ctx;
  final Map<String, dynamic> p;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final img = imageUrl(ctx.base, p);
    final discount = (p['discountPct'] as num?)?.round() ?? 0;
    final inStock = p['inStock'] == true;
    final badge = (p['badge'] as String?) ?? '';
    final cs = Theme.of(context).colorScheme;
    return InkWell(
      borderRadius: BorderRadius.circular(18),
      onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProductPage(ctx: ctx, slug: p['slug'] as String))),
      child: Ink(
        decoration: BoxDecoration(color: cs.surface, borderRadius: BorderRadius.circular(18), border: Border.all(color: cs.outlineVariant)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(child: Stack(fit: StackFit.expand, children: [
            ClipRRect(borderRadius: const BorderRadius.vertical(top: Radius.circular(18)), child: img == null ? Container(color: cs.surfaceContainerHighest, child: const Icon(Icons.shopping_bag_outlined, size: 40)) : Image.network(img, fit: BoxFit.cover, color: inStock ? null : Colors.white54, colorBlendMode: inStock ? null : BlendMode.lighten, errorBuilder: (_, __, ___) => Container(color: cs.surfaceContainerHighest))),
            PositionedDirectional(start: 8, top: 8, child: discount > 0 ? pill(context, '-$discount%', bg: const Color(0xFFE5484D)) : badge.isNotEmpty ? pill(context, {'NEW': s.ar ? 'جديد' : 'New', 'SALE': s.ar ? 'تخفيض' : 'Sale', 'BEST_SELLER': s.ar ? 'الأكثر مبيعًا' : 'Best', 'LIMITED': s.ar ? 'محدود' : 'Limited'}[badge] ?? badge) : const SizedBox.shrink()),
            if (!inStock) Positioned(left: 8, right: 8, bottom: 8, child: Container(padding: const EdgeInsets.all(4), decoration: BoxDecoration(color: Colors.black87, borderRadius: BorderRadius.circular(99)), child: Text(s.ar ? 'نفد المخزون' : 'Out of stock', textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w700)))),
          ])),
          Padding(padding: const EdgeInsets.fromLTRB(10, 8, 10, 10), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(p['name'] as String, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5, height: 1.2)),
            const SizedBox(height: 4),
            Wrap(crossAxisAlignment: WrapCrossAlignment.end, spacing: 6, children: [
              Text(s.money(p['minPriceMinor'] as num?, ctx.tenant.currency), style: TextStyle(fontWeight: FontWeight.w900, color: cs.primary)),
              if (p['compareAtMinor'] != null) Text(s.money(p['compareAtMinor'] as num?, ctx.tenant.currency), style: const TextStyle(decoration: TextDecoration.lineThrough, fontSize: 12, color: Colors.grey)),
            ]),
          ])),
        ]),
      ),
    );
  }
}
