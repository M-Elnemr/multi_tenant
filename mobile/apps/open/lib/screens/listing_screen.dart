import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart' show AppLocale;

import '../core/api.dart';
import '../core/governorates.g.dart';
import '../core/models.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';

/// Results for a search, a category, a shop or the deals tab: product grid with infinite scroll, sort, and the full set of filters.
/// [fixed] seeds the query; keys that make the screen what it is (a shop, the deals tab) stay locked.
class ListingScreen extends StatefulWidget {
  const ListingScreen({super.key, required this.title, required this.fixed, this.header, this.showBack = true});
  final String title;
  final Map<String, String> fixed;
  final Widget? header;
  final bool showBack;
  @override
  State<ListingScreen> createState() => _ListingScreenState();
}

class _ListingScreenState extends State<ListingScreen> {
  static const _filterKeys = ['minPrice', 'maxPrice', 'brand', 'color', 'size', 'minRating', 'inStock', 'freeShipping', 'governorate'];
  final ScrollController _sc = ScrollController();
  late final Map<String, String> _p = {...widget.fixed};
  late final Set<String> _locked = {if (widget.fixed.containsKey('shop')) 'shop', if (widget.fixed.containsKey('onSale')) 'onSale'};
  final List<Product> _items = [];
  Map<String, dynamic>? _facets;
  int _page = 0, _total = 0;
  bool _loading = false, _hasNext = true;
  Object? _error;
  int _gen = 0;

  @override
  void initState() {
    super.initState();
    _sc.addListener(() {
      if (_sc.position.pixels > _sc.position.maxScrollExtent - 600) _more();
    });
    _reset();
  }

  @override
  void dispose() {
    _sc.dispose();
    super.dispose();
  }

  Map<String, String> get _query => {..._p, 'pageSize': '24'};

  Future<void> _reset() async {
    _gen++;
    setState(() {
      _items.clear();
      _page = 0;
      _hasNext = true;
      _error = null;
    });
    _loadFacets();
    await _more();
  }

  Future<void> _loadFacets() async {
    final gen = _gen;
    try {
      final f = Map<String, dynamic>.from(await OpenApi.get('products/facets', query: {'q': _p['q'], 'category': _p['category'], 'shop': _p['shop']}) as Map);
      if (mounted && gen == _gen) setState(() => _facets = f);
    } catch (_) {/* filters just stay unavailable */}
  }

  Future<void> _more() async {
    if (_loading || !_hasNext) return;
    final gen = _gen;
    setState(() => _loading = true);
    try {
      final res = Map<String, dynamic>.from(await OpenApi.get('products', query: {..._query, 'page': '${_page + 1}'}) as Map);
      if (!mounted || gen != _gen) return;
      final meta = Map<String, dynamic>.from(res['meta'] as Map);
      setState(() {
        _items.addAll(list(res['data']).map(Product.new));
        _page++;
        _total = n(meta['total']).toInt();
        _hasNext = meta['hasNext'] == true;
        _error = null;
      });
    } catch (e) {
      if (mounted && gen == _gen) setState(() { _error = e; _hasNext = false; });
    } finally {
      if (mounted && gen == _gen) setState(() => _loading = false);
    }
  }

  int get _activeCount => _filterKeys.where((k) => (_p[k] ?? '').isNotEmpty).length + ((_p['onSale'] == 'true' && !_locked.contains('onSale')) ? 1 : 0) + ((_p['shop'] != null && !_locked.contains('shop')) ? 1 : 0);

  void _apply(Map<String, String> next) {
    for (final k in [..._filterKeys, 'onSale', 'shop', 'category']) {
      if (_locked.contains(k)) continue;
      final v = next[k];
      if (v == null || v.isEmpty) {
        _p.remove(k);
      } else {
        _p[k] = v;
      }
    }
    _reset();
  }

  List<(String, String)> get _sorts => [
        if ((_p['q'] ?? '').isNotEmpty) ('relevance', tr('open.sort.relevance')) else ('recommended', tr('open.sort.recommended')),
        ('popular', tr('open.sort.popular')),
        ('newest', tr('open.sort.newest')),
        ('price_asc', tr('open.sort.price_asc')),
        ('price_desc', tr('open.sort.price_desc')),
        ('discount', tr('open.sort.discount')),
        ('rating', tr('open.sort.rating')),
      ];

