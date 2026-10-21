import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart' show kFont;

import '../core/api.dart';
import '../core/config.dart';
import '../core/models.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../screens/product_screen.dart';

/// A network picture with a soft placeholder. In local development pictures go through [kImageProxy] with the shop's host header.
class Img extends StatelessWidget {
  const Img(this.url, {super.key, this.fit = BoxFit.cover, this.radius = 0, this.icon = '🛍️'});
  final String? url;
  final BoxFit fit;
  final double radius;
  final String icon;

  @override
  Widget build(BuildContext context) {
    Widget ph() => Container(color: kSoft, alignment: Alignment.center, child: Text(icon, style: TextStyle(fontSize: 34, color: Colors.black.withValues(alpha: .25))));
    if (url == null || url!.isEmpty) return ClipRRect(borderRadius: BorderRadius.circular(radius), child: ph());
    var u = url!;
    Map<String, String>? headers;
    if (kImageProxy.isNotEmpty) {
      final o = Uri.parse(u);
      final p = Uri.parse(kImageProxy);
      headers = {'Host': o.hasPort ? '${o.host}:${o.port}' : o.host};
      u = o.replace(scheme: p.scheme, host: p.host, port: p.port).toString();
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: CachedNetworkImage(imageUrl: u, httpHeaders: headers, fit: fit, fadeInDuration: const Duration(milliseconds: 180), placeholder: (_, __) => const Shimmer(), errorWidget: (_, __, ___) => ph()),
    );
  }
}

class Shimmer extends StatefulWidget {
  const Shimmer({super.key, this.height, this.width, this.radius = 0});
  final double? height, width;
  final double radius;
  @override
  State<Shimmer> createState() => _ShimmerState();
}

class _ShimmerState extends State<Shimmer> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1300))..repeat();
  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _c,
        builder: (_, __) => Container(
          height: widget.height,
          width: widget.width,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(widget.radius),
            gradient: LinearGradient(begin: Alignment(-1.5 + _c.value * 3, 0), end: Alignment(-0.5 + _c.value * 3, 0), colors: const [Color(0xFFE9ECF5), Color(0xFFF6F7FB), Color(0xFFE9ECF5)]),
          ),
        ),
      );
}

class Stars extends StatelessWidget {
  const Stars(this.rating, {super.key, this.size = 14});
  final double rating;
  final double size;
  @override
  Widget build(BuildContext context) => Row(mainAxisSize: MainAxisSize.min, children: [for (var i = 1; i <= 5; i++) Icon(i <= rating.round() ? Icons.star_rounded : Icons.star_outline_rounded, size: size, color: const Color(0xFFF5A524))]);
}

class GradientButton extends StatelessWidget {
  const GradientButton({super.key, required this.label, required this.onPressed, this.icon, this.dark = false, this.busy = false, this.compact = false});
  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final bool dark, busy, compact;

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null && !busy;
    return Opacity(
      opacity: enabled || busy ? 1 : .55,
      child: Material(
        color: Colors.transparent,
        child: Ink(
          decoration: BoxDecoration(gradient: dark ? null : kAccentGradient, color: dark ? kNavy : null, borderRadius: BorderRadius.circular(999), boxShadow: enabled ? [BoxShadow(color: (dark ? kNavy : kAccent).withValues(alpha: .35), blurRadius: 20, offset: const Offset(0, 8), spreadRadius: -8)] : null),
          child: InkWell(
            borderRadius: BorderRadius.circular(999),
            onTap: enabled ? onPressed : null,
            child: Container(
              height: compact ? 42 : 52,
              padding: const EdgeInsets.symmetric(horizontal: 22),
              alignment: Alignment.center,
              child: busy
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.4, color: Colors.white))
                  : Row(mainAxisSize: MainAxisSize.min, mainAxisAlignment: MainAxisAlignment.center, children: [
                      if (icon != null) ...[Icon(icon, size: 20, color: Colors.white), const SizedBox(width: 8)],
                      Flexible(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontFamily: kFont, color: Colors.white, fontWeight: FontWeight.w800, fontSize: 15))),
                    ]),
            ),
          ),
        ),
      ),
    );
  }
}

