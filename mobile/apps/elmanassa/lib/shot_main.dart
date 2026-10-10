// TEMPORARY: opens one screen of the real app for store-listing screenshots. Not committed.
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:platform_core/platform_core.dart';

import 'main.dart';
import 'shop/common.dart';
import 'shop/product.dart';
import 'shop/shop.dart';

const shot = String.fromEnvironment('SHOT');
const host = String.fromEnvironment('HOST', defaultValue: 'demo');

void main() => runApp(MaterialApp(
      debugShowCheckedModeBanner: false,
      locale: const Locale('ar'),
      theme: themeFor(null),
      localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
      supportedLocales: const [Locale('ar'), Locale('en')],
      home: FutureBuilder<TenantInfo>(
        future: TenantInfo.resolve(host),
        builder: (c, snap) {
          if (!snap.hasData) return const Scaffold(body: Center(child: CircularProgressIndicator()));
          final t = snap.data!;
          return Theme(
            data: themeFor(t),
            child: switch (shot) {
              'clinic' => Builder(builder: (c) {
                  final api = ApiClient(baseUrl: t.baseUrl, session: Session(t.host));
                  return Scaffold(appBar: AppBar(title: Text(t.name)), body: ClinicInfoPage(api: api));
                }),
              'product' => Builder(builder: (c) {
                  final api = ApiClient(baseUrl: t.baseUrl, session: Session(t.host));
                  return StoreLoaderProduct(tenant: t, api: api);
                }),
              _ => StoreLoader(tenant: t, api: ApiClient(baseUrl: t.baseUrl, session: Session(t.host))),
            },
          );
        },
      ),
    ));

class StoreLoaderProduct extends StatelessWidget {
  const StoreLoaderProduct({super.key, required this.tenant, required this.api});
  final TenantInfo tenant;
  final ApiClient api;
  @override
  Widget build(BuildContext context) => Async<(Map<String, dynamic>, List<Map<String, dynamic>>)>(
        load: () async => (await api.get('shop/profile') as Map<String, dynamic>, <Map<String, dynamic>>[]),
        builder: (context, data, _) => Async<Map<String, dynamic>>(
          load: () async => await api.get('shop/products', query: {'pageSize': '12'}) as Map<String, dynamic>,
          builder: (context, list, _) {
            final slug = ((list['data'] as List).cast<Map<String, dynamic>>().firstWhere((p) => '${p['name']}'.contains('ملايات'), orElse: () => (list['data'] as List).first as Map<String, dynamic>))['slug'] as String;
            return ProductPage(ctx: StoreCtx(tenant: tenant, api: api, cart: Cart(tenant.host), profile: data.$1, categories: data.$2), slug: slug);
          },
        ),
      );
}
