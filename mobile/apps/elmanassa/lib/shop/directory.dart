import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'common.dart';
import 'shop.dart';

/// One client sign-in covers every shop (the token is valid on any store), so every store screen shares this session.
final Session clientSession = Session('client', refreshBase: platformBase());

/// Pick a shop without typing an address: gradient cards with logo, name, specialty and city, a search bar, and filters by specialty and city.
class StoreDirectory extends StatefulWidget {
  const StoreDirectory({super.key});
  @override
  State<StoreDirectory> createState() => _StoreDirectoryState();
}

class _StoreDirectoryState extends State<StoreDirectory> {
  final _api = ApiClient(baseUrl: platformBase(), session: clientSession);
  final _search = TextEditingController();
  final _debounce = Debouncer(350);
  final _scroll = ScrollController();
  List<Map<String, dynamic>> _stores = [];
  List<Map<String, dynamic>> _categories = [];
  List<String> _govs = [];
  String? _category;
  String? _gov;
  int _page = 1;
  bool _loading = true, _more = false, _hasNext = false;
  Object? _error;
  Map<String, dynamic>? _last;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() { if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 300 && _hasNext && !_more && !_loading) _loadMore(); });
    _init();
  }

  @override
  void dispose() { _debounce.dispose(); _search.dispose(); _scroll.dispose(); super.dispose(); }

  Future<void> _init() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString('shop.last');
      if (raw != null) _last = (jsonDecode(raw) as Map).cast<String, dynamic>();
    } catch (_) {}
    try {
      final f = await _api.get('tenant/stores/filters') as Map<String, dynamic>;
      _categories = (f['categories'] as List).cast<Map<String, dynamic>>();
      _govs = (f['governorates'] as List).cast<String>();
    } catch (_) {/* filters are optional: the list still works */}
    await _fetch();
  }

  Future<void> _fetch() async {
    final plain = _search.text.trim().isEmpty && _category == null && _gov == null;
    // the unfiltered first page is remembered: reopening shows it at once while the fresh list loads behind it
    if (plain && _stores.isEmpty) {
      final c = await ListCache.read<Map<String, dynamic>>('shops.first');
      if (c != null && mounted) setState(() { _stores = (c['data'] as List).cast<Map<String, dynamic>>(); _hasNext = (c['meta'] as Map)['hasNext'] == true; _loading = false; });
    }
    setState(() { _loading = _stores.isEmpty; _error = null; _page = 1; });
    try {
      final r = await _api.get('tenant/stores', query: {'q': _search.text.trim(), 'category': _category, 'governorate': _gov, 'page': '1', 'pageSize': '20'}) as Map<String, dynamic>;
      if (plain) ListCache.write('shops.first', r);
      if (!mounted) return;
      setState(() { _stores = (r['data'] as List).cast<Map<String, dynamic>>(); _hasNext = (r['meta'] as Map)['hasNext'] == true; _loading = false; });
    } catch (e) {
      if (mounted) setState(() { if (_stores.isEmpty) _error = e; _loading = false; });
    }
  }

  Future<void> _loadMore() async {
    setState(() => _more = true);
    try {
      final r = await _api.get('tenant/stores', query: {'q': _search.text.trim(), 'category': _category, 'governorate': _gov, 'page': '${_page + 1}', 'pageSize': '20'}) as Map<String, dynamic>;
      if (!mounted) return;
      setState(() { _page++; _stores = [..._stores, ...(r['data'] as List).cast<Map<String, dynamic>>()]; _hasNext = (r['meta'] as Map)['hasNext'] == true; });
    } catch (_) {/* keep what we have */}
    if (mounted) setState(() => _more = false);
  }

  Future<void> _open(Map<String, dynamic> store) async {
    try { (await SharedPreferences.getInstance()).setString('shop.last', jsonEncode(store)); } catch (_) {}
    if (!mounted) return;
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => StoreOpener(store: store)));
  }

  Future<void> _pickCity() async {
    final s = S.of(context);
    final chosen = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      backgroundColor: Colors.white,
      builder: (_) => SafeArea(child: ListView(shrinkWrap: true, children: [
        ListTile(title: Text(s.all), trailing: _gov == null ? const Icon(Icons.check_rounded) : null, onTap: () => Navigator.pop(context, '')),
        for (final g in _govs) ListTile(title: Text(govName(g, s.ar)), trailing: _gov == g ? const Icon(Icons.check_rounded) : null, onTap: () => Navigator.pop(context, g)),
      ])),
    );
    if (chosen == null) return;
    setState(() => _gov = chosen.isEmpty ? null : chosen);
    _fetch();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final scheme = Theme.of(context).colorScheme;
    Widget chip(String label, bool on, VoidCallback tap, {IconData? icon}) => Padding(
          padding: const EdgeInsetsDirectional.only(end: 8),
          child: PressScale(
            onTap: tap,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              decoration: BoxDecoration(color: on ? Colors.white : Colors.white.withValues(alpha: 0.18), borderRadius: BorderRadius.circular(20), border: Border.all(color: Colors.white.withValues(alpha: 0.5))),
              child: Row(mainAxisSize: MainAxisSize.min, children: [if (icon != null) ...[Icon(icon, size: 16, color: on ? scheme.primary : Colors.white), const SizedBox(width: 4)], Text(label, style: TextStyle(fontWeight: FontWeight.w800, fontSize: 13, color: on ? scheme.primary : Colors.white))]),
            ),
          ),
        );
    return Scaffold(
      body: Column(children: [
        GradientHeader(
          radius: 28,
          child: SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(8, 4, 16, 18),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  IconButton(onPressed: () => Navigator.of(context).maybePop(), icon: const Icon(Icons.arrow_back_rounded, color: Colors.white)),
                  Expanded(child: Text(s.chooseShop, style: const TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900))),
                  const LanguageChip(onDark: true),
                ]),
                Padding(
                  padding: const EdgeInsets.fromLTRB(8, 8, 0, 12),
                  child: TextField(
                    controller: _search,
                    onChanged: (_) => _debounce.run(_fetch),
                    textInputAction: TextInputAction.search,
                    decoration: InputDecoration(hintText: s.searchShops, prefixIcon: const Icon(Icons.search_rounded), suffixIcon: _search.text.isEmpty ? null : IconButton(icon: const Icon(Icons.close_rounded), onPressed: () { _search.clear(); _fetch(); })),
                  ),
                ),
                SizedBox(
                  height: 38,
                  child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.only(left: 8), children: [
                    chip(_gov == null ? s.cityLabel : govName(_gov!, s.ar), _gov != null, _pickCity, icon: Icons.location_on_outlined),
                    chip(s.all, _category == null, () { setState(() => _category = null); _fetch(); }),
                    for (final c in _categories) chip(s.ar ? c['nameAr'] as String : c['nameEn'] as String, _category == c['code'], () { setState(() => _category = c['code'] as String); _fetch(); }),
                  ]),
                ),
              ]),
            ),
          ),
        ),
        Expanded(child: _body(s)),
      ]),
    );
  }

  Widget _body(S s) {
    if (_loading) return ListView(padding: const EdgeInsets.all(16), children: const [Skeleton(height: 96), SizedBox(height: 12), Skeleton(height: 96), SizedBox(height: 12), Skeleton(height: 96)]);
    if (_error != null) {
      return Center(child: Column(mainAxisSize: MainAxisSize.min, children: [const Icon(Icons.cloud_off_rounded, size: 46, color: Color(0xFF9AA9BA)), const SizedBox(height: 8), Text(errorText(_error!)), TextButton.icon(onPressed: _fetch, icon: const Icon(Icons.refresh_rounded), label: Text(s.retry))]));
    }
    final showLast = _last != null && _search.text.trim().isEmpty && _category == null && _gov == null;
    if (_stores.isEmpty && !showLast) return Center(child: Padding(padding: const EdgeInsets.all(32), child: Column(mainAxisSize: MainAxisSize.min, children: [const Icon(Icons.search_off_rounded, size: 46, color: Color(0xFF9AA9BA)), const SizedBox(height: 8), Text(s.noShops)])));
    return RefreshIndicator(
      onRefresh: _fetch,
      child: ListView(controller: _scroll, padding: const EdgeInsets.fromLTRB(16, 16, 16, 24), children: [
        if (showLast) ...[
          Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(s.ar ? 'آخر متجر زرته' : 'Last visited', style: const TextStyle(fontWeight: FontWeight.w900))),
          FadeSlideIn(child: ShopCard(store: _last!, ar: s.ar, onTap: () => _open(_last!))),
          if (_stores.isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(0, 10, 0, 8), child: Text(s.ar ? 'كل المتاجر' : 'All shops', style: const TextStyle(fontWeight: FontWeight.w900))),
        ],
        for (var i = 0; i < _stores.length; i++) FadeSlideIn(index: i, child: ShopCard(store: _stores[i], ar: s.ar, onTap: () => _open(_stores[i]))),
        if (_more) const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator())),
      ]),
    );
  }
}

