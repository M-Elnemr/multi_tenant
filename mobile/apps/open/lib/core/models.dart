import 'package:intl/intl.dart';
import 'package:platform_core/platform_core.dart' show AppLocale;

num n(Object? v) => v is num ? v : (v is String ? (num.tryParse(v) ?? 0) : 0);
String s(Object? v) => v == null ? '' : '$v';
List<Map<String, dynamic>> list(Object? v) => v is List ? v.whereType<Map>().map((e) => Map<String, dynamic>.from(e)).toList() : <Map<String, dynamic>>[];

/// Money is integer minor units (piastres): 19999 -> 199.99
String money(Object? minor, [String currency = 'EGP']) {
  if (minor == null) return '-';
  final f = NumberFormat.currency(locale: AppLocale.isAr ? 'ar_EG' : 'en_GB', name: currency, symbol: AppLocale.isAr ? 'ج.م' : currency, decimalDigits: n(minor) % 100 == 0 ? 0 : 2);
  return f.format(n(minor) / 100);
}

String localName(Map<String, dynamic> m) => AppLocale.isAr ? s(m['nameAr']) : (s(m['nameEn']).isEmpty ? s(m['nameAr']) : s(m['nameEn']));

class Product {
  Product(this.m);
  final Map<String, dynamic> m;
  String get id => s(m['id']);
  String get name => s(m['name']);
  String get slug => s(m['slug']);
  String get brand => s(m['brand']);
  String get currency => s(m['currency']).isEmpty ? 'EGP' : s(m['currency']);
  int? get price => m['minPriceMinor'] == null ? null : n(m['minPriceMinor']).toInt();
  int? get compareAt => m['compareAtMinor'] == null ? null : n(m['compareAtMinor']).toInt();
  int get discount => n(m['discountPct']).round();
  double get rating => n(m['rating']).toDouble();
  int get ratingCount => n(m['ratingCount']).toInt();
  bool get inStock => m['inStock'] != false;
  bool get freeShipping => m['freeShipping'] == true;
  String get badge => s(m['badge']);
  Map<String, dynamic> get shop => Map<String, dynamic>.from(m['shop'] as Map? ?? const {});
  String get shopId => s(shop['id']).isEmpty ? s(m['shopId']) : s(shop['id']);
  String get shopSlug => s(shop['slug']);
  String get shopName => s(shop['name']);
  String? get thumb => (m['image'] as Map?)?['thumb'] as String?;
  String? get medium => (m['image'] as Map?)?['medium'] as String?;
}

class CartLine {
  CartLine({required this.variantId, required this.productId, required this.shopId, required this.shopSlug, required this.shopName, required this.slug, required this.name, required this.variantLabel, required this.unitPriceMinor, required this.currency, required this.quantity, this.image});
  final String variantId, productId, shopId, shopSlug, shopName, slug, name, variantLabel, currency;
  int unitPriceMinor;
  int quantity;
  final String? image;

  factory CartLine.fromJson(Map<String, dynamic> j) => CartLine(variantId: s(j['variantId']), productId: s(j['productId']), shopId: s(j['shopId']), shopSlug: s(j['shopSlug']), shopName: s(j['shopName']), slug: s(j['slug']), name: s(j['name']), variantLabel: s(j['variantLabel']), unitPriceMinor: n(j['unitPriceMinor']).toInt(), currency: s(j['currency']), quantity: n(j['quantity']).toInt(), image: j['image'] as String?);
  Map<String, dynamic> toJson() => {'variantId': variantId, 'productId': productId, 'shopId': shopId, 'shopSlug': shopSlug, 'shopName': shopName, 'slug': slug, 'name': name, 'variantLabel': variantLabel, 'unitPriceMinor': unitPriceMinor, 'currency': currency, 'quantity': quantity, 'image': image};
}

class WishItem {
  WishItem({required this.id, required this.shopSlug, required this.slug, required this.name, required this.priceMinor, required this.currency, this.image});
  final String id, shopSlug, slug, name, currency;
  final int? priceMinor;
  final String? image;
  factory WishItem.fromJson(Map<String, dynamic> j) => WishItem(id: s(j['id']), shopSlug: s(j['shopSlug']), slug: s(j['slug']), name: s(j['name']), priceMinor: j['priceMinor'] == null ? null : n(j['priceMinor']).toInt(), currency: s(j['currency']), image: j['image'] as String?);
  Map<String, dynamic> toJson() => {'id': id, 'shopSlug': shopSlug, 'slug': slug, 'name': name, 'priceMinor': priceMinor, 'currency': currency, 'image': image};
}
