import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'account.dart';
import 'catalog.dart';
import 'checkout.dart';
import 'common.dart';
import 'info.dart';

/// The shop side of the app: the client enters a store's address, then browses, searches, orders (guest checkout, cash on delivery),
/// follows orders and reaches the shop. Everything the website offers for a shop works here too.
class ShopEntry extends StatelessWidget {
  const ShopEntry({super.key});
  @override
  Widget build(BuildContext context) => TenantGate(initial: null, expectedType: 'STORE', builder: (c, t, api) => StoreLoader(tenant: t, api: api));
}

/// Loads the store's public profile and category tree once, then shows the store.
class StoreLoader extends StatelessWidget {
  const StoreLoader({super.key, required this.tenant, required this.api});
  final TenantInfo tenant;
  final ApiClient api;
  @override
  Widget build(BuildContext context) => Async<(Map<String, dynamic>, List<Map<String, dynamic>>)>(
        load: () async {
          final profile = await api.get('shop/profile') as Map<String, dynamic>;
          final cats = ((await api.get('shop/categories')) as List).cast<Map<String, dynamic>>();
          return (profile, cats);
        },
        builder: (context, data, _) => StoreHome(ctx: StoreCtx(tenant: tenant, api: api, cart: Cart(tenant.host), profile: data.$1, categories: data.$2)),
      );
}

class StoreHome extends StatefulWidget {
  const StoreHome({super.key, required this.ctx});
  final StoreCtx ctx;
  @override
  State<StoreHome> createState() => _StoreHomeState();
}

class _StoreHomeState extends State<StoreHome> {
  int tab = 0;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = widget.ctx;
    final pages = [
      CatalogPage(ctx: c),
      CartPage(ctx: c, onCheckout: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CheckoutPage(ctx: c)))),
      OrdersPage(ctx: c),
      InfoPage(ctx: c),
    ];
    final logo = c.logoId;
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 12,
        title: Row(children: [
          if (logo != null) Padding(padding: const EdgeInsetsDirectional.only(end: 10), child: ClipRRect(borderRadius: BorderRadius.circular(10), child: Image.network(fileImage(c.base, logo), width: 34, height: 34, fit: BoxFit.cover, errorBuilder: (_, __, ___) => const SizedBox.shrink()))),
          Expanded(child: Text(c.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800))),
        ]),
        bottom: c.isOpen ? null : PreferredSize(preferredSize: const Size.fromHeight(28), child: Container(width: double.infinity, color: Colors.black87, padding: const EdgeInsets.all(6), child: Text((c.p['closedMessage'] as String?)?.isNotEmpty == true ? c.p['closedMessage'] as String : (s.ar ? 'المتجر مغلق مؤقتًا' : 'The shop is temporarily closed'), textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 12)))),
      ),
      body: IndexedStack(index: tab, children: pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.storefront_outlined), selectedIcon: const Icon(Icons.storefront), label: s.ar ? 'المتجر' : 'Shop'),
          NavigationDestination(
            icon: ValueListenableBuilder<List<CartLine>>(valueListenable: c.cart, builder: (_, v, __) => Badge(isLabelVisible: v.isNotEmpty, label: Text('${c.cart.count}'), child: const Icon(Icons.shopping_cart_outlined))),
            label: s.cart,
          ),
          NavigationDestination(icon: const Icon(Icons.local_shipping_outlined), selectedIcon: const Icon(Icons.local_shipping), label: s.ar ? 'طلباتي' : 'Orders'),
          NavigationDestination(icon: const Icon(Icons.info_outline), selectedIcon: const Icon(Icons.info), label: s.ar ? 'عن المتجر' : 'About'),
        ],
      ),
    );
  }
}