class SectionHeader extends StatelessWidget {
  const SectionHeader({super.key, required this.title, this.eyebrow, this.onSeeAll});
  final String title;
  final String? eyebrow;
  final VoidCallback? onSeeAll;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 22, 16, 10),
        child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (eyebrow != null && eyebrow!.isNotEmpty) Text(eyebrow!, style: const TextStyle(color: kAccent, fontWeight: FontWeight.w800, fontSize: 12, letterSpacing: .4)),
            Text(title, style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w900, color: kInk)),
          ])),
          if (onSeeAll != null)
            GestureDetector(onTap: onSeeAll, child: Row(children: [Text(tr('open.seeAll'), style: const TextStyle(color: kAccent, fontWeight: FontWeight.w800, fontSize: 13)), const SizedBox(width: 2), const Icon(Icons.chevron_right_rounded, color: kAccent, size: 20)])),
        ]),
      );
}

class OChip extends StatelessWidget {
  const OChip({super.key, required this.label, this.active = false, this.onTap, this.onDelete, this.leading});
  final String label;
  final bool active;
  final VoidCallback? onTap, onDelete;
  final Widget? leading;
  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap ?? onDelete,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
          decoration: BoxDecoration(color: active ? kNavy : Colors.white, borderRadius: BorderRadius.circular(999), border: Border.all(color: active ? kNavy : kLine)),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            if (leading != null) ...[leading!, const SizedBox(width: 6)],
            Text(label, style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13, color: active ? Colors.white : kInk)),
            if (onDelete != null) ...[const SizedBox(width: 6), Icon(Icons.close_rounded, size: 15, color: active ? Colors.white : kMute)],
          ]),
        ),
      );
}

class Card2 extends StatelessWidget {
  const Card2({super.key, required this.child, this.padding = const EdgeInsets.all(16), this.margin, this.onTap});
  final Widget child;
  final EdgeInsetsGeometry padding;
  final EdgeInsetsGeometry? margin;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Container(
        margin: margin,
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20), border: Border.all(color: kLine), boxShadow: softShadow(.07)),
        child: Material(color: Colors.transparent, child: InkWell(borderRadius: BorderRadius.circular(20), onTap: onTap, child: Padding(padding: padding, child: child))),
      );
}

void toast(BuildContext context, String msg) => ScaffoldMessenger.of(context)
  ..hideCurrentSnackBar()
  ..showSnackBar(SnackBar(content: Text(msg)));

/// A product tile: picture, discount/badge chips, wishlist heart, shop, name, rating and price (old price struck through).
class ProductCard extends StatelessWidget {
  const ProductCard(this.p, {super.key, this.width});
  final Product p;
  final double? width;

  @override
  Widget build(BuildContext context) {
    final card = GestureDetector(
      onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProductScreen(shopSlug: p.shopSlug, slug: p.slug, preview: p))),
      child: Container(
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18), border: Border.all(color: kLine), boxShadow: softShadow(.06)),
        clipBehavior: Clip.antiAlias,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          AspectRatio(
            aspectRatio: 1,
            child: Stack(fit: StackFit.expand, children: [
              Hero(tag: 'p-${p.id}', child: Opacity(opacity: p.inStock ? 1 : .5, child: Img(p.medium))),
              PositionedDirectional(start: 8, top: 8, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                if (p.discount > 0) _Badge('-${p.discount}%', kRed),
                if (p.badge == 'NEW') _Badge(tr('open.badge.NEW'), kGreen),
                if (p.badge == 'BEST_SELLER') _Badge(tr('open.badge.BEST_SELLER'), kGold, dark: true),
              ])),
              PositionedDirectional(end: 8, top: 8, child: WishHeart(p)),
              if (!p.inStock) Positioned(left: 8, right: 8, bottom: 8, child: Container(padding: const EdgeInsets.symmetric(vertical: 4), decoration: BoxDecoration(color: kNavy.withValues(alpha: .85), borderRadius: BorderRadius.circular(99)), alignment: Alignment.center, child: Text(tr('open.outOfStock'), style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 11)))),
            ]),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(10, 9, 10, 10),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(p.shopName, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: kMute, fontSize: 10.5, fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text(p.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13, height: 1.3, color: kInk)),
                const SizedBox(height: 4),
                if (p.rating > 0) Row(children: [const Icon(Icons.star_rounded, size: 14, color: Color(0xFFF5A524)), const SizedBox(width: 2), Text(p.rating.toStringAsFixed(1), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 11.5)), Text(' (${p.ratingCount})', style: const TextStyle(color: kMute, fontSize: 11))]),
                const Spacer(),
                Wrap(crossAxisAlignment: WrapCrossAlignment.end, spacing: 6, children: [
                  Text(money(p.price, p.currency), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15, color: kInk)),
                  if (p.compareAt != null) Text(money(p.compareAt, p.currency), style: const TextStyle(color: kMute, fontSize: 11, decoration: TextDecoration.lineThrough)),
                ]),
                if (p.freeShipping) Padding(padding: const EdgeInsets.only(top: 4), child: Text(tr('open.freeShipping'), style: const TextStyle(color: Color(0xFF067647), fontSize: 10.5, fontWeight: FontWeight.w800))),
              ]),
            ),
          ),
        ]),
      ),
    );
    return width == null ? card : SizedBox(width: width, child: card);
  }
}

