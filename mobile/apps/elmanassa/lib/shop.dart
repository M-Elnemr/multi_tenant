import 'dart:math';

import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:platform_core/platform_core.dart';

class CartLine {
  CartLine(this.variantId, this.name, this.label, this.priceMinor, this.qty);
  final String variantId, name, label;
  final int priceMinor;
  int qty;
}

/// The shop side of the app: the client enters a store's address, then browses, orders (guest checkout, cash on delivery) and optionally signs in with Google to follow orders.
class ShopEntry extends StatelessWidget {
  const ShopEntry({super.key});
  @override
  Widget build(BuildContext context) => TenantGate(initial: null, expectedType: 'STORE', builder: (c, t, api) => StoreHome(tenant: t, api: api));
}

class StoreHome extends StatefulWidget {
  const StoreHome({super.key, required this.tenant, required this.api});
  final TenantInfo tenant;
  final ApiClient api;
  @override
  State<StoreHome> createState() => _StoreHomeState();
}

class _StoreHomeState extends State<StoreHome> {
  final cart = ValueNotifier<List<CartLine>>([]);
  int tab = 0;

  void add(CartLine l) {
    final i = cart.value.indexWhere((x) => x.variantId == l.variantId);
    if (i >= 0) {
      cart.value[i].qty += l.qty;
      cart.value = [...cart.value];
    } else {
      cart.value = [...cart.value, l];
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final pages = [
      ProductsPage(api: widget.api, tenant: widget.tenant, onAdd: add),
      CartPage(api: widget.api, tenant: widget.tenant, cart: cart),
      OrdersPage(api: widget.api, tenant: widget.tenant),
    ];
    return Scaffold(
      appBar: AppBar(title: Text(widget.tenant.name)),
      body: pages[tab],
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.storefront_outlined), label: s.products),
          NavigationDestination(
            icon: ValueListenableBuilder<List<CartLine>>(
              valueListenable: cart,
              builder: (_, v, __) => Badge(isLabelVisible: v.isNotEmpty, label: Text('${v.fold<int>(0, (n, l) => n + l.qty)}'), child: const Icon(Icons.shopping_cart_outlined)),
            ),
            label: s.cart,
          ),
          NavigationDestination(icon: const Icon(Icons.receipt_long_outlined), label: s.orders),
        ],
      ),
    );
  }
}

class ProductsPage extends StatelessWidget {
  const ProductsPage({super.key, required this.api, required this.tenant, required this.onAdd});
  final ApiClient api;
  final TenantInfo tenant;
  final void Function(CartLine) onAdd;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      load: () async => await api.get('shop/products', query: {'pageSize': '40'}) as Map<String, dynamic>,
      builder: (context, data, reload) {
        final items = (data['data'] as List).cast<Map<String, dynamic>>();
        if (items.isEmpty) return Center(child: Text(s.empty));
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView.separated(
            itemCount: items.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (_, i) {
              final p = items[i];
              return ListTile(
                title: Text(p['name'] as String),
                subtitle: Text(s.money(p['minPriceMinor'] as num?, tenant.currency)),
                trailing: p['inStock'] == true ? const Icon(Icons.chevron_right) : const Text('—'),
                onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProductPage(api: api, tenant: tenant, slug: p['slug'] as String, onAdd: onAdd))),
              );
            },
          ),
        );
      },
    );
  }
}

class ProductPage extends StatefulWidget {
  const ProductPage({super.key, required this.api, required this.tenant, required this.slug, required this.onAdd});
  final ApiClient api;
  final TenantInfo tenant;
  final String slug;
  final void Function(CartLine) onAdd;
  @override
  State<ProductPage> createState() => _ProductPageState();
}

class _ProductPageState extends State<ProductPage> {
  int selected = 0;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(),
      body: Async<Map<String, dynamic>>(
        load: () async => await widget.api.get('shop/products/${widget.slug}') as Map<String, dynamic>,
        builder: (context, p, _) {
          final variants = (p['variants'] as List).cast<Map<String, dynamic>>();
          final v = variants[selected.clamp(0, variants.length - 1)];
          String label(Map<String, dynamic> x) => (x['comboKey'] as String).isEmpty ? (x['sku'] as String) : (x['comboKey'] as String).replaceAll('|', ' / ').replaceAll('=', ': ');
          final inStock = (v['available'] as num) > 0;
          return ListView(padding: const EdgeInsets.all(16), children: [
            Text(p['name'] as String, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 8),
            Text(s.money(v['priceMinor'] as num, widget.tenant.currency), style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            if (variants.length > 1)
              Wrap(spacing: 8, children: [for (var i = 0; i < variants.length; i++) ChoiceChip(label: Text(label(variants[i])), selected: i == selected, onSelected: (_) => setState(() => selected = i))]),
            const SizedBox(height: 16),
            Text((p['description'] ?? '') as String),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: inStock
                  ? () {
                      widget.onAdd(CartLine(v['id'] as String, p['name'] as String, label(v), (v['priceMinor'] as num).toInt(), 1));
                      Navigator.of(context).pop();
                    }
                  : null,
              child: Text(s.addToCart),
            ),
          ]);
        },
      ),
    );
  }
}

