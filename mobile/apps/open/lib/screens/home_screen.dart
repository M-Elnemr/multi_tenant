import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart' show AppLocale;

import '../core/api.dart';
import '../core/models.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'listing_screen.dart';
import 'search_screen.dart';
import 'shell.dart';
import 'shop_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) => SafeArea(
        bottom: false,
        child: Loader<Map<String, dynamic>>(
          key: _k,
          load: () async => Map<String, dynamic>.from(await OpenApi.get('home') as Map),
          skeleton: const _HomeSkeleton(),
          builder: (context, home, reload) => RefreshIndicator(
            color: kAccent,
            onRefresh: () async => setState(() => _k = UniqueKey()),
            child: _HomeBody(home),
          ),
        ),
      );
}

class _HomeBody extends StatelessWidget {
  const _HomeBody(this.h);
  final Map<String, dynamic> h;

  List<Product> prods(String k) => list(h[k]).map(Product.new).toList();

  void _see(BuildContext c, String title, Map<String, String> fixed) => Navigator.of(c).push(MaterialPageRoute(builder: (_) => ListingScreen(title: title, fixed: fixed)));

  @override
  Widget build(BuildContext context) {
    final deals = prods('deals'), trending = prods('trending'), news = prods('newArrivals'), top = prods('topRated');
    final cats = list(h['categories']);
    final rows = list(h['categoryRows']);
    final shops = list(h['shops']);
    final stats = Map<String, dynamic>.from(h['stats'] as Map? ?? const {});
    final maxOff = deals.fold<int>(0, (a, d) => d.discount > a ? d.discount : a);

    final slides = <_Slide>[
      _Slide(tr('open.hero.eyebrow'), tr('open.hero.title'), tr('open.hero.sub', {'shops': stats['shops'], 'products': stats['products']}), tr('open.hero.cta'), const [kNavy, Color(0xFF2B3BD6)], (trending.isNotEmpty ? trending.first.medium : null), '🛍️', () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ListingScreen(title: tr('open.allProducts'), fixed: const {})))),
      if (deals.isNotEmpty) _Slide(tr('open.deals'), maxOff > 0 ? tr('open.hero.dealsTitle', {'pct': maxOff}) : tr('open.deals'), tr('open.hero.dealsSub'), tr('open.hero.dealsCta'), const [Color(0xFFC2210A), Color(0xFFFF7A2F)], deals.first.medium, '🔥', () => Shell.goTo(context, 2)),
      for (var i = 0; i < rows.length && i < 2; i++)
        _Slide(tr('open.hero.catEyebrow'), localName(rows[i]), tr('open.hero.catSub', {'n': rows[i]['n']}), tr('open.hero.catCta'), i == 0 ? const [Color(0xFF5B21B6), Color(0xFFC026D3)] : const [Color(0xFF0B6B5C), Color(0xFF18A58C)], list(rows[i]['products']).isEmpty ? null : Product(list(rows[i]['products']).first).medium, s(rows[i]['icon']).isEmpty ? '🛍️' : s(rows[i]['icon']), () => _see(context, localName(rows[i]), {'category': s(rows[i]['slug'])})),
    ];

    return CustomScrollView(physics: const AlwaysScrollableScrollPhysics(), slivers: [
      SliverToBoxAdapter(child: _Header()),
      SliverToBoxAdapter(child: _Hero(slides: slides)),
      if (cats.isNotEmpty)
        SliverToBoxAdapter(
          child: SizedBox(
            height: 126,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.fromLTRB(16, 18, 16, 0),
              itemCount: cats.length,
              separatorBuilder: (_, __) => const SizedBox(width: 14),
              itemBuilder: (_, i) => _CatTile(cats[i], i, () => _see(context, localName(cats[i]), {'category': s(cats[i]['slug'])})),
            ),
          ),
        ),
      if (deals.isNotEmpty) ...[
        SliverToBoxAdapter(child: SectionHeader(eyebrow: '🔥 ${tr('open.deals')}', title: tr('open.dealsTitle'), onSeeAll: () => Shell.goTo(context, 2))),
        SliverToBoxAdapter(child: ProductRail(products: deals)),
      ],
      if (trending.isNotEmpty) ...[
        SliverToBoxAdapter(child: SectionHeader(eyebrow: tr('open.trending'), title: tr('open.trendingTitle'), onSeeAll: () => _see(context, tr('open.trendingTitle'), {'sort': 'popular'}))),
        SliverToBoxAdapter(child: ProductRail(products: trending)),
      ],
      if (shops.isNotEmpty) ...[
        SliverToBoxAdapter(child: SectionHeader(eyebrow: tr('open.shopsEyebrow'), title: tr('open.shopsTitle'), onSeeAll: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const ShopsScreen())))),
        SliverToBoxAdapter(
          child: SizedBox(
            height: 168,
            child: ListView.separated(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 16), itemCount: shops.length, separatorBuilder: (_, __) => const SizedBox(width: 12), itemBuilder: (_, i) => ShopCardMini(shops[i])),
          ),
        ),
      ],
      if (news.isNotEmpty) ...[
        SliverToBoxAdapter(child: SectionHeader(eyebrow: tr('open.new'), title: tr('open.newTitle'), onSeeAll: () => _see(context, tr('open.newTitle'), {'sort': 'newest'}))),
        SliverToBoxAdapter(child: ProductRail(products: news)),
      ],
      for (final r in rows) ...[
        SliverToBoxAdapter(child: SectionHeader(eyebrow: s(r['icon']), title: localName(r), onSeeAll: () => _see(context, localName(r), {'category': s(r['slug'])}))),
        SliverToBoxAdapter(child: ProductRail(products: list(r['products']).map(Product.new).toList())),
      ],
      if (top.isNotEmpty) ...[
        SliverToBoxAdapter(child: SectionHeader(eyebrow: tr('open.topRated'), title: tr('open.topRatedTitle'), onSeeAll: () => _see(context, tr('open.topRatedTitle'), {'sort': 'rating'}))),
        SliverToBoxAdapter(child: ProductRail(products: top)),
      ],
      if ((stats['products'] ?? 0) == 0) SliverFillRemaining(hasScrollBody: false, child: EmptyState(emoji: '🏬', title: tr('open.empty.title'), body: tr('open.empty.body'))),
      const SliverToBoxAdapter(child: SizedBox(height: 28)),
    ]);
  }
}