  Future<void> _pickSort() async {
    final cur = _p['sort'] ?? _sorts.first.$1;
    final v = await showModalBottomSheet<String>(
      context: context,
      builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const SizedBox(height: 8),
        Container(width: 40, height: 4, decoration: BoxDecoration(color: kLine, borderRadius: BorderRadius.circular(9))),
        Padding(padding: const EdgeInsets.all(16), child: Text(tr('open.sort.label'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
        for (final s0 in _sorts) ListTile(title: Text(s0.$2, style: TextStyle(fontWeight: FontWeight.w700, color: s0.$1 == cur ? kAccent : kInk)), trailing: s0.$1 == cur ? const Icon(Icons.check_rounded, color: kAccent) : null, onTap: () => Navigator.pop(c, s0.$1)),
        const SizedBox(height: 8),
      ])),
    );
    if (v != null && v != cur) {
      _p['sort'] = v;
      _reset();
    }
  }

  Future<void> _openFilters() async {
    final f = _facets;
    if (f == null) return;
    final result = await showModalBottomSheet<Map<String, String>>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => _FilterSheet(facets: f, current: {for (final k in [..._filterKeys, 'onSale', 'shop', 'category']) if (_p[k] != null) k: _p[k]!}, locked: _locked, total: _total),
    );
    if (result != null) _apply(result);
  }

  String _chipLabel(String k) {
    final v = _p[k] ?? '';
    switch (k) {
      case 'minPrice': return '≥ ${n(v) / 100}';
      case 'maxPrice': return '≤ ${n(v) / 100}';
      case 'onSale': return tr('open.f.onSale');
      case 'inStock': return tr('open.f.inStock');
      case 'freeShipping': return tr('open.f.freeShipping');
      case 'minRating': return '$v★ ${tr('open.f.andUp')}';
      case 'governorate':
        final g = kGovernorates.where((x) => x.code == v);
        return g.isEmpty ? v : '${tr('open.f.deliverTo')}: ${AppLocale.isAr ? g.first.ar : g.first.en}';
      case 'shop':
        final sh = list(_facets?['shops']).where((x) => x['shopSlug'] == v);
        return sh.isEmpty ? v : s(sh.first['shopName']);
      default: return v.split(',').join(' · ');
    }
  }

  @override
  Widget build(BuildContext context) {
    final active = [..._filterKeys, 'onSale', 'shop'].where((k) => (_p[k] ?? '').isNotEmpty && !_locked.contains(k)).toList();
    return Scaffold(
      appBar: AppBar(automaticallyImplyLeading: widget.showBack, title: Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis)),
      body: RefreshIndicator(
        color: kAccent,
        onRefresh: _reset,
        child: CustomScrollView(controller: _sc, physics: const AlwaysScrollableScrollPhysics(), slivers: [
          if (widget.header != null) SliverToBoxAdapter(child: widget.header),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
              child: Row(children: [
                Expanded(child: Text(tr('open.resultsCount', {'n': _total}), style: const TextStyle(color: kMute, fontWeight: FontWeight.w700))),
                GestureDetector(onTap: _pickSort, child: _ToolButton(icon: Icons.swap_vert_rounded, label: _sorts.firstWhere((x) => x.$1 == (_p['sort'] ?? _sorts.first.$1), orElse: () => _sorts.first).$2)),
                const SizedBox(width: 8),
                GestureDetector(onTap: _openFilters, child: _ToolButton(icon: Icons.tune_rounded, label: tr('open.f.title'), badge: _activeCount)),
              ]),
            ),
          ),
          if (active.isNotEmpty)
            SliverToBoxAdapter(child: SizedBox(height: 44, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 16), children: [for (final k in active) Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: OChip(label: _chipLabel(k), active: true, onDelete: () { _p.remove(k); _reset(); }))]))),
          if (_items.isEmpty && _loading) const SliverToBoxAdapter(child: SizedBox(height: 520, child: ProductGridSkeleton()))
          else if (_items.isEmpty && _error != null) SliverFillRemaining(hasScrollBody: false, child: EmptyState(emoji: '📡', title: _error is ApiException ? (_error as ApiException).text : tr('error.generic'), action: GradientButton(label: tr('common.retry'), onPressed: _reset, compact: true)))
          else if (_items.isEmpty) SliverFillRemaining(hasScrollBody: false, child: EmptyState(emoji: '🔎', title: tr('open.noResults'), body: tr('open.noResultsHint')))
          else
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
              sliver: SliverGrid(
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 2, mainAxisSpacing: 12, crossAxisSpacing: 12, childAspectRatio: .6),
                delegate: SliverChildBuilderDelegate((_, i) => ProductCard(_items[i]), childCount: _items.length),
              ),
            ),
          if (_loading && _items.isNotEmpty) const SliverToBoxAdapter(child: Padding(padding: EdgeInsets.all(20), child: Center(child: SizedBox(width: 26, height: 26, child: CircularProgressIndicator(strokeWidth: 2.6, color: kAccent))))),
          const SliverToBoxAdapter(child: SizedBox(height: 24)),
        ]),
      ),
    );
  }
}