class CartPage extends StatelessWidget {
  const CartPage({super.key, required this.api, required this.tenant, required this.cart});
  final ApiClient api;
  final TenantInfo tenant;
  final ValueNotifier<List<CartLine>> cart;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return ValueListenableBuilder<List<CartLine>>(
      valueListenable: cart,
      builder: (context, lines, _) {
        if (lines.isEmpty) return Center(child: Text(s.empty));
        final total = lines.fold<int>(0, (n, l) => n + l.priceMinor * l.qty);
        return Column(children: [
          Expanded(
            child: ListView(children: [
              for (final l in lines)
                ListTile(
                  title: Text(l.name),
                  subtitle: Text('${l.label}\n${s.money(l.priceMinor * l.qty, tenant.currency)}'),
                  isThreeLine: l.label.isNotEmpty,
                  trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                    IconButton(icon: const Icon(Icons.remove_circle_outline), onPressed: () { if (l.qty > 1) { l.qty--; cart.value = [...cart.value]; } else { cart.value = cart.value.where((x) => x != l).toList(); } }),
                    Text('${l.qty}', style: const TextStyle(fontWeight: FontWeight.w700)),
                    IconButton(icon: const Icon(Icons.add_circle_outline), onPressed: () { if (l.qty < 99) { l.qty++; cart.value = [...cart.value]; } }),
                  ]),
                ),
            ]),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text('${s.total}: ${s.money(total, tenant.currency)}', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              FilledButton(onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CheckoutPage(api: api, tenant: tenant, cart: cart))), child: Text(s.checkout)),
            ]),
          ),
        ]);
      },
    );
  }
}

String _newKey() {
  final r = Random.secure();
  return List.generate(24, (_) => r.nextInt(36).toRadixString(36)).join();
}

class CheckoutPage extends StatefulWidget {
  const CheckoutPage({super.key, required this.api, required this.tenant, required this.cart});
  final ApiClient api;
  final TenantInfo tenant;
  final ValueNotifier<List<CartLine>> cart;
  @override
  State<CheckoutPage> createState() => _CheckoutPageState();
}

class _CheckoutPageState extends State<CheckoutPage> {
  final key = _newKey(); // one Idempotency-Key per checkout attempt: a retry can never create a second order
  final name = TextEditingController(), phone = TextEditingController(), line1 = TextEditingController(), city = TextEditingController();
  bool? loggedIn;
  List<Map<String, dynamic>> shipping = [];
  String? shippingId;
  String? error;
  bool busy = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final li = await widget.api.session.loggedIn;
    final prof = await widget.api.get('shop/profile') as Map<String, dynamic>;
    if (!mounted) return;
    setState(() {
      loggedIn = li;
      shipping = (prof['shippingMethods'] as List).cast<Map<String, dynamic>>();
      shippingId = shipping.isEmpty ? null : shipping.first['id'] as String;
    });
  }

  Future<void> _place() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final pickup = shipping.firstWhere((x) => x['id'] == shippingId)['type'] == 'PICKUP';
      final r = await widget.api.post('shop/checkout', {
        'items': [for (final l in widget.cart.value) {'variantId': l.variantId, 'quantity': l.qty}],
        'shippingMethodId': shippingId,
        'paymentMethod': 'CASH_ON_DELIVERY',
        'address': {'recipientName': name.text.trim(), 'phone': phone.text.trim(), if (!pickup) 'addressLine1': line1.text.trim(), if (!pickup) 'city': city.text.trim()},
      }, key) as Map<String, dynamic>;
      widget.cart.value = [];
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${S.of(context).orderPlaced}: ${r['orderNumber']}')));
      Navigator.of(context).pop();
    } catch (e) {
      setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (loggedIn == null) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    final pickup = shipping.isNotEmpty && shippingId != null && shipping.firstWhere((x) => x['id'] == shippingId, orElse: () => shipping.first)['type'] == 'PICKUP';
    return Scaffold(
      appBar: AppBar(title: Text(s.checkout)),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        DropdownButtonFormField<String>(
          initialValue: shippingId,
          items: [for (final x in shipping) DropdownMenuItem(value: x['id'] as String, child: Text(x['name'] as String))],
          onChanged: (v) => setState(() => shippingId = v),
        ),
        const SizedBox(height: 12),
        TextField(controller: name, decoration: InputDecoration(labelText: s.ar ? 'الاسم' : 'Name')),
        const SizedBox(height: 12),
        TextField(controller: phone, keyboardType: TextInputType.phone, decoration: InputDecoration(labelText: s.ar ? 'رقم الموبايل' : 'Mobile number')),
        const SizedBox(height: 12),
        if (!pickup) ...[
          TextField(controller: line1, decoration: InputDecoration(labelText: s.address1)),
          const SizedBox(height: 12),
          TextField(controller: city, decoration: InputDecoration(labelText: s.city)),
          const SizedBox(height: 12),
        ],
        Text(s.cod),
        const SizedBox(height: 4),
        Text(s.ar ? 'لا تحتاج حسابًا لإتمام الطلب.' : 'No account needed to order.', style: Theme.of(context).textTheme.bodySmall),
        if (error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
        const SizedBox(height: 16),
        FilledButton(onPressed: busy ? null : _place, child: Text(s.placeOrder)),
      ]),
    );
  }
}