class _Badge extends StatelessWidget {
  const _Badge(this.text, this.color, {this.dark = false});
  final String text;
  final Color color;
  final bool dark;
  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(bottom: 4),
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2.5),
        decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(8)),
        child: Text(text, style: TextStyle(color: dark ? const Color(0xFF3A2A00) : Colors.white, fontSize: 10.5, fontWeight: FontWeight.w900)),
      );
}

class WishHeart extends StatelessWidget {
  const WishHeart(this.p, {super.key, this.size = 34});
  final Product p;
  final double size;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: AppState.I,
        builder: (_, __) {
          final on = AppState.I.wished(p.id);
          return GestureDetector(
            onTap: () => AppState.I.toggleWish(p),
            child: AnimatedScale(
              scale: on ? 1.08 : 1,
              duration: const Duration(milliseconds: 180),
              child: Container(width: size, height: size, decoration: BoxDecoration(color: Colors.white.withValues(alpha: .95), shape: BoxShape.circle, boxShadow: softShadow(.2)), child: Icon(on ? Icons.favorite_rounded : Icons.favorite_border_rounded, size: size * .56, color: on ? kRed : kInk)),
            ),
          );
        },
      );
}

/// A horizontal row of product cards.
class ProductRail extends StatelessWidget {
  const ProductRail({super.key, required this.products});
  final List<Product> products;
  @override
  Widget build(BuildContext context) => SizedBox(
        height: 286,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          itemCount: products.length,
          separatorBuilder: (_, __) => const SizedBox(width: 12),
          itemBuilder: (_, i) => ProductCard(products[i], width: 160),
        ),
      );
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.emoji, required this.title, this.body, this.action});
  final String emoji, title;
  final String? body;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(emoji, style: const TextStyle(fontSize: 56)),
            const SizedBox(height: 12),
            Text(title, textAlign: TextAlign.center, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
            if (body != null) ...[const SizedBox(height: 6), Text(body!, textAlign: TextAlign.center, style: const TextStyle(color: kMute))],
            if (action != null) ...[const SizedBox(height: 18), action!],
          ]),
        ),
      );
}

/// Loads something from the API and shows a skeleton, an error with "try again", or the data. Pull the page down to reload.
class Loader<T> extends StatefulWidget {
  const Loader({super.key, required this.load, required this.builder, this.skeleton});
  final Future<T> Function() load;
  final Widget Function(BuildContext, T, Future<void> Function() reload) builder;
  final Widget? skeleton;
  @override
  State<Loader<T>> createState() => _LoaderState<T>();
}

class _LoaderState<T> extends State<Loader<T>> {
  late Future<T> _f = widget.load();
  Future<void> _reload() async {
    final f = widget.load();
    setState(() => _f = f);
    try {
      await f;
    } catch (_) {/* shown by the builder */}
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<T>(
        future: _f,
        builder: (context, snap) {
          if (snap.hasError) {
            final e = snap.error;
            return EmptyState(emoji: '📡', title: e is ApiException ? e.text : tr('error.generic'), action: GradientButton(label: tr('common.retry'), onPressed: _reload, compact: true));
          }
          if (!snap.hasData) return widget.skeleton ?? const Center(child: CircularProgressIndicator(color: kAccent));
          return widget.builder(context, snap.data as T, _reload);
        },
      );
}

class ProductGridSkeleton extends StatelessWidget {
  const ProductGridSkeleton({super.key});
  @override
  Widget build(BuildContext context) => GridView.count(
        padding: const EdgeInsets.all(16),
        crossAxisCount: 2,
        childAspectRatio: .62,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        physics: const NeverScrollableScrollPhysics(),
        children: List.generate(6, (_) => const Shimmer(radius: 18)),
      );
}