class _Header extends StatelessWidget {
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 14),
        decoration: const BoxDecoration(color: kNavy, borderRadius: BorderRadius.vertical(bottom: Radius.circular(26))),
        child: Column(children: [
          Row(children: [
            ClipRRect(borderRadius: BorderRadius.circular(10), child: Image.asset('assets/icon.png', width: 34, height: 34)),
            const SizedBox(width: 10),
            Text(tr('brand.name'), style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 19)),
            const SizedBox(width: 6),
            Container(padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2), decoration: BoxDecoration(color: kGold, borderRadius: BorderRadius.circular(6)), child: const Text('OPEN', style: TextStyle(color: Color(0xFF3A2A00), fontWeight: FontWeight.w900, fontSize: 10.5))),
            const Spacer(),
            IconButton(onPressed: AppLocale.toggle, icon: const Icon(Icons.translate_rounded, color: Colors.white70), tooltip: AppLocale.isAr ? 'English' : 'العربية'),
          ]),
          const SizedBox(height: 10),
          GestureDetector(
            onTap: () => Navigator.of(context).push(PageRouteBuilder(pageBuilder: (_, __, ___) => const SearchScreen(), transitionsBuilder: (_, a, __, c) => FadeTransition(opacity: a, child: c))),
            child: Container(
              height: 50,
              padding: const EdgeInsetsDirectional.only(start: 16, end: 5),
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(16)),
              child: Row(children: [
                Expanded(child: Text(tr('open.searchPlaceholder'), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: kMute, fontWeight: FontWeight.w600))),
                Container(width: 42, height: 40, decoration: BoxDecoration(gradient: kAccentGradient, borderRadius: BorderRadius.circular(12)), child: const Icon(Icons.search_rounded, color: Colors.white)),
              ]),
            ),
          ),
        ]),
      );
}

class _Slide {
  _Slide(this.eyebrow, this.title, this.sub, this.cta, this.colors, this.image, this.emoji, this.onTap);
  final String eyebrow, title, sub, cta, emoji;
  final List<Color> colors;
  final String? image;
  final VoidCallback onTap;
}

class _Hero extends StatefulWidget {
  const _Hero({required this.slides});
  final List<_Slide> slides;
  @override
  State<_Hero> createState() => _HeroState();
}

class _HeroState extends State<_Hero> {
  final PageController _c = PageController(viewportFraction: .93);
  Timer? _t;
  int _i = 0;
  @override
  void initState() {
    super.initState();
    _t = Timer.periodic(const Duration(seconds: 5), (_) {
      if (!mounted || !_c.hasClients || widget.slides.length < 2) return;
      _c.animateToPage((_i + 1) % widget.slides.length, duration: const Duration(milliseconds: 550), curve: Curves.easeOutCubic);
    });
  }