class OrdersPage extends StatelessWidget {
  const OrdersPage({super.key, required this.api, required this.tenant});
  final ApiClient api;
  final TenantInfo tenant;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return FutureBuilder<bool>(
      future: api.session.loggedIn,
      builder: (context, snap) {
        if (!snap.hasData) return const Center(child: CircularProgressIndicator());
        if (!snap.data!) {
          return Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(s.ar ? 'سجّل الدخول بجوجل لمتابعة طلباتك. يمكنك الطلب دون حساب.' : 'Sign in with Google to follow your orders. You can order without an account.', textAlign: TextAlign.center),
            const SizedBox(height: 16),
            GoogleSignInButton(api: api, onSignedIn: () => (context as Element).markNeedsBuild()),
          ])));
        }
        return Async<Map<String, dynamic>>(
          load: () async => await api.get('shop/orders') as Map<String, dynamic>,
          builder: (context, data, reload) {
            final orders = (data['data'] as List).cast<Map<String, dynamic>>();
            if (orders.isEmpty) return Center(child: Text(s.empty));
            return RefreshIndicator(
              onRefresh: reload,
              child: ListView(children: [
                for (final o in orders) ListTile(title: Text(o['orderNumber'] as String), subtitle: Text(_status(s, o['status'] as String)), trailing: Text(s.money(o['totalMinor'] as num, tenant.currency))),
              ]),
            );
          },
        );
      },
    );
  }
}

/// Order progress in the customer's language.
String _status(S s, String code) => switch (code) {
      'REQUESTED' || 'PENDING' => s.ar ? 'تم الطلب' : 'Requested',
      'PREPARING' => s.ar ? 'جاري التجهيز للشحن' : 'Preparing to ship',
      'SHIPPED' => s.ar ? 'تم الشحن' : 'Shipped',
      'ARRIVED' => s.ar ? 'وصل' : 'Arrived',
      'RETURNED' => s.ar ? 'مرتجع' : 'Returned',
      'CANCELLED' => s.ar ? 'ملغي' : 'Cancelled',
      _ => code,
    };

/// Google sign-in for shop clients. The OAuth client id is a build setting (--dart-define=GOOGLE_CLIENT_ID=...); until it is set the button explains that it is coming.
class GoogleSignInButton extends StatefulWidget {
  const GoogleSignInButton({super.key, required this.api, required this.onSignedIn});
  final ApiClient api;
  final VoidCallback onSignedIn;
  @override
  State<GoogleSignInButton> createState() => _GoogleSignInButtonState();
}

class _GoogleSignInButtonState extends State<GoogleSignInButton> {
  static const clientId = String.fromEnvironment('GOOGLE_CLIENT_ID');
  String? error;
  bool busy = false;

  Future<void> _go() async {
    setState(() { busy = true; error = null; });
    try {
      final account = await GoogleSignIn(serverClientId: clientId, scopes: const ['email']).signIn();
      if (account == null) return;
      final idToken = (await account.authentication).idToken;
      if (idToken == null) throw StateError('No Google token');
      final r = await widget.api.post('auth/client/google', {'credential': idToken}) as Map<String, dynamic>;
      await widget.api.session.save(r);
      widget.onSignedIn();
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (clientId.isEmpty) return Text(s.ar ? 'تسجيل الدخول بجوجل سيتوفر قريبًا.' : 'Google sign-in is coming soon.', style: Theme.of(context).textTheme.bodySmall);
    return Column(mainAxisSize: MainAxisSize.min, children: [
      OutlinedButton.icon(onPressed: busy ? null : _go, icon: const Icon(Icons.login), label: Text(s.ar ? 'المتابعة بجوجل' : 'Continue with Google')),
      if (error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
    ]);
  }
}
