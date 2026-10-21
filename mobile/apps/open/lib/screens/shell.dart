import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart' show AppLocale, kFont;

import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import 'account_screen.dart';
import 'cart_screen.dart';
import 'categories_screen.dart';
import 'home_screen.dart';
import 'listing_screen.dart';

/// The five tabs: home, categories, deals, cart, account. Tab pages stay alive so scroll positions and carts survive switching.
class Shell extends StatefulWidget {
  const Shell({super.key});
  static void goTo(BuildContext context, int tab) => context.findAncestorStateOfType<_ShellState>()?._select(tab);
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _tab = 0;
  void _select(int i) => setState(() => _tab = i);

  @override
  Widget build(BuildContext context) {
    // Rebuild on language change so every label follows.
    return ValueListenableBuilder<Locale>(
      valueListenable: AppLocale.notifier,
      builder: (_, __, ___) => Scaffold(
        body: IndexedStack(index: _tab, children: [
          const HomeScreen(),
          const CategoriesScreen(),
          ListingScreen(key: ValueKey('deals-${AppLocale.isAr}'), title: tr('open.deals'), fixed: const {'onSale': 'true', 'sort': 'discount'}, showBack: false),
          const CartScreen(),
          const AccountScreen(),
        ]),
        bottomNavigationBar: ListenableBuilder(
          listenable: AppState.I,
          builder: (_, __) {
            final items = <(IconData, IconData, String, int)>[
              (Icons.home_outlined, Icons.home_rounded, tr('shop.home'), 0),
              (Icons.grid_view_outlined, Icons.grid_view_rounded, tr('open.f.categories'), 0),
              (Icons.local_fire_department_outlined, Icons.local_fire_department_rounded, tr('open.deals'), 0),
              (Icons.shopping_cart_outlined, Icons.shopping_cart_rounded, tr('nav.cart'), AppState.I.cartCount),
              (Icons.person_outline_rounded, Icons.person_rounded, tr('nav.account'), 0),
            ];
            return Container(
              decoration: BoxDecoration(color: Colors.white, border: const Border(top: BorderSide(color: kLine)), boxShadow: softShadow(.12)),
              child: SafeArea(
                top: false,
                child: SizedBox(
                  height: 62,
                  child: Row(children: [
                    for (var i = 0; i < items.length; i++)
                      Expanded(
                        child: InkResponse(
                          onTap: () => _select(i),
                          radius: 34,
                          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                            Badge(
                              isLabelVisible: items[i].$4 > 0,
                              label: Text('${items[i].$4}', style: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w900, fontSize: 10)),
                              backgroundColor: kAccent,
                              child: AnimatedScale(scale: _tab == i ? 1.12 : 1, duration: const Duration(milliseconds: 180), child: Icon(_tab == i ? items[i].$2 : items[i].$1, size: 25, color: _tab == i ? kAccent : kMute)),
                            ),
                            const SizedBox(height: 3),
                            Text(items[i].$3, maxLines: 1, style: TextStyle(fontSize: 10.5, fontWeight: FontWeight.w800, color: _tab == i ? kAccent : kMute)),
                          ]),
                        ),
                      ),
                  ]),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