  @override
  void dispose() {
    _t?.cancel();
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(children: [
        const SizedBox(height: 14),
        SizedBox(
          height: 188,
          child: PageView.builder(
            controller: _c,
            itemCount: widget.slides.length,
            onPageChanged: (i) => setState(() => _i = i),
            itemBuilder: (_, i) {
              final s = widget.slides[i];
              return GestureDetector(
                onTap: s.onTap,
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 5),
                  clipBehavior: Clip.antiAlias,
                  decoration: BoxDecoration(borderRadius: BorderRadius.circular(24), gradient: LinearGradient(colors: s.colors, begin: Alignment.topLeft, end: Alignment.bottomRight), boxShadow: softShadow(.3)),
                  child: Stack(children: [
                    PositionedDirectional(end: -28, top: -40, child: Container(width: 190, height: 190, decoration: BoxDecoration(shape: BoxShape.circle, color: Colors.white.withValues(alpha: .10)))),
                    PositionedDirectional(
                      end: 14,
                      bottom: 14,
                      child: Transform.rotate(
                        angle: .06,
                        child: Container(
                          width: 112,
                          height: 126,
                          clipBehavior: Clip.antiAlias,
                          decoration: BoxDecoration(borderRadius: BorderRadius.circular(22), border: Border.all(color: Colors.white70, width: 3), boxShadow: softShadow(.5)),
                          child: s.image != null ? Img(s.image, icon: s.emoji) : Container(color: Colors.white12, alignment: Alignment.center, child: Text(s.emoji, style: const TextStyle(fontSize: 52))),
                        ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsetsDirectional.fromSTEB(18, 16, 138, 14),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.center, children: [
                        Container(padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3), decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(99)), child: Text(s.eyebrow, style: const TextStyle(color: Colors.white, fontSize: 10.5, fontWeight: FontWeight.w800))),
                        const SizedBox(height: 8),
                        Text(s.title, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white, fontSize: 21, fontWeight: FontWeight.w900, height: 1.2)),
                        const SizedBox(height: 4),
                        Text(s.sub, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white70, fontSize: 12, height: 1.35)),
                        const Spacer(),
                        Container(padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8), decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(99)), child: Text(s.cta, style: const TextStyle(color: kNavy, fontWeight: FontWeight.w900, fontSize: 12.5))),
                      ]),
                    ),
                  ]),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 10),
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          for (var i = 0; i < widget.slides.length; i++) AnimatedContainer(duration: const Duration(milliseconds: 250), margin: const EdgeInsets.symmetric(horizontal: 3), width: i == _i ? 22 : 6, height: 6, decoration: BoxDecoration(color: i == _i ? kAccent : kLine, borderRadius: BorderRadius.circular(9))),
        ]),
      ]);
}

const _tints = [Color(0xFFFFF1EA), Color(0xFFEAF1FF), Color(0xFFEAFAF2), Color(0xFFFFF7E0), Color(0xFFF4ECFF), Color(0xFFE9F8FB), Color(0xFFFFEEF3), Color(0xFFEEF7E6)];

class _CatTile extends StatelessWidget {
  const _CatTile(this.c, this.i, this.onTap);
  final Map<String, dynamic> c;
  final int i;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap,
        child: SizedBox(width: 74, child: Column(children: [
          Container(width: 64, height: 64, decoration: BoxDecoration(color: _tints[i % _tints.length], borderRadius: BorderRadius.circular(22)), alignment: Alignment.center, child: Text(s(c['icon']).isEmpty ? '🛍️' : s(c['icon']), style: const TextStyle(fontSize: 30))),
          const SizedBox(height: 6),
          Text(localName(c), maxLines: 2, textAlign: TextAlign.center, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800, height: 1.25)),
        ])),
      );
}

class _HomeSkeleton extends StatelessWidget {
  const _HomeSkeleton();
  @override
  Widget build(BuildContext context) => ListView(physics: const NeverScrollableScrollPhysics(), padding: const EdgeInsets.all(16), children: [
        const Shimmer(height: 110, radius: 26),
        const SizedBox(height: 16),
        const Shimmer(height: 180, radius: 24),
        const SizedBox(height: 22),
        Row(children: List.generate(4, (_) => const Padding(padding: EdgeInsetsDirectional.only(end: 14), child: Shimmer(height: 64, width: 64, radius: 22)))),
        const SizedBox(height: 22),
        SizedBox(height: 250, child: Row(children: List.generate(2, (_) => const Expanded(child: Padding(padding: EdgeInsets.only(right: 12), child: Shimmer(radius: 18)))))),
      ]);
}