/// One shop in the list: gradient from the shop's own colour, its logo, name, specialty chips and city.
class ShopCard extends StatelessWidget {
  const ShopCard({super.key, required this.store, required this.ar, required this.onTap});
  final Map<String, dynamic> store;
  final bool ar;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final color = hexColor(store['primaryColor']) ?? kBrandBlue;
    final cats = ((store['categories'] as List?) ?? const []).cast<Map<String, dynamic>>().map((c) => ar ? c['nameAr'] as String : c['nameEn'] as String).take(3).toList();
    final cities = ((store['cities'] as List?) ?? const []).cast<Map<String, dynamic>>().map((c) => govName(c['governorateCode'] as String, ar)).toSet().take(2).toList();
    final name = store['name'] as String;
    final logo = store['logoUrl'] as String?;
    final letter = name.trim().isEmpty ? 'S' : String.fromCharCode(name.trim().runes.first).toUpperCase();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: PressScale(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(22),
            gradient: LinearGradient(begin: AlignmentDirectional.topStart, end: AlignmentDirectional.bottomEnd, colors: [color, Color.lerp(color, kBrandGreen, 0.55)!]),
            boxShadow: [BoxShadow(color: color.withValues(alpha: 0.28), blurRadius: 18, offset: const Offset(0, 8))],
          ),
          child: Row(children: [
            Container(
              width: 62,
              height: 62,
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18), boxShadow: const [BoxShadow(color: Color(0x22000000), blurRadius: 8, offset: Offset(0, 3))]),
              clipBehavior: Clip.antiAlias,
              child: logo == null ? Center(child: Text(letter, style: TextStyle(fontSize: 26, fontWeight: FontWeight.w900, color: color))) : Image.network(logo, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Center(child: Text(letter, style: TextStyle(fontSize: 26, fontWeight: FontWeight.w900, color: color)))),
            ),
            const SizedBox(width: 14),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w900)),
              const SizedBox(height: 6),
              if (cats.isNotEmpty) Wrap(spacing: 6, runSpacing: 4, children: [for (final c in cats) _tag(c, Icons.sell_outlined)]),
              if (cities.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Row(children: [const Icon(Icons.location_on_rounded, size: 15, color: Colors.white70), const SizedBox(width: 3), Expanded(child: Text(cities.join(' · '), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white70, fontSize: 12.5, fontWeight: FontWeight.w600)))])),
            ])),
            const Icon(Icons.chevron_right_rounded, color: Colors.white, size: 26),
          ]),
        ),
      ),
    );
  }

  Widget _tag(String t, IconData i) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
        decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(14)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(i, size: 12, color: Colors.white), const SizedBox(width: 4), Text(t, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w700))]),
      );
}

/// Resolves the chosen shop (currency, language, colour) and opens it with the shared client session.
class StoreOpener extends StatelessWidget {
  const StoreOpener({super.key, required this.store});
  final Map<String, dynamic> store;
  @override
  Widget build(BuildContext context) => FutureBuilder<TenantInfo>(
        future: TenantInfo.resolve(store['host'] as String),
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return Scaffold(appBar: AppBar(), body: const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(height: 120), SizedBox(height: 12), Skeleton(), SizedBox(height: 12), Skeleton()])));
          if (snap.hasError) return Scaffold(appBar: AppBar(), body: Center(child: Text(errorText(snap.error!))));
          final t = snap.data!;
          return Theme(data: themeFor(t), child: StoreLoader(tenant: t, api: ApiClient(baseUrl: t.baseUrl, session: clientSession)));
        },
      );
}
