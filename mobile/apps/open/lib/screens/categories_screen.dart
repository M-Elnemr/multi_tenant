import 'package:flutter/material.dart';

import '../core/api.dart';
import '../core/models.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'listing_screen.dart';

/// Every category with its sub-categories (only ones that hold products), as expandable tiles.
class CategoriesScreen extends StatelessWidget {
  const CategoriesScreen({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(tr('open.allCategories'))),
        body: Loader<List<Map<String, dynamic>>>(
          load: () async => list(await OpenApi.get('categories')),
          skeleton: ListView(padding: const EdgeInsets.all(16), children: List.generate(6, (_) => const Padding(padding: EdgeInsets.only(bottom: 12), child: Shimmer(height: 64, radius: 18)))),
          builder: (context, tree, reload) => RefreshIndicator(
            color: kAccent,
            onRefresh: reload,
            child: ListView.separated(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
              itemCount: tree.length,
              separatorBuilder: (_, __) => const SizedBox(height: 10),
              itemBuilder: (_, i) {
                final c = tree[i];
                final kids = list(c['children']);
                void open(Map<String, dynamic> x) => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ListingScreen(title: localName(x), fixed: {'category': s(x['slug'])})));
                return Container(
                  decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20), border: Border.all(color: kLine)),
                  clipBehavior: Clip.antiAlias,
                  child: Theme(
                    data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
                    child: ExpansionTile(
                      tilePadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 2),
                      shape: const Border(),
                      collapsedShape: const Border(),
                      leading: Container(width: 46, height: 46, decoration: BoxDecoration(color: kSoft, borderRadius: BorderRadius.circular(15)), alignment: Alignment.center, child: Text(s(c['icon']).isEmpty ? '🛍️' : s(c['icon']), style: const TextStyle(fontSize: 24))),
                      title: Text(localName(c), style: const TextStyle(fontWeight: FontWeight.w900)),
                      subtitle: Text(tr('open.resultsCount', {'n': c['n']}), style: const TextStyle(fontSize: 12, color: kMute)),
                      children: [
                        ListTile(dense: true, title: Text(tr('open.seeAll'), style: const TextStyle(color: kAccent, fontWeight: FontWeight.w900)), onTap: () => open(c)),
                        for (final k in kids) ListTile(dense: true, title: Text(localName(k), style: const TextStyle(fontWeight: FontWeight.w700)), trailing: Text('${k['n']}', style: const TextStyle(color: kMute, fontSize: 12)), onTap: () => open(k)),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ),
      );
}
