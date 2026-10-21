import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';
import 'models.dart';

/// Everything that lives on the phone: the cart, the wishlist, the coupons, the sign-in and the purchases made as a guest.
/// Prices kept here are for display only; the server prices every order again at checkout.
class AppState extends ChangeNotifier {
  AppState._();
  static final AppState I = AppState._();

  final List<CartLine> cart = [];
  final List<WishItem> wishlist = [];
  String coupon = ''; // the code applied in the cart (a cart has one shop, so one coupon)
  final List<Map<String, dynamic>> orders = []; // orders placed on this phone: {shop, shopName, number, phone, at}
  Map<String, dynamic>? account;
  SharedPreferences? _p;

  int get cartCount => cart.fold(0, (a, l) => a + l.quantity);
  bool get signedIn => account != null && OpenApi.token != null;

  Future<void> load() async {
    try {
      _p = await SharedPreferences.getInstance();
    } catch (_) {
      return; // storage unavailable: everything still works for this session
    }
    List<T> read<T>(String k, T Function(Map<String, dynamic>) f) {
      try {
        final raw = _p!.getString(k);
        if (raw == null) return [];
        return (jsonDecode(raw) as List).map((e) => f(Map<String, dynamic>.from(e as Map))).toList();
      } catch (_) {
        return [];
      }
    }

    cart.addAll(read('cart', CartLine.fromJson));
    wishlist.addAll(read('wish', WishItem.fromJson));
    orders.addAll(read('orders', (m) => m));
    try {
      coupon = _p!.getString('coupon') ?? '';
      OpenApi.token = _p!.getString('token');
      final a = _p!.getString('account');
      if (a != null) account = Map<String, dynamic>.from(jsonDecode(a) as Map);
    } catch (_) {/* start clean */}
    if (OpenApi.token == null) account = null;
    notifyListeners();
    if (OpenApi.token != null) refreshAccount();
  }

  void _save() {
    final p = _p;
    if (p == null) return;
    p.setString('cart', jsonEncode(cart.map((e) => e.toJson()).toList()));
    p.setString('wish', jsonEncode(wishlist.map((e) => e.toJson()).toList()));
    p.setString('orders', jsonEncode(orders));
    p.setString('coupon', coupon);
    if (OpenApi.token == null) {
      p.remove('token');
      p.remove('account');
    } else {
      p.setString('token', OpenApi.token!);
      p.setString('account', jsonEncode(account));
    }
  }

  void _changed() {
    _save();
    notifyListeners();
  }

  // ---- cart ----
  /// A cart holds products of ONE shop (delivery and cash collection are per shop). Returns the other shop's name when the cart belongs to a different shop; nothing is added then.
  String? addToCart(CartLine line) {
    if (cart.isNotEmpty && cart.first.shopId != line.shopId) return cart.first.shopName;
    final i = cart.indexWhere((l) => l.variantId == line.variantId);
    if (i >= 0) {
      cart[i].quantity = (cart[i].quantity + line.quantity).clamp(1, 99);
      cart[i].unitPriceMinor = line.unitPriceMinor;
    } else {
      cart.add(line);
    }
    _changed();
    return null;
  }

  /// The shopper chose to drop the other shop's cart and start a new one with this product.
  void replaceCart(CartLine line) {
    cart
      ..clear()
      ..add(line);
    coupon = '';
    _changed();
  }

  void setQty(String variantId, int q) {
    final i = cart.indexWhere((l) => l.variantId == variantId);
    if (i < 0) return;
    cart[i].quantity = q.clamp(1, 99);
    _changed();
  }

  void removeLine(String variantId) {
    cart.removeWhere((l) => l.variantId == variantId);
    if (cart.isEmpty) coupon = '';
    _changed();
  }

  void clearCart() {
    cart.clear();
    coupon = '';
    _changed();
  }

  void setCoupon(String code) {
    coupon = code;
    _changed();
  }

  // ---- wishlist ----
  bool wished(String productId) => wishlist.any((w) => w.id == productId);

  void toggleWish(Product p) {
    if (wished(p.id)) {
      wishlist.removeWhere((w) => w.id == p.id);
    } else {
      wishlist.insert(0, WishItem(id: p.id, shopSlug: p.shopSlug, slug: p.slug, name: p.name, priceMinor: p.price, currency: p.currency, image: p.medium));
      if (wishlist.length > 200) wishlist.removeLast();
    }
    _changed();
  }

  void removeWish(String productId) {
    wishlist.removeWhere((w) => w.id == productId);
    _changed();
  }

  /// Saves and tells listeners (used after code changed the lists directly).
  void refresh() => _changed();

  // ---- account ----
  void signIn(String token, Map<String, dynamic> acc) {
    OpenApi.token = token;
    account = acc;
    _changed();
  }

  Future<void> signOut() async {
    try {
      await OpenApi.post('auth/logout');
    } catch (_) {/* the token is forgotten here either way */}
    OpenApi.token = null;
    account = null;
    _changed();
  }

  Future<void> refreshAccount() async {
    try {
      account = Map<String, dynamic>.from(await OpenApi.get('me') as Map);
      _changed();
    } on ApiException catch (e) {
      if (e.status == 401) {
        OpenApi.token = null;
        account = null;
        _changed();
      }
    }
  }

  void rememberOrder(String shop, String shopName, String number, String phone) {
    orders.removeWhere((o) => o['shop'] == shop && o['number'] == number);
    orders.insert(0, {'shop': shop, 'shopName': shopName, 'number': number, 'phone': phone, 'at': DateTime.now().millisecondsSinceEpoch});
    if (orders.length > 30) orders.removeLast();
    _changed();
  }
}
