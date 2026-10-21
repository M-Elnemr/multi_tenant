import 'package:elmanassa_open/core/models.dart';
import 'package:elmanassa_open/core/strings.dart';
import 'package:flutter/widgets.dart' show Locale;
import 'package:flutter_test/flutter_test.dart';
import 'package:platform_core/platform_core.dart' show AppLocale;

void main() {
  test('strings follow the language and fill placeholders', () {
    AppLocale.notifier.value = const Locale('en');
    expect(tr('open.cart.items', {'n': 3}), 'Items (3)');
    AppLocale.notifier.value = const Locale('ar');
    expect(tr('open.cart.items', {'n': 3}), 'المنتجات (3)');
    expect(tr('does.not.exist'), 'does.not.exist');
  });

  test('money formats minor units', () {
    AppLocale.notifier.value = const Locale('en');
    expect(money(25000), contains('250'));
    expect(money(null), '-');
  });

  test('product reads the API shape', () {
    final p = Product({'id': 'a', 'name': 'Tee', 'minPriceMinor': 1999, 'compareAtMinor': 2999, 'discountPct': '33.3', 'inStock': true, 'shop': {'id': 's', 'slug': 'x', 'name': 'X'}, 'image': {'thumb': 't', 'medium': 'm'}});
    expect(p.price, 1999);
    expect(p.discount, 33);
    expect(p.shopSlug, 'x');
    expect(p.medium, 'm');
  });
}
