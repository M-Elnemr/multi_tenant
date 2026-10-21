import 'package:flutter/material.dart';

import '../core/models.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'product_screen.dart';

class WishlistScreen extends StatelessWidget {
  const WishlistScreen({super.key});
  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: AppState.I,
        builder: (context, _) {
          final items = AppState.I.wishlist;
          return Scaffold(
            appBar: AppBar(title: Text(tr('open.wishlist'))),
            body: items.isEmpty
                ? EmptyState(emoji: '🤍', title: tr('open.wishlist.empty'), body: tr('open.wishlist.hint'))
                : GridView.builder(
                    padding: const EdgeInsets.all(16),
                    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 2, mainAxisSpacing: 12, crossAxisSpacing: 12, childAspectRatio: .72),
                    itemCount: items.length,
                    itemBuilder: (_, i) {
                      final w = items[i];
                      return GestureDetector(
                        onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProductScreen(shopSlug: w.shopSlug, slug: w.slug))),
                        child: Container(
                          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18), border: Border.all(color: kLine)),
                          clipBehavior: Clip.antiAlias,
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            AspectRatio(aspectRatio: 1, child: Img(w.image)),
                            Padding(padding: const EdgeInsets.fromLTRB(10, 8, 10, 0), child: Text(w.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13))),
                            const Spacer(),
                            Padding(padding: const EdgeInsets.fromLTRB(10, 0, 4, 6), child: Row(children: [Expanded(child: Text(money(w.priceMinor, w.currency), style: const TextStyle(fontWeight: FontWeight.w900))), IconButton(onPressed: () => AppState.I.removeWish(w.id), icon: const Icon(Icons.favorite_rounded, color: kRed, size: 20))])),
                          ]),
                        ),
                      );
                    },
                  ),
          );
        },
      );
}