class _ToolButton extends StatelessWidget {
  const _ToolButton({required this.icon, required this.label, this.badge = 0});
  final IconData icon;
  final String label;
  final int badge;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(99), border: Border.all(color: kLine)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, size: 17, color: kInk),
          const SizedBox(width: 5),
          ConstrainedBox(constraints: const BoxConstraints(maxWidth: 110), child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 12.5))),
          if (badge > 0) ...[const SizedBox(width: 5), Container(padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1), decoration: BoxDecoration(color: kAccent, borderRadius: BorderRadius.circular(99)), child: Text('$badge', style: const TextStyle(color: Colors.white, fontSize: 10.5, fontWeight: FontWeight.w900)))],
        ]),
      );
}

class _FilterSheet extends StatefulWidget {
  const _FilterSheet({required this.facets, required this.current, required this.locked, required this.total});
  final Map<String, dynamic> facets;
  final Map<String, String> current;
  final Set<String> locked;
  final int total;
  @override
  State<_FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<_FilterSheet> {
  late final Map<String, String> d = {...widget.current};
  late final TextEditingController _min = TextEditingController(text: d['minPrice'] == null ? '' : '${n(d['minPrice']) / 100}'.replaceAll(RegExp(r'\.0$'), ''));
  late final TextEditingController _max = TextEditingController(text: d['maxPrice'] == null ? '' : '${n(d['maxPrice']) / 100}'.replaceAll(RegExp(r'\.0$'), ''));

  Set<String> _csv(String k) => (d[k] ?? '').split(',').where((e) => e.isNotEmpty).toSet();
  void _toggleCsv(String k, String v) => setState(() {
        final cur = _csv(k);
        cur.contains(v) ? cur.remove(v) : cur.add(v);
        cur.isEmpty ? d.remove(k) : d[k] = cur.join(',');
      });

  Widget _group(String title, Widget child) => Padding(padding: const EdgeInsets.only(bottom: 20), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(title, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)), const SizedBox(height: 10), child]));

  @override
  Widget build(BuildContext context) {
    final f = widget.facets;
    final cats = list(f['categories']);
    final trail = list(f['trail']);
    final brands = list(f['brands']);
    final colors = list(f['colors']);
    final sizes = list(f['sizes']);
    final shops = list(f['shops']);
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: .92,
      maxChildSize: .96,
      minChildSize: .5,
      builder: (context, sc) => Column(children: [
        const SizedBox(height: 8),
        Container(width: 40, height: 4, decoration: BoxDecoration(color: kLine, borderRadius: BorderRadius.circular(9))),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 10, 12, 6),
          child: Row(children: [
            Expanded(child: Text(tr('open.f.title'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 19))),
            TextButton(onPressed: () => setState(() { for (final k in ['minPrice', 'maxPrice', 'brand', 'color', 'size', 'minRating', 'inStock', 'freeShipping', 'governorate', 'shop']) { if (!widget.locked.contains(k)) d.remove(k); } if (!widget.locked.contains('onSale')) d.remove('onSale'); _min.clear(); _max.clear(); }), child: Text(tr('open.f.clear'), style: const TextStyle(color: kAccent, fontWeight: FontWeight.w800))),
          ]),
        ),
        const Divider(),
        Expanded(
          child: ListView(controller: sc, padding: const EdgeInsets.fromLTRB(20, 16, 20, 16), children: [
            if (!widget.locked.contains('shop') && (cats.isNotEmpty || trail.isNotEmpty))
              _group(tr('open.f.categories'), Wrap(spacing: 8, runSpacing: 8, children: [
                for (final c in trail) OChip(label: localName(c), active: c['slug'] == d['category'], onTap: () => setState(() => d['category'] = s(c['slug']))),
                for (final c in cats) OChip(label: '${s(c['icon'])} ${localName(c)} (${c['n']})'.trim(), active: c['slug'] == d['category'], onTap: () => setState(() => d['category'] = s(c['slug']))),
              ])),
            _group(tr('open.f.deliverTo'), DropdownButtonFormField<String>(
              initialValue: d['governorate'] ?? '',
              isExpanded: true,
              decoration: const InputDecoration(contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 10)),
              items: [DropdownMenuItem(value: '', child: Text(tr('open.f.anywhere'))), for (final g in kGovernorates) DropdownMenuItem(value: g.code, child: Text(AppLocale.isAr ? g.ar : g.en))],
              onChanged: (v) => setState(() => (v == null || v.isEmpty) ? d.remove('governorate') : d['governorate'] = v),
            )),
            _group(tr('open.f.price'), Row(children: [
              Expanded(child: TextField(controller: _min, keyboardType: TextInputType.number, textAlign: TextAlign.center, decoration: InputDecoration(hintText: f['priceMinMinor'] == null ? '0' : '${(n(f['priceMinMinor']) / 100).floor()}'), onChanged: (v) => v.isEmpty ? d.remove('minPrice') : d['minPrice'] = '${((num.tryParse(v) ?? 0) * 100).round()}')),
              const Padding(padding: EdgeInsets.symmetric(horizontal: 10), child: Text('–')),
              Expanded(child: TextField(controller: _max, keyboardType: TextInputType.number, textAlign: TextAlign.center, decoration: InputDecoration(hintText: f['priceMaxMinor'] == null ? '∞' : '${(n(f['priceMaxMinor']) / 100).ceil()}'), onChanged: (v) => v.isEmpty ? d.remove('maxPrice') : d['maxPrice'] = '${((num.tryParse(v) ?? 0) * 100).round()}')),
            ])),
            _group(tr('open.f.rating'), Wrap(spacing: 8, children: [for (final r in [4, 3]) OChip(label: '$r★ ${tr('open.f.andUp')}', active: d['minRating'] == '$r', onTap: () => setState(() => d['minRating'] == '$r' ? d.remove('minRating') : d['minRating'] = '$r'))])),
            _group(tr('open.f.offers'), Column(children: [
              for (final (k, label) in [('onSale', tr('open.f.onSale')), ('freeShipping', tr('open.f.freeShipping')), ('inStock', tr('open.f.inStock'))])
                if (!widget.locked.contains(k)) SwitchListTile(contentPadding: EdgeInsets.zero, activeThumbColor: kAccent, title: Text(label, style: const TextStyle(fontWeight: FontWeight.w700)), value: d[k] == 'true', onChanged: (v) => setState(() => v ? d[k] = 'true' : d.remove(k))),
            ])),
            if (!widget.locked.contains('shop') && shops.length > 1) _group(tr('open.f.shops'), Wrap(spacing: 8, runSpacing: 8, children: [for (final sh in shops) OChip(label: '${s(sh['shopName'])} (${sh['n']})', active: d['shop'] == sh['shopSlug'], onTap: () => setState(() => d['shop'] == sh['shopSlug'] ? d.remove('shop') : d['shop'] = s(sh['shopSlug'])))])),
            if (brands.isNotEmpty) _group(tr('open.f.brands'), Wrap(spacing: 8, runSpacing: 8, children: [for (final b in brands) OChip(label: '${s(b['brand'])} (${b['n']})', active: _csv('brand').contains(s(b['brand'])), onTap: () => _toggleCsv('brand', s(b['brand'])))])),
            if (colors.isNotEmpty) _group(tr('open.f.colors'), Wrap(spacing: 12, runSpacing: 12, children: [
              for (final c in colors)
                GestureDetector(
                  onTap: () => _toggleCsv('color', s(c['code'])),
                  child: Container(width: 40, height: 40, decoration: BoxDecoration(shape: BoxShape.circle, color: hexColor0(s(c['hex'])), border: Border.all(color: _csv('color').contains(s(c['code'])) ? kAccent : kLine, width: _csv('color').contains(s(c['code'])) ? 3 : 1.5)), child: _csv('color').contains(s(c['code'])) ? const Icon(Icons.check_rounded, color: Colors.white, size: 18) : null),
                ),
            ])),
            if (sizes.isNotEmpty) _group(tr('open.f.sizes'), Wrap(spacing: 8, runSpacing: 8, children: [for (final z in sizes) OChip(label: localName(z), active: _csv('size').contains(s(z['code'])), onTap: () => _toggleCsv('size', s(z['code'])))])),
          ]),
        ),
        Container(
          padding: EdgeInsets.fromLTRB(20, 12, 20, 12 + MediaQuery.of(context).padding.bottom),
          decoration: const BoxDecoration(color: Colors.white, border: Border(top: BorderSide(color: kLine))),
          child: GradientButton(label: tr('open.f.show', {'n': widget.total}), onPressed: () => Navigator.pop(context, d)),
        ),
      ]),
    );
  }
}

Color hexColor0(String h) => RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(h) ? Color(int.parse('FF${h.substring(1)}', radix: 16)) : const Color(0xFFCCCCCC);
